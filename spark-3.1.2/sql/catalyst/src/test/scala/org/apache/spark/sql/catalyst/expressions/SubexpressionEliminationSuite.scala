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
package org.apache.spark.sql.catalyst.expressions

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{BinaryType, DataType, IntegerType}

class SubexpressionEliminationSuite extends SparkFunSuite with ExpressionEvalHelper {
  test("Semantic equals and hash") {
    val a: AttributeReference = AttributeReference("name", IntegerType)()
    val id = {
      // Make sure we use a "ExprId" different from "a.exprId"
      val _id = ExprId(1)
      if (a.exprId == _id) ExprId(2) else _id
    }
    val b1 = a.withName("name2").withExprId(id)
    val b2 = a.withExprId(id)
    val b3 = a.withQualifier(Seq("qualifierName"))

    assert(b1 != b2)
    assert(a != b1)
    assert(b1.semanticEquals(b2))
    assert(!b1.semanticEquals(a))
    assert(a.hashCode != b1.hashCode)
    assert(b1.hashCode != b2.hashCode)
    assert(b1.semanticHash() == b2.semanticHash())
    assert(a != b3)
    assert(a.hashCode != b3.hashCode)
    assert(a.semanticEquals(b3))
  }

  test("Expression Equivalence - basic") {
    val equivalence = new EquivalentExpressions
    assert(equivalence.getAllEquivalentExprs.isEmpty)

    val oneA = Literal(1)
    val oneB = Literal(1)
    val twoA = Literal(2)
    var twoB = Literal(2)

    assert(equivalence.getEquivalentExprs(oneA).isEmpty)
    assert(equivalence.getEquivalentExprs(twoA).isEmpty)

    // Add oneA and test if it is returned. Since it is a group of one, it does not.
    assert(!equivalence.addExpr(oneA))
    assert(equivalence.getEquivalentExprs(oneA).size == 1)
    assert(equivalence.getEquivalentExprs(twoA).isEmpty)
    assert(equivalence.addExpr((oneA)))
    assert(equivalence.getEquivalentExprs(oneA).size == 2)

    // Add B and make sure they can see each other.
    assert(equivalence.addExpr(oneB))
    // Use exists and reference equality because of how equals is defined.
    assert(equivalence.getEquivalentExprs(oneA).exists(_ eq oneB))
    assert(equivalence.getEquivalentExprs(oneA).exists(_ eq oneA))
    assert(equivalence.getEquivalentExprs(oneB).exists(_ eq oneA))
    assert(equivalence.getEquivalentExprs(oneB).exists(_ eq oneB))
    assert(equivalence.getEquivalentExprs(twoA).isEmpty)
    assert(equivalence.getAllEquivalentExprs.size == 1)
    assert(equivalence.getAllEquivalentExprs.head.size == 3)
    assert(equivalence.getAllEquivalentExprs.head.contains(oneA))
    assert(equivalence.getAllEquivalentExprs.head.contains(oneB))

    val add1 = Add(oneA, oneB)
    val add2 = Add(oneA, oneB)

    equivalence.addExpr(add1)
    equivalence.addExpr(add2)

    assert(equivalence.getAllEquivalentExprs.size == 2)
    assert(equivalence.getEquivalentExprs(add2).exists(_ eq add1))
    assert(equivalence.getEquivalentExprs(add2).size == 2)
    assert(equivalence.getEquivalentExprs(add1).exists(_ eq add2))
  }

  test("Expression Equivalence - Trees") {
    val one = Literal(1)
    val two = Literal(2)

    val add = Add(one, two)
    val abs = Abs(add)
    val add2 = Add(add, add)

    var equivalence = new EquivalentExpressions
    equivalence.addExprTree(add)
    equivalence.addExprTree(abs)
    equivalence.addExprTree(add2)

    // Should only have one equivalence for `one + two`
    assert(equivalence.getAllEquivalentExprs.count(_.size > 1) == 1)
    assert(equivalence.getAllEquivalentExprs.filter(_.size > 1).head.size == 4)

    // Set up the expressions
    //   one * two,
    //   (one * two) * (one * two)
    //   sqrt( (one * two) * (one * two) )
    //   (one * two) + sqrt( (one * two) * (one * two) )
    equivalence = new EquivalentExpressions
    val mul = Multiply(one, two)
    val mul2 = Multiply(mul, mul)
    val sqrt = Sqrt(mul2)
    val sum = Add(mul2, sqrt)
    equivalence.addExprTree(mul)
    equivalence.addExprTree(mul2)
    equivalence.addExprTree(sqrt)
    equivalence.addExprTree(sum)

    // (one * two), (one * two) * (one * two) and sqrt( (one * two) * (one * two) ) should be found
    assert(equivalence.getAllEquivalentExprs.count(_.size > 1) == 3)
    assert(equivalence.getEquivalentExprs(mul).size == 3)
    assert(equivalence.getEquivalentExprs(mul2).size == 3)
    assert(equivalence.getEquivalentExprs(sqrt).size == 2)
    assert(equivalence.getEquivalentExprs(sum).size == 1)
  }

  test("Expression equivalence - non deterministic") {
    val sum = Add(Rand(0), Rand(0))
    val equivalence = new EquivalentExpressions
    equivalence.addExpr(sum)
    equivalence.addExpr(sum)
    assert(equivalence.getAllEquivalentExprs.isEmpty)
  }

  test("Children of CodegenFallback") {
    val one = Literal(1)
    val two = Add(one, one)
    val fallback = CodegenFallbackExpression(two)
    val add = Add(two, fallback)

    val equivalence = new EquivalentExpressions
    equivalence.addExprTree(add)
    // the `two` inside `fallback` should not be added
    assert(equivalence.getAllEquivalentExprs.count(_.size > 1) == 0)
    assert(equivalence.getAllEquivalentExprs.count(_.size == 1) == 3) // add, two, explode
  }

  test("Children of conditional expressions: If") {
    val add = Add(Literal(1), Literal(2))
    val condition = GreaterThan(add, Literal(3))

    val ifExpr1 = If(condition, add, add)
    val equivalence1 = new EquivalentExpressions
    equivalence1.addExprTree(ifExpr1)

    // `add` is in both two branches of `If` and predicate.
    assert(equivalence1.getAllEquivalentExprs.count(_.size == 2) == 1)
    assert(equivalence1.getAllEquivalentExprs.filter(_.size == 2).head == Seq(add, add))
    // one-time expressions: only ifExpr and its predicate expression
    assert(equivalence1.getAllEquivalentExprs.count(_.size == 1) == 2)
    assert(equivalence1.getAllEquivalentExprs.filter(_.size == 1).contains(Seq(ifExpr1)))
    assert(equivalence1.getAllEquivalentExprs.filter(_.size == 1).contains(Seq(condition)))

    // Repeated `add` is only in one branch, so we don't count it.
    val ifExpr2 = If(condition, Add(Literal(1), Literal(3)), Add(add, add))
    val equivalence2 = new EquivalentExpressions
    equivalence2.addExprTree(ifExpr2)

    assert(equivalence2.getAllEquivalentExprs.count(_.size > 1) == 0)
    assert(equivalence2.getAllEquivalentExprs.count(_.size == 1) == 3)

    val ifExpr3 = If(condition, ifExpr1, ifExpr1)
    val equivalence3 = new EquivalentExpressions
    equivalence3.addExprTree(ifExpr3)

    // `add`: 2, `condition`: 2
    assert(equivalence3.getAllEquivalentExprs.count(_.size == 2) == 2)
    assert(equivalence3.getAllEquivalentExprs.filter(_.size == 2).contains(Seq(add, add)))
    assert(
      equivalence3.getAllEquivalentExprs.filter(_.size == 2).contains(Seq(condition, condition)))

    // `ifExpr1`, `ifExpr3`
    assert(equivalence3.getAllEquivalentExprs.count(_.size == 1) == 2)
    assert(equivalence3.getAllEquivalentExprs.filter(_.size == 1).contains(Seq(ifExpr1)))
    assert(equivalence3.getAllEquivalentExprs.filter(_.size == 1).contains(Seq(ifExpr3)))
  }

  test("Children of conditional expressions: CaseWhen") {
    val add1 = Add(Literal(1), Literal(2))
    val add2 = Add(Literal(2), Literal(3))
    val conditions1 = (GreaterThan(add2, Literal(3)), add1) ::
      (GreaterThan(add2, Literal(4)), add1) ::
      (GreaterThan(add2, Literal(5)), add1) :: Nil

    val caseWhenExpr1 = CaseWhen(conditions1, None)
    val equivalence1 = new EquivalentExpressions
    equivalence1.addExprTree(caseWhenExpr1)

    // `add2` is repeatedly in all conditions.
    assert(equivalence1.getAllEquivalentExprs.count(_.size == 2) == 1)
    assert(equivalence1.getAllEquivalentExprs.filter(_.size == 2).head == Seq(add2, add2))

    val conditions2 = (GreaterThan(add1, Literal(3)), add1) ::
      (GreaterThan(add2, Literal(4)), add1) ::
      (GreaterThan(add2, Literal(5)), add1) :: Nil

    val caseWhenExpr2 = CaseWhen(conditions2, None)
    val equivalence2 = new EquivalentExpressions
    equivalence2.addExprTree(caseWhenExpr2)

    // `add1` is repeatedly in all branch values, and first predicate.
    assert(equivalence2.getAllEquivalentExprs.count(_.size == 2) == 1)
    assert(equivalence2.getAllEquivalentExprs.filter(_.size == 2).head == Seq(add1, add1))

    // Negative case. `add1` or `add2` is not commonly used in all predicates/branch values.
    val conditions3 = (GreaterThan(add1, Literal(3)), add2) ::
      (GreaterThan(add2, Literal(4)), add1) ::
      (GreaterThan(add2, Literal(5)), add1) :: Nil

    val caseWhenExpr3 = CaseWhen(conditions3, None)
    val equivalence3 = new EquivalentExpressions
    equivalence3.addExprTree(caseWhenExpr3)
    assert(equivalence3.getAllEquivalentExprs.count(_.size == 2) == 0)
  }

  test("Children of conditional expressions: Coalesce") {
    val add1 = Add(Literal(1), Literal(2))
    val add2 = Add(Literal(2), Literal(3))
    val conditions1 = GreaterThan(add2, Literal(3)) ::
      GreaterThan(add2, Literal(4)) ::
      GreaterThan(add2, Literal(5)) :: Nil

    val coalesceExpr1 = Coalesce(conditions1)
    val equivalence1 = new EquivalentExpressions
    equivalence1.addExprTree(coalesceExpr1)

    // `add2` is repeatedly in all conditions.
    assert(equivalence1.getAllEquivalentExprs.count(_.size == 2) == 1)
    assert(equivalence1.getAllEquivalentExprs.filter(_.size == 2).head == Seq(add2, add2))

    // Negative case. `add1` and `add2` both are not used in all branches.
    val conditions2 = GreaterThan(add1, Literal(3)) ::
      GreaterThan(add2, Literal(4)) ::
      GreaterThan(add2, Literal(5)) :: Nil

    val coalesceExpr2 = Coalesce(conditions2)
    val equivalence2 = new EquivalentExpressions
    equivalence2.addExprTree(coalesceExpr2)

    assert(equivalence2.getAllEquivalentExprs.count(_.size == 2) == 0)
  }

  test("SPARK-34723: Correct parameter type for subexpression elimination under whole-stage") {
    withSQLConf(SQLConf.CODEGEN_METHOD_SPLIT_THRESHOLD.key -> "1") {
      val str = BoundReference(0, BinaryType, false)
      val pos = BoundReference(1, IntegerType, false)

      val substr = new Substring(str, pos)

      val add = Add(Length(substr), Literal(1))
      val add2 = Add(Length(substr), Literal(2))

      val ctx = new CodegenContext()
      val exprs = Seq(add, add2)

      val oneVar = ctx.freshVariable("str", BinaryType)
      val twoVar = ctx.freshVariable("pos", IntegerType)
      ctx.addMutableState("byte[]", oneVar, forceInline = true, useFreshName = false)
      ctx.addMutableState("int", twoVar, useFreshName = false)

      ctx.INPUT_ROW = null
      ctx.currentVars = Seq(
        ExprCode(TrueLiteral, oneVar),
        ExprCode(TrueLiteral, twoVar))

      val subExprs = ctx.subexpressionEliminationForWholeStageCodegen(exprs)
      ctx.withSubExprEliminationExprs(subExprs.states) {
        exprs.map(_.genCode(ctx))
      }
      val subExprsCode = subExprs.codes.mkString("\n")

      val codeBody = s"""
        public java.lang.Object generate(Object[] references) {
          return new TestCode(references);
        }

        class TestCode {
          ${ctx.declareMutableStates()}

          public TestCode(Object[] references) {
          }

          public void initialize(int partitionIndex) {
            ${subExprsCode}
          }

          ${ctx.declareAddedFunctions()}
        }
      """

      val code = CodeFormatter.stripOverlappingComments(
        new CodeAndComment(codeBody, ctx.getPlaceHolderToComments()))

      CodeGenerator.compile(code)
    }
  }
}

case class CodegenFallbackExpression(child: Expression)
  extends UnaryExpression with CodegenFallback {
  override def dataType: DataType = child.dataType
}
