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

package org.apache.spark.sql.catalyst.optimizer

import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.Literal.FalseLiteral
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.types._

/**
 * Unwrap casts in binary comparison operations with patterns like following:
 *
 * `BinaryComparison(Cast(fromExp, toType), Literal(value, toType))`
 *   or
 * `BinaryComparison(Literal(value, toType), Cast(fromExp, toType))`
 *
 * This rule optimizes expressions with the above pattern by either replacing the cast with simpler
 * constructs, or moving the cast from the expression side to the literal side, which enables them
 * to be optimized away later and pushed down to data sources.
 *
 * Currently this only handles cases where:
 *   1). `fromType` (of `fromExp`) and `toType` are of numeric types (i.e., short, int, float,
 *     decimal, etc)
 *   2). `fromType` can be safely coerced to `toType` without precision loss (e.g., short to int,
 *     int to long, but not long to int)
 *
 * If the above conditions are satisfied, the rule checks to see if the literal `value` is within
 * range `(min, max)`, where `min` and `max` are the minimum and maximum value of `fromType`,
 * respectively. If this is true then it means we may safely cast `value` to `fromType` and thus
 * able to move the cast to the literal side. That is:
 *
 *   `cast(fromExp, toType) op value` ==> `fromExp op cast(value, fromType)`
 *
 * Note there are some exceptions to the above: if casting from `value` to `fromType` causes
 * rounding up or down, the above conversion will no longer be valid. Instead, the rule does the
 * following:
 *
 * if casting `value` to `fromType` causes rounding up:
 *  - `cast(fromExp, toType) > value` ==> `fromExp >= cast(value, fromType)`
 *  - `cast(fromExp, toType) >= value` ==> `fromExp >= cast(value, fromType)`
 *  - `cast(fromExp, toType) === value` ==> if(isnull(fromExp), null, false)
 *  - `cast(fromExp, toType) <=> value` ==> false (if `fromExp` is deterministic)
 *  - `cast(fromExp, toType) <= value` ==> `fromExp < cast(value, fromType)`
 *  - `cast(fromExp, toType) < value` ==> `fromExp < cast(value, fromType)`
 *
 * Similarly for the case when casting `value` to `fromType` causes rounding down.
 *
 * If the `value` is not within range `(min, max)`, the rule breaks the scenario into different
 * cases and try to replace each with simpler constructs.
 *
 * if `value > max`, the cases are of following:
 *  - `cast(fromExp, toType) > value` ==> if(isnull(fromExp), null, false)
 *  - `cast(fromExp, toType) >= value` ==> if(isnull(fromExp), null, false)
 *  - `cast(fromExp, toType) === value` ==> if(isnull(fromExp), null, false)
 *  - `cast(fromExp, toType) <=> value` ==> false (if `fromExp` is deterministic)
 *  - `cast(fromExp, toType) <= value` ==> if(isnull(fromExp), null, true)
 *  - `cast(fromExp, toType) < value` ==> if(isnull(fromExp), null, true)
 *
 * if `value == max`, the cases are of following:
 *  - `cast(fromExp, toType) > value` ==> if(isnull(fromExp), null, false)
 *  - `cast(fromExp, toType) >= value` ==> fromExp == max
 *  - `cast(fromExp, toType) === value` ==> fromExp == max
 *  - `cast(fromExp, toType) <=> value` ==> fromExp <=> max
 *  - `cast(fromExp, toType) <= value` ==> if(isnull(fromExp), null, true)
 *  - `cast(fromExp, toType) < value` ==> fromExp =!= max
 *
 * Similarly for the cases when `value == min` and `value < min`.
 *
 * Further, the above `if(isnull(fromExp), null, false)` is represented using conjunction
 * `and(isnull(fromExp), null)`, to enable further optimization and filter pushdown to data sources.
 * Similarly, `if(isnull(fromExp), null, true)` is represented with `or(isnotnull(fromExp), null)`.
 */
object UnwrapCastInBinaryComparison extends Rule[LogicalPlan] {
  override def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case l: LogicalPlan =>
      l transformExpressionsUp {
        case e @ BinaryComparison(_, _) => unwrapCast(e)
      }
  }

  private def unwrapCast(exp: Expression): Expression = exp match {
    // Not a canonical form. In this case we first canonicalize the expression by swapping the
    // literal and cast side, then process the result and swap the literal and cast again to
    // restore the original order.
    case BinaryComparison(Literal(_, literalType), Cast(fromExp, toType, _))
        if canImplicitlyCast(fromExp, toType, literalType) =>
      def swap(e: Expression): Expression = e match {
        case GreaterThan(left, right) => LessThan(right, left)
        case GreaterThanOrEqual(left, right) => LessThanOrEqual(right, left)
        case EqualTo(left, right) => EqualTo(right, left)
        case EqualNullSafe(left, right) => EqualNullSafe(right, left)
        case LessThanOrEqual(left, right) => GreaterThanOrEqual(right, left)
        case LessThan(left, right) => GreaterThan(right, left)
        case _ => e
      }

      swap(unwrapCast(swap(exp)))

    // In case both sides have numeric type, optimize the comparison by removing casts or
    // moving cast to the literal side.
    case be @ BinaryComparison(
      Cast(fromExp, toType: NumericType, _), Literal(value, literalType))
        if canImplicitlyCast(fromExp, toType, literalType) =>
      simplifyNumericComparison(be, fromExp, toType, value)

    case _ => exp
  }

  /**
   * Check if the input `value` is within range `(min, max)` of the `fromType`, where `min` and
   * `max` are the minimum and maximum value of the `fromType`. If the above is true, this
   * optimizes the expression by moving the cast to the literal side. Otherwise if result is not
   * true, this replaces the input binary comparison `exp` with simpler expressions.
   */
  private def simplifyNumericComparison(
      exp: BinaryComparison,
      fromExp: Expression,
      toType: NumericType,
      value: Any): Expression = {

    val fromType = fromExp.dataType
    val ordering = toType.ordering.asInstanceOf[Ordering[Any]]
    val range = getRange(fromType)

    if (range.isDefined) {
      val (min, max) = range.get
      val (minInToType, maxInToType) = {
        (Cast(Literal(min), toType).eval(), Cast(Literal(max), toType).eval())
      }
      val minCmp = ordering.compare(value, minInToType)
      val maxCmp = ordering.compare(value, maxInToType)

      if (maxCmp >= 0 || minCmp <= 0) {
        return if (maxCmp > 0) {
          exp match {
            case EqualTo(_, _) | GreaterThan(_, _) | GreaterThanOrEqual(_, _) =>
              falseIfNotNull(fromExp)
            case LessThan(_, _) | LessThanOrEqual(_, _) =>
              trueIfNotNull(fromExp)
            // make sure the expression is evaluated if it is non-deterministic
            case EqualNullSafe(_, _) if exp.deterministic =>
              FalseLiteral
            case _ => exp
          }
        } else if (maxCmp == 0) {
          exp match {
            case GreaterThan(_, _) =>
              falseIfNotNull(fromExp)
            case LessThanOrEqual(_, _) =>
              trueIfNotNull(fromExp)
            case LessThan(_, _) =>
              Not(EqualTo(fromExp, Literal(max, fromType)))
            case GreaterThanOrEqual(_, _) | EqualTo(_, _) =>
              EqualTo(fromExp, Literal(max, fromType))
            case EqualNullSafe(_, _) =>
              EqualNullSafe(fromExp, Literal(max, fromType))
            case _ => exp
          }
        } else if (minCmp < 0) {
          exp match {
            case GreaterThan(_, _) | GreaterThanOrEqual(_, _) =>
              trueIfNotNull(fromExp)
            case LessThan(_, _) | LessThanOrEqual(_, _) | EqualTo(_, _) =>
              falseIfNotNull(fromExp)
            // make sure the expression is evaluated if it is non-deterministic
            case EqualNullSafe(_, _) if exp.deterministic =>
              FalseLiteral
            case _ => exp
          }
        } else { // minCmp == 0
          exp match {
            case LessThan(_, _) =>
              falseIfNotNull(fromExp)
            case GreaterThanOrEqual(_, _) =>
              trueIfNotNull(fromExp)
            case GreaterThan(_, _) =>
              Not(EqualTo(fromExp, Literal(min, fromType)))
            case LessThanOrEqual(_, _) | EqualTo(_, _) =>
              EqualTo(fromExp, Literal(min, fromType))
            case EqualNullSafe(_, _) =>
              EqualNullSafe(fromExp, Literal(min, fromType))
            case _ => exp
          }
        }
      }
    }

    // When we reach to this point, it means either there is no min/max for the `fromType` (e.g.,
    // decimal type), or that the literal `value` is within range `(min, max)`. For these, we
    // optimize by moving the cast to the literal side.

    val newValue = Cast(Literal(value), fromType).eval()
    if (newValue == null) {
      // This means the cast failed, for instance, due to the value is not representable in the
      // narrower type. In this case we simply return the original expression.
      return exp
    }
    val valueRoundTrip = Cast(Literal(newValue, fromType), toType).eval()
    val lit = Literal(newValue, fromType)
    val cmp = ordering.compare(value, valueRoundTrip)
    if (cmp == 0) {
      exp match {
        case GreaterThan(_, _) => GreaterThan(fromExp, lit)
        case GreaterThanOrEqual(_, _) => GreaterThanOrEqual(fromExp, lit)
        case EqualTo(_, _) => EqualTo(fromExp, lit)
        case EqualNullSafe(_, _) => EqualNullSafe(fromExp, lit)
        case LessThan(_, _) => LessThan(fromExp, lit)
        case LessThanOrEqual(_, _) => LessThanOrEqual(fromExp, lit)
        case _ => exp
      }
    } else if (cmp < 0) {
      // This means the literal value is rounded up after casting to `fromType`
      exp match {
        case EqualTo(_, _) => falseIfNotNull(fromExp)
        case EqualNullSafe(_, _) if fromExp.deterministic => FalseLiteral
        case GreaterThan(_, _) | GreaterThanOrEqual(_, _) => GreaterThanOrEqual(fromExp, lit)
        case LessThan(_, _) | LessThanOrEqual(_, _) => LessThan(fromExp, lit)
        case _ => exp
      }
    } else {
      // This means the literal value is rounded down after casting to `fromType`
      exp match {
        case EqualTo(_, _) => falseIfNotNull(fromExp)
        case EqualNullSafe(_, _) => FalseLiteral
        case GreaterThan(_, _) | GreaterThanOrEqual(_, _) => GreaterThan(fromExp, lit)
        case LessThan(_, _) | LessThanOrEqual(_, _) => LessThanOrEqual(fromExp, lit)
        case _ => exp
      }
    }
  }

  /**
   * Check if the input `fromExp` can be safely cast to `toType` without any loss of precision,
   * i.e., the conversion is injective. Note this only handles the case when both sides are of
   * numeric type.
   */
  private def canImplicitlyCast(
      fromExp: Expression,
      toType: DataType,
      literalType: DataType): Boolean = {
    toType.sameType(literalType) &&
      !fromExp.foldable &&
      fromExp.dataType.isInstanceOf[NumericType] &&
      toType.isInstanceOf[NumericType] &&
      Cast.canUpCast(fromExp.dataType, toType)
  }

  private[optimizer] def getRange(dt: DataType): Option[(Any, Any)] = dt match {
    case ByteType => Some((Byte.MinValue, Byte.MaxValue))
    case ShortType => Some((Short.MinValue, Short.MaxValue))
    case IntegerType => Some((Int.MinValue, Int.MaxValue))
    case LongType => Some((Long.MinValue, Long.MaxValue))
    case FloatType => Some((Float.NegativeInfinity, Float.NaN))
    case DoubleType => Some((Double.NegativeInfinity, Double.NaN))
    case _ => None
  }

  /**
   * Wraps input expression `e` with `if(isnull(e), null, false)`. The if-clause is represented
   * using `and(isnull(e), null)` which is semantically equivalent by applying 3-valued logic.
   */
  private[optimizer] def falseIfNotNull(e: Expression): Expression = {
    And(IsNull(e), Literal(null, BooleanType))
  }

  /**
   * Wraps input expression `e` with `if(isnull(e), null, true)`. The if-clause is represented
   * using `or(isnotnull(e), null)` which is semantically equivalent by applying 3-valued logic.
   */
  private[optimizer] def trueIfNotNull(e: Expression): Expression = {
    Or(IsNotNull(e), Literal(null, BooleanType))
  }
}
