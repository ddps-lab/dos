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

package org.apache.spark.sql.execution.adaptive

import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, LogicalPlanIntegrity, PlanHelper}
import org.apache.spark.sql.catalyst.rules.RuleExecutor
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.util.Utils

/**
 * The optimizer for re-optimizing the logical plan used by AdaptiveSparkPlanExec.
 */
class AQEOptimizer(conf: SQLConf) extends RuleExecutor[LogicalPlan] {
  private val defaultBatches = Seq(
    Batch("Demote BroadcastHashJoin", Once,
      DemoteBroadcastHashJoin),
    Batch("Eliminate Join to Empty Relation", Once, EliminateJoinToEmptyRelation)
  )

  final override protected def batches: Seq[Batch] = {
    val excludedRules = conf.getConf(SQLConf.ADAPTIVE_OPTIMIZER_EXCLUDED_RULES)
      .toSeq.flatMap(Utils.stringToSeq)
    defaultBatches.flatMap { batch =>
      val filteredRules = batch.rules.filter { rule =>
        val exclude = excludedRules.contains(rule.ruleName)
        if (exclude) {
          logInfo(s"Optimization rule '${rule.ruleName}' is excluded from the optimizer.")
        }
        !exclude
      }
      if (batch.rules == filteredRules) {
        Some(batch)
      } else if (filteredRules.nonEmpty) {
        Some(Batch(batch.name, batch.strategy, filteredRules: _*))
      } else {
        logInfo(s"Optimization batch '${batch.name}' is excluded from the optimizer " +
          s"as all enclosed rules have been excluded.")
        None
      }
    }
  }

  override protected def isPlanIntegral(plan: LogicalPlan): Boolean = {
    !Utils.isTesting || (plan.resolved &&
      plan.find(PlanHelper.specialExpressionsInUnsupportedOperator(_).nonEmpty).isEmpty &&
      LogicalPlanIntegrity.checkIfExprIdsAreGloballyUnique(plan))
  }
}
