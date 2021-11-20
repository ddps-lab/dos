/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.expressions.{Expression, PlanExpression}
import org.apache.spark.sql.catalyst.plans.QueryPlan
import org.apache.spark.sql.execution.adaptive.{AdaptiveSparkPlanExec, AdaptiveSparkPlanHelper, QueryStageExec}

object ExplainUtils extends AdaptiveSparkPlanHelper {
  /**
   * Given a input physical plan, performs the following tasks.
   *   1. Computes the operator id for current operator and records it in the operator
   *      by setting a tag.
   *   2. Computes the whole stage codegen id for current operator and records it in the
   *      operator by setting a tag.
   *   3. Generate the two part explain output for this plan.
   *      1. First part explains the operator tree with each operator tagged with an unique
   *         identifier.
   *      2. Second part explains each operator in a verbose manner.
   *
   * Note : This function skips over subqueries. They are handled by its caller.
   *
   * @param plan Input query plan to process
   * @param append function used to append the explain output
   * @param startOperatorID The start value of operation id. The subsequent operations will
   *                         be assigned higher value.
   *
   * @return The last generated operation id for this input plan. This is to ensure we
   *         always assign incrementing unique id to each operator.
   *
   */
  private def processPlanSkippingSubqueries[T <: QueryPlan[T]](
      plan: => QueryPlan[T],
      append: String => Unit,
      startOperatorID: Int): Int = {

    val operationIDs = new mutable.ArrayBuffer[(Int, QueryPlan[_])]()
    var currentOperatorID = startOperatorID
    try {
      currentOperatorID = generateOperatorIDs(plan, currentOperatorID, operationIDs)
      generateWholeStageCodegenIds(plan)

      QueryPlan.append(
        plan,
        append,
        verbose = false,
        addSuffix = false,
        printOperatorId = true)

      append("\n")
      var i: Integer = 0
      for ((opId, curPlan) <- operationIDs) {
        append(curPlan.verboseStringWithOperatorId())
      }
    } catch {
      case e: AnalysisException => append(e.toString)
    }
    currentOperatorID
  }

  /**
   * Given a input physical plan, performs the following tasks.
   *   1. Generates the explain output for the input plan excluding the subquery plans.
   *   2. Generates the explain output for each subquery referenced in the plan.
   */
  def processPlan[T <: QueryPlan[T]](
      plan: => QueryPlan[T],
      append: String => Unit): Unit = {
    try {
      val subqueries = ArrayBuffer.empty[(SparkPlan, Expression, BaseSubqueryExec)]
      var currentOperatorID = 0
      currentOperatorID = processPlanSkippingSubqueries(plan, append, currentOperatorID)
      getSubqueries(plan, subqueries)
      var i = 0

      for (sub <- subqueries) {
        if (i == 0) {
          append("\n===== Subqueries =====\n\n")
        }
        i = i + 1
        append(s"Subquery:$i Hosting operator id = " +
          s"${getOpId(sub._1)} Hosting Expression = ${sub._2}\n")

        // For each subquery expression in the parent plan, process its child plan to compute
        // the explain output. In case of subquery reuse, we don't print subquery plan more
        // than once. So we skip [[ReusedSubqueryExec]] here.
        if (!sub._3.isInstanceOf[ReusedSubqueryExec]) {
          currentOperatorID = processPlanSkippingSubqueries(
            sub._3.child,
            append,
            currentOperatorID)
        }
        append("\n")
      }
    } finally {
      removeTags(plan)
    }
  }

  /**
   * Traverses the supplied input plan in a bottom-up fashion does the following :
   *    1. produces a map : operator identifier -> operator
   *    2. Records the operator id via setting a tag in the operator.
   * Note :
   *    1. Operator such as WholeStageCodegenExec and InputAdapter are skipped as they don't
   *       appear in the explain output.
   *    2. operator identifier starts at startOperatorID + 1
   * @param plan Input query plan to process
   * @param startOperatorID The start value of operation id. The subsequent operations will
   *                         be assigned higher value.
   * @param operatorIDs A output parameter that contains a map of operator id and query plan. This
   *                    is used by caller to print the detail portion of the plan.
   * @return The last generated operation id for this input plan. This is to ensure we
   *         always assign incrementing unique id to each operator.
   */
  private def generateOperatorIDs(
      plan: QueryPlan[_],
      startOperatorID: Int,
      operatorIDs: mutable.ArrayBuffer[(Int, QueryPlan[_])]): Int = {
    var currentOperationID = startOperatorID
    // Skip the subqueries as they are not printed as part of main query block.
    if (plan.isInstanceOf[BaseSubqueryExec]) {
      return currentOperationID
    }
    plan.foreachUp {
      case p: WholeStageCodegenExec =>
      case p: InputAdapter =>
      case other: QueryPlan[_] =>

        def setOpId(): Unit = if (other.getTagValue(QueryPlan.OP_ID_TAG).isEmpty) {
          currentOperationID += 1
          other.setTagValue(QueryPlan.OP_ID_TAG, currentOperationID)
          operatorIDs += ((currentOperationID, other))
        }

        other match {
          case p: AdaptiveSparkPlanExec =>
            currentOperationID =
              generateOperatorIDs(p.executedPlan, currentOperationID, operatorIDs)
            setOpId()
          case p: QueryStageExec =>
            currentOperationID = generateOperatorIDs(p.plan, currentOperationID, operatorIDs)
            setOpId()
          case _ =>
            setOpId()
            other.innerChildren.foldLeft(currentOperationID) {
              (curId, plan) => generateOperatorIDs(plan, curId, operatorIDs)
            }
        }
    }
    currentOperationID
  }

  /**
   * Traverses the supplied input plan in a top-down fashion and records the
   * whole stage code gen id in the plan via setting a tag.
   */
  private def generateWholeStageCodegenIds(plan: QueryPlan[_]): Unit = {
    var currentCodegenId = -1

    def setCodegenId(p: QueryPlan[_], children: Seq[QueryPlan[_]]): Unit = {
      if (currentCodegenId != -1) {
        p.setTagValue(QueryPlan.CODEGEN_ID_TAG, currentCodegenId)
      }
      children.foreach(generateWholeStageCodegenIds)
    }

    // Skip the subqueries as they are not printed as part of main query block.
    if (plan.isInstanceOf[BaseSubqueryExec]) {
      return
    }
    plan.foreach {
      case p: WholeStageCodegenExec => currentCodegenId = p.codegenStageId
      case _: InputAdapter => currentCodegenId = -1
      case p: AdaptiveSparkPlanExec => setCodegenId(p, Seq(p.executedPlan))
      case p: QueryStageExec => setCodegenId(p, Seq(p.plan))
      case other: QueryPlan[_] => setCodegenId(other, other.innerChildren)
    }
  }

  /**
   * Generate detailed field string with different format based on type of input value
   */
  def generateFieldString(fieldName: String, values: Any): String = values match {
    case iter: Iterable[_] if (iter.size == 0) => s"${fieldName}: []"
    case iter: Iterable[_] => s"${fieldName} [${iter.size}]: ${iter.mkString("[", ", ", "]")}"
    case str: String if (str == null || str.isEmpty) => s"${fieldName}: None"
    case str: String => s"${fieldName}: ${str}"
    case _ => throw new IllegalArgumentException(s"Unsupported type for argument values: $values")
  }

  /**
   * Given a input plan, returns an array of tuples comprising of :
   *  1. Hosting operator id.
   *  2. Hosting expression
   *  3. Subquery plan
   */
  private def getSubqueries(
      plan: => QueryPlan[_],
      subqueries: ArrayBuffer[(SparkPlan, Expression, BaseSubqueryExec)]): Unit = {
    plan.foreach {
      case a: AdaptiveSparkPlanExec =>
        getSubqueries(a.executedPlan, subqueries)
      case p: SparkPlan =>
        p.expressions.foreach (_.collect {
          case e: PlanExpression[_] =>
            e.plan match {
              case s: BaseSubqueryExec =>
                subqueries += ((p, e, s))
                getSubqueries(s, subqueries)
              case _ =>
            }
        })
    }
  }

  /**
   * Returns the operator identifier for the supplied plan by retrieving the
   * `operationId` tag value.
   */
  def getOpId(plan: QueryPlan[_]): String = {
    plan.getTagValue(QueryPlan.OP_ID_TAG).map(v => s"$v").getOrElse("unknown")
  }

  def removeTags(plan: QueryPlan[_]): Unit = {
    def remove(p: QueryPlan[_], children: Seq[QueryPlan[_]]): Unit = {
      p.unsetTagValue(QueryPlan.OP_ID_TAG)
      p.unsetTagValue(QueryPlan.CODEGEN_ID_TAG)
      children.foreach(removeTags)
    }

    plan foreach {
      case p: AdaptiveSparkPlanExec => remove(p, Seq(p.executedPlan))
      case p: QueryStageExec => remove(p, Seq(p.plan))
      case plan: QueryPlan[_] => remove(plan, plan.innerChildren)
    }
  }
}
