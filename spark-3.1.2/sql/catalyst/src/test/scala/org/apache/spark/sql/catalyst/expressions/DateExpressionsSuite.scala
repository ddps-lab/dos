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

import java.sql.{Date, Timestamp}
import java.text.{ParseException, SimpleDateFormat}
import java.time.{DateTimeException, Instant, LocalDate, ZoneId}
import java.time.format.DateTimeParseException
import java.util.{Calendar, Locale, TimeZone}
import java.util.concurrent.TimeUnit._

import scala.reflect.ClassTag

import org.apache.spark.{SparkFunSuite, SparkUpgradeException}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.codegen.GenerateUnsafeProjection
import org.apache.spark.sql.catalyst.util.{DateTimeUtils, IntervalUtils, TimestampFormatter}
import org.apache.spark.sql.catalyst.util.DateTimeConstants._
import org.apache.spark.sql.catalyst.util.DateTimeTestUtils._
import org.apache.spark.sql.catalyst.util.DateTimeUtils.TimeZoneUTC
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.{CalendarInterval, UTF8String}

class DateExpressionsSuite extends SparkFunSuite with ExpressionEvalHelper {

  import IntegralLiteralTestUtils._

  private val PST_OPT = Option(PST.getId)
  private val JST_OPT = Option(JST.getId)

  def toMillis(timestamp: String): Long = {
    val tf = TimestampFormatter("yyyy-MM-dd HH:mm:ss", UTC, isParsing = true)
    DateTimeUtils.microsToMillis(tf.parse(timestamp))
  }
  val date = "2015-04-08 13:10:15"
  val d = new Date(toMillis(date))
  val time = "2013-11-08 13:10:15"
  val ts = new Timestamp(toMillis(time))

  test("datetime function current_date") {
    val d0 = DateTimeUtils.currentDate(UTC)
    val cd = CurrentDate(UTC_OPT).eval(EmptyRow).asInstanceOf[Int]
    val d1 = DateTimeUtils.currentDate(UTC)
    assert(d0 <= cd && cd <= d1 && d1 - d0 <= 1)

    val cdjst = CurrentDate(JST_OPT).eval(EmptyRow).asInstanceOf[Int]
    val cdpst = CurrentDate(PST_OPT).eval(EmptyRow).asInstanceOf[Int]
    assert(cdpst <= cd && cd <= cdjst)
  }

  test("datetime function current_timestamp") {
    val ct = DateTimeUtils.toJavaTimestamp(CurrentTimestamp().eval(EmptyRow).asInstanceOf[Long])
    val t1 = System.currentTimeMillis()
    assert(math.abs(t1 - ct.getTime) < 5000)
  }

  test("DayOfYear") {
    val sdfDay = new SimpleDateFormat("D", Locale.US)

    val c = Calendar.getInstance()
    (0 to 3).foreach { m =>
      (0 to 5).foreach { i =>
        c.set(2000, m, 28, 0, 0, 0)
        c.add(Calendar.DATE, i)
        checkEvaluation(DayOfYear(Literal(new Date(c.getTimeInMillis))),
          sdfDay.format(c.getTime).toInt)
      }
    }
    checkEvaluation(DayOfYear(Literal.create(null, DateType)), null)

    checkEvaluation(DayOfYear(Cast(Literal("1582-10-15 13:10:15"), DateType)), 288)
    checkEvaluation(DayOfYear(Cast(Literal("1582-10-04 13:10:15"), DateType)), 277)
    checkConsistencyBetweenInterpretedAndCodegen(DayOfYear, DateType)
  }

  test("Year") {
    checkEvaluation(Year(Literal.create(null, DateType)), null)
    checkEvaluation(Year(Literal(d)), 2015)
    checkEvaluation(Year(Cast(Literal(date), DateType, UTC_OPT)), 2015)
    checkEvaluation(Year(Cast(Literal(ts), DateType, UTC_OPT)), 2013)

    val c = Calendar.getInstance()
    (2000 to 2002).foreach { y =>
      (0 to 11 by 11).foreach { m =>
        c.set(y, m, 28)
        (0 to 5 * 24).foreach { i =>
          c.add(Calendar.HOUR_OF_DAY, 1)
          checkEvaluation(Year(Literal(new Date(c.getTimeInMillis))),
            c.get(Calendar.YEAR))
        }
      }
    }
    checkEvaluation(Year(Cast(Literal("1582-01-01 13:10:15"), DateType)), 1582)
    checkEvaluation(Year(Cast(Literal("1581-12-31 13:10:15"), DateType)), 1581)
    checkConsistencyBetweenInterpretedAndCodegen(Year, DateType)
  }

  test("Quarter") {
    checkEvaluation(Quarter(Literal.create(null, DateType)), null)
    checkEvaluation(Quarter(Literal(d)), 2)
    checkEvaluation(Quarter(Cast(Literal(date), DateType, UTC_OPT)), 2)
    checkEvaluation(Quarter(Cast(Literal(ts), DateType, UTC_OPT)), 4)

    val c = Calendar.getInstance()
    (2003 to 2004).foreach { y =>
      (0 to 11 by 3).foreach { m =>
        c.set(y, m, 28, 0, 0, 0)
        (0 to 5 * 24).foreach { i =>
          c.add(Calendar.HOUR_OF_DAY, 1)
          checkEvaluation(Quarter(Literal(new Date(c.getTimeInMillis))),
            c.get(Calendar.MONTH) / 3 + 1)
        }
      }
    }

    checkEvaluation(Quarter(Cast(Literal("1582-10-01 13:10:15"), DateType)), 4)
    checkEvaluation(Quarter(Cast(Literal("1582-09-30 13:10:15"), DateType)), 3)
    checkConsistencyBetweenInterpretedAndCodegen(Quarter, DateType)
  }

  test("Month") {
    checkEvaluation(Month(Literal.create(null, DateType)), null)
    checkEvaluation(Month(Literal(d)), 4)
    checkEvaluation(Month(Cast(Literal(date), DateType, UTC_OPT)), 4)
    checkEvaluation(Month(Cast(Literal(ts), DateType, UTC_OPT)), 11)

    checkEvaluation(Month(Cast(Literal("1582-04-28 13:10:15"), DateType)), 4)
    checkEvaluation(Month(Cast(Literal("1582-10-04 13:10:15"), DateType)), 10)
    checkEvaluation(Month(Cast(Literal("1582-10-15 13:10:15"), DateType)), 10)

    val c = Calendar.getInstance()
    (2003 to 2004).foreach { y =>
      (0 to 3).foreach { m =>
        (0 to 2 * 24).foreach { i =>
          c.set(y, m, 28, 0, 0, 0)
          c.add(Calendar.HOUR_OF_DAY, i)
          checkEvaluation(Month(Literal(new Date(c.getTimeInMillis))),
            c.get(Calendar.MONTH) + 1)
        }
      }
    }
    checkConsistencyBetweenInterpretedAndCodegen(Month, DateType)
  }

  test("Day / DayOfMonth") {
    checkEvaluation(DayOfMonth(Cast(Literal("2000-02-29"), DateType)), 29)
    checkEvaluation(DayOfMonth(Literal.create(null, DateType)), null)
    checkEvaluation(DayOfMonth(Literal(d)), 8)
    checkEvaluation(DayOfMonth(Cast(Literal(date), DateType, UTC_OPT)), 8)
    checkEvaluation(DayOfMonth(Cast(Literal(ts), DateType, UTC_OPT)), 8)

    checkEvaluation(DayOfMonth(Cast(Literal("1582-04-28 13:10:15"), DateType)), 28)
    checkEvaluation(DayOfMonth(Cast(Literal("1582-10-15 13:10:15"), DateType)), 15)
    checkEvaluation(DayOfMonth(Cast(Literal("1582-10-04 13:10:15"), DateType)), 4)

    val c = Calendar.getInstance()
    (1999 to 2000).foreach { y =>
      c.set(y, 0, 1, 0, 0, 0)
      (0 to 365).foreach { d =>
        c.add(Calendar.DATE, 1)
        checkEvaluation(DayOfMonth(Literal(new Date(c.getTimeInMillis))),
          c.get(Calendar.DAY_OF_MONTH))
      }
    }
    checkConsistencyBetweenInterpretedAndCodegen(DayOfMonth, DateType)
  }

  test("Seconds") {
    assert(Second(Literal.create(null, DateType), UTC_OPT).resolved === false)
    assert(Second(Cast(Literal(d), TimestampType, UTC_OPT), UTC_OPT).resolved )
    checkEvaluation(Second(Cast(Literal(d), TimestampType, UTC_OPT), UTC_OPT), 0)
    checkEvaluation(Second(Cast(Literal(date), TimestampType, UTC_OPT), UTC_OPT), 15)
    checkEvaluation(Second(Literal(ts), UTC_OPT), 15)

    val c = Calendar.getInstance()
    for (zid <- outstandingZoneIds) {
      val timeZoneId = Option(zid.getId)
      c.setTimeZone(TimeZone.getTimeZone(zid))
      (0 to 60 by 5).foreach { s =>
        c.set(2015, 18, 3, 3, 5, s)
        checkEvaluation(
          Second(Literal(new Timestamp(c.getTimeInMillis)), timeZoneId),
          c.get(Calendar.SECOND))
      }
      checkConsistencyBetweenInterpretedAndCodegen(
        (child: Expression) => Second(child, timeZoneId), TimestampType)
    }
  }

  test("DayOfWeek") {
    checkEvaluation(DayOfWeek(Literal.create(null, DateType)), null)
    checkEvaluation(DayOfWeek(Literal(d)), Calendar.WEDNESDAY)
    checkEvaluation(DayOfWeek(Cast(Literal(date), DateType, UTC_OPT)),
      Calendar.WEDNESDAY)
    checkEvaluation(DayOfWeek(Cast(Literal(ts), DateType, UTC_OPT)), Calendar.FRIDAY)
    checkEvaluation(DayOfWeek(Cast(Literal("2011-05-06"), DateType, UTC_OPT)), Calendar.FRIDAY)
    checkEvaluation(DayOfWeek(Literal(new Date(toMillis("2017-05-27 13:10:15")))),
      Calendar.SATURDAY)
    checkEvaluation(DayOfWeek(Literal(new Date(toMillis("1582-10-15 13:10:15")))),
      Calendar.FRIDAY)
    checkConsistencyBetweenInterpretedAndCodegen(DayOfWeek, DateType)
  }

  test("WeekDay") {
    checkEvaluation(WeekDay(Literal.create(null, DateType)), null)
    checkEvaluation(WeekDay(Literal(d)), 2)
    checkEvaluation(WeekDay(Cast(Literal(date), DateType, UTC_OPT)), 2)
    checkEvaluation(WeekDay(Cast(Literal(ts), DateType, UTC_OPT)), 4)
    checkEvaluation(WeekDay(Cast(Literal("2011-05-06"), DateType, UTC_OPT)), 4)
    checkEvaluation(WeekDay(Literal(new Date(toMillis("2017-05-27 13:10:15")))), 5)
    checkEvaluation(WeekDay(Literal(new Date(toMillis("1582-10-15 13:10:15")))), 4)
    checkConsistencyBetweenInterpretedAndCodegen(WeekDay, DateType)
  }

  test("WeekOfYear") {
    checkEvaluation(WeekOfYear(Literal.create(null, DateType)), null)
    checkEvaluation(WeekOfYear(Literal(d)), 15)
    checkEvaluation(WeekOfYear(Cast(Literal(date), DateType, UTC_OPT)), 15)
    checkEvaluation(WeekOfYear(Cast(Literal(ts), DateType, UTC_OPT)), 45)
    checkEvaluation(WeekOfYear(Cast(Literal("2011-05-06"), DateType, UTC_OPT)), 18)
    checkEvaluation(WeekOfYear(Cast(Literal("1582-10-15 13:10:15"), DateType, UTC_OPT)), 41)
    checkEvaluation(WeekOfYear(Cast(Literal("1582-10-04 13:10:15"), DateType, UTC_OPT)), 40)
    checkConsistencyBetweenInterpretedAndCodegen(WeekOfYear, DateType)
  }

  test("DateFormat") {
    Seq("legacy", "corrected").foreach { legacyParserPolicy =>
      withSQLConf(SQLConf.LEGACY_TIME_PARSER_POLICY.key -> legacyParserPolicy) {
        checkEvaluation(
          DateFormatClass(Literal.create(null, TimestampType), Literal("y"), UTC_OPT),
          null)
        checkEvaluation(DateFormatClass(Cast(Literal(d), TimestampType, UTC_OPT),
          Literal.create(null, StringType), UTC_OPT), null)

        checkEvaluation(DateFormatClass(Cast(Literal(d), TimestampType, UTC_OPT),
          Literal("y"), UTC_OPT), "2015")
        checkEvaluation(DateFormatClass(Literal(ts), Literal("y"), UTC_OPT), "2013")
        checkEvaluation(DateFormatClass(Cast(Literal(d), TimestampType, UTC_OPT),
          Literal("H"), UTC_OPT), "0")
        checkEvaluation(DateFormatClass(Literal(ts), Literal("H"), UTC_OPT), "13")

        checkEvaluation(DateFormatClass(Cast(Literal(d), TimestampType, PST_OPT),
          Literal("y"), PST_OPT), "2015")
        checkEvaluation(DateFormatClass(Literal(ts), Literal("y"), PST_OPT), "2013")
        checkEvaluation(DateFormatClass(Cast(Literal(d), TimestampType, PST_OPT),
          Literal("H"), PST_OPT), "0")
        checkEvaluation(DateFormatClass(Literal(ts), Literal("H"), PST_OPT), "5")

        checkEvaluation(DateFormatClass(Cast(Literal(d), TimestampType, JST_OPT),
          Literal("y"), JST_OPT), "2015")
        checkEvaluation(DateFormatClass(Literal(ts), Literal("y"), JST_OPT), "2013")
        checkEvaluation(DateFormatClass(Cast(Literal(d), TimestampType, JST_OPT),
          Literal("H"), JST_OPT), "0")
        checkEvaluation(DateFormatClass(Literal(ts), Literal("H"), JST_OPT), "22")

        // Test escaping of format
        GenerateUnsafeProjection.generate(
          DateFormatClass(Literal(ts), Literal("\""), JST_OPT) :: Nil)

        // SPARK-28072 The codegen path should work
        checkEvaluation(
          expression = DateFormatClass(
            BoundReference(ordinal = 0, dataType = TimestampType, nullable = true),
            BoundReference(ordinal = 1, dataType = StringType, nullable = true),
            JST_OPT),
          expected = "22",
          inputRow = InternalRow(DateTimeUtils.fromJavaTimestamp(ts), UTF8String.fromString("H")))
      }
    }
  }

  test("Hour") {
    assert(Hour(Literal.create(null, DateType), UTC_OPT).resolved === false)
    assert(Hour(Literal(ts), UTC_OPT).resolved)
    checkEvaluation(Hour(Cast(Literal(d), TimestampType, UTC_OPT), UTC_OPT), 0)
    checkEvaluation(Hour(Cast(Literal(date), TimestampType, UTC_OPT), UTC_OPT), 13)
    checkEvaluation(Hour(Literal(ts), UTC_OPT), 13)

    val c = Calendar.getInstance()
    for (zid <- outstandingZoneIds) {
      val timeZoneId = Option(zid.getId)
      c.setTimeZone(TimeZone.getTimeZone(zid))
      (0 to 24 by 6).foreach { h =>
        (0 to 60 by 30).foreach { m =>
          (0 to 60 by 30).foreach { s =>
            c.set(2015, 18, 3, h, m, s)
            checkEvaluation(
              Hour(Literal(new Timestamp(c.getTimeInMillis)), timeZoneId),
              c.get(Calendar.HOUR_OF_DAY))
          }
        }
      }
      checkConsistencyBetweenInterpretedAndCodegen(
        (child: Expression) => Hour(child, timeZoneId), TimestampType)
    }
  }

  test("Minute") {
    assert(Minute(Literal.create(null, DateType), UTC_OPT).resolved === false)
    assert(Minute(Literal(ts), UTC_OPT).resolved)
    checkEvaluation(Minute(Cast(Literal(d), TimestampType, UTC_OPT), UTC_OPT), 0)
    checkEvaluation(
      Minute(Cast(Literal(date), TimestampType, UTC_OPT), UTC_OPT), 10)
    checkEvaluation(Minute(Literal(ts), UTC_OPT), 10)

    val c = Calendar.getInstance()
    for (zid <- outstandingZoneIds) {
      val timeZoneId = Option(zid.getId)
      c.setTimeZone(TimeZone.getTimeZone(zid))
      (0 to 60 by 5).foreach { m =>
        (0 to 60 by 15).foreach { s =>
          c.set(2015, 18, 3, 3, m, s)
          checkEvaluation(
            Minute(Literal(new Timestamp(c.getTimeInMillis)), timeZoneId),
            c.get(Calendar.MINUTE))
        }
      }
      checkConsistencyBetweenInterpretedAndCodegen(
        (child: Expression) => Minute(child, timeZoneId), TimestampType)
    }
  }

  test("date_add") {
    checkEvaluation(
      DateAdd(Literal(Date.valueOf("2016-02-28")), Literal(1.toByte)),
      DateTimeUtils.fromJavaDate(Date.valueOf("2016-02-29")))
    checkEvaluation(
      DateAdd(Literal(Date.valueOf("2016-02-28")), Literal(1.toShort)),
      DateTimeUtils.fromJavaDate(Date.valueOf("2016-02-29")))
    checkEvaluation(
      DateAdd(Literal(Date.valueOf("2016-02-28")), Literal(1)),
      DateTimeUtils.fromJavaDate(Date.valueOf("2016-02-29")))
    checkEvaluation(
      DateAdd(Literal(Date.valueOf("2016-02-28")), Literal(-365)),
      DateTimeUtils.fromJavaDate(Date.valueOf("2015-02-28")))
    checkEvaluation(DateAdd(Literal.create(null, DateType), Literal(1)), null)
    checkEvaluation(DateAdd(Literal(Date.valueOf("2016-02-28")), Literal.create(null, IntegerType)),
      null)
    checkEvaluation(DateAdd(Literal.create(null, DateType), Literal.create(null, IntegerType)),
      null)
    checkEvaluation(
      DateAdd(Literal(Date.valueOf("2016-02-28")), positiveIntLit), 49627)
    checkEvaluation(
      DateAdd(Literal(Date.valueOf("2016-02-28")), negativeIntLit), -15910)
    checkConsistencyBetweenInterpretedAndCodegen(DateAdd, DateType, ByteType)
    checkConsistencyBetweenInterpretedAndCodegen(DateAdd, DateType, ShortType)
    checkConsistencyBetweenInterpretedAndCodegen(DateAdd, DateType, IntegerType)
  }

  test("date add interval") {
    val d = Date.valueOf("2016-02-28")
    Seq("true", "false") foreach { flag =>
      withSQLConf((SQLConf.ANSI_ENABLED.key, flag)) {
        checkEvaluation(
          DateAddInterval(Literal(d), Literal(new CalendarInterval(0, 1, 0))),
          DateTimeUtils.fromJavaDate(Date.valueOf("2016-02-29")))
        checkEvaluation(
          DateAddInterval(Literal(d), Literal(new CalendarInterval(1, 1, 0))),
          DateTimeUtils.fromJavaDate(Date.valueOf("2016-03-29")))
        checkEvaluation(DateAddInterval(Literal(d), Literal.create(null, CalendarIntervalType)),
          null)
        checkEvaluation(DateAddInterval(Literal.create(null, DateType),
          Literal(new CalendarInterval(1, 1, 0))),
          null)
      }
    }

    withSQLConf((SQLConf.ANSI_ENABLED.key, "true")) {
      checkExceptionInExpression[IllegalArgumentException](
        DateAddInterval(Literal(d), Literal(new CalendarInterval(1, 1, 25 * MICROS_PER_HOUR))),
        "Cannot add hours, minutes or seconds, milliseconds, microseconds to a date")
    }

    withSQLConf((SQLConf.ANSI_ENABLED.key, "false")) {
      checkEvaluation(
        DateAddInterval(Literal(d), Literal(new CalendarInterval(1, 1, 25))),
        DateTimeUtils.fromJavaDate(Date.valueOf("2016-03-29")))
      checkEvaluation(
        DateAddInterval(Literal(d), Literal(new CalendarInterval(1, 1, 25 * MICROS_PER_HOUR))),
        DateTimeUtils.fromJavaDate(Date.valueOf("2016-03-30")))
    }
  }

  test("date_sub") {
    checkEvaluation(
      DateSub(Literal(Date.valueOf("2015-01-01")), Literal(1.toByte)),
      DateTimeUtils.fromJavaDate(Date.valueOf("2014-12-31")))
    checkEvaluation(
      DateSub(Literal(Date.valueOf("2015-01-01")), Literal(1.toShort)),
      DateTimeUtils.fromJavaDate(Date.valueOf("2014-12-31")))
    checkEvaluation(
      DateSub(Literal(Date.valueOf("2015-01-01")), Literal(1)),
      DateTimeUtils.fromJavaDate(Date.valueOf("2014-12-31")))
    checkEvaluation(
      DateSub(Literal(Date.valueOf("2015-01-01")), Literal(-1)),
      DateTimeUtils.fromJavaDate(Date.valueOf("2015-01-02")))
    checkEvaluation(DateSub(Literal.create(null, DateType), Literal(1)), null)
    checkEvaluation(DateSub(Literal(Date.valueOf("2016-02-28")), Literal.create(null, IntegerType)),
      null)
    checkEvaluation(DateSub(Literal.create(null, DateType), Literal.create(null, IntegerType)),
      null)
    checkEvaluation(
      DateSub(Literal(Date.valueOf("2016-02-28")), positiveIntLit), -15909)
    checkEvaluation(
      DateSub(Literal(Date.valueOf("2016-02-28")), negativeIntLit), 49628)
    checkConsistencyBetweenInterpretedAndCodegen(DateSub, DateType, ByteType)
    checkConsistencyBetweenInterpretedAndCodegen(DateSub, DateType, ShortType)
    checkConsistencyBetweenInterpretedAndCodegen(DateSub, DateType, IntegerType)
  }

  test("time_add") {
    val sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    for (zid <- outstandingZoneIds) {
      val timeZoneId = Option(zid.getId)
      sdf.setTimeZone(TimeZone.getTimeZone(zid))

      checkEvaluation(
        TimeAdd(
          Literal(new Timestamp(sdf.parse("2016-01-29 10:00:00.000").getTime)),
          Literal(new CalendarInterval(1, 2, 123000L)),
          timeZoneId),
        DateTimeUtils.fromJavaTimestamp(
          new Timestamp(sdf.parse("2016-03-02 10:00:00.123").getTime)))

      checkEvaluation(
        TimeAdd(
          Literal.create(null, TimestampType),
          Literal(new CalendarInterval(1, 2, 123000L)),
          timeZoneId),
        null)
      checkEvaluation(
        TimeAdd(
          Literal(new Timestamp(sdf.parse("2016-01-29 10:00:00.000").getTime)),
          Literal.create(null, CalendarIntervalType),
          timeZoneId),
        null)
      checkEvaluation(
        TimeAdd(
          Literal.create(null, TimestampType),
          Literal.create(null, CalendarIntervalType),
          timeZoneId),
        null)
      checkConsistencyBetweenInterpretedAndCodegen(
        (start: Expression, interval: Expression) => TimeAdd(start, interval, timeZoneId),
        TimestampType, CalendarIntervalType)
    }
  }

  test("time_sub") {
    val sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    for (zid <- outstandingZoneIds) {
      val timeZoneId = Option(zid.getId)
      sdf.setTimeZone(TimeZone.getTimeZone(zid))

      checkEvaluation(
        TimeAdd(
          Literal(new Timestamp(sdf.parse("2016-03-31 10:00:00.000").getTime)),
          UnaryMinus(Literal(new CalendarInterval(1, 0, 0))),
          timeZoneId),
        DateTimeUtils.fromJavaTimestamp(
          new Timestamp(sdf.parse("2016-02-29 10:00:00.000").getTime)))
      checkEvaluation(
        TimeAdd(
          Literal(new Timestamp(sdf.parse("2016-03-31 10:00:00.000").getTime)),
          UnaryMinus(Literal(new CalendarInterval(1, 1, 0))),
          timeZoneId),
        DateTimeUtils.fromJavaTimestamp(
          new Timestamp(sdf.parse("2016-02-28 10:00:00.000").getTime)))
      checkEvaluation(
        TimeAdd(
          Literal(new Timestamp(sdf.parse("2016-03-30 00:00:01.000").getTime)),
          UnaryMinus(Literal(new CalendarInterval(1, 0, 2000000.toLong))),
          timeZoneId),
        DateTimeUtils.fromJavaTimestamp(
          new Timestamp(sdf.parse("2016-02-28 23:59:59.000").getTime)))
      checkEvaluation(
        TimeAdd(
          Literal(new Timestamp(sdf.parse("2016-03-30 00:00:01.000").getTime)),
          UnaryMinus(Literal(new CalendarInterval(1, 1, 2000000.toLong))),
          timeZoneId),
        DateTimeUtils.fromJavaTimestamp(
          new Timestamp(sdf.parse("2016-02-27 23:59:59.000").getTime)))

      checkEvaluation(
        TimeAdd(
          Literal.create(null, TimestampType),
          UnaryMinus(Literal(new CalendarInterval(1, 2, 123000L))),
          timeZoneId),
        null)
      checkEvaluation(
        TimeAdd(
          Literal(new Timestamp(sdf.parse("2016-01-29 10:00:00.000").getTime)),
          UnaryMinus(Literal.create(null, CalendarIntervalType)),
          timeZoneId),
        null)
      checkEvaluation(
        TimeAdd(
          Literal.create(null, TimestampType),
          UnaryMinus(Literal.create(null, CalendarIntervalType)),
          timeZoneId),
        null)
      checkConsistencyBetweenInterpretedAndCodegen((start: Expression, interval: Expression) =>
        TimeAdd(start, UnaryMinus(interval), timeZoneId),
        TimestampType, CalendarIntervalType)
    }
  }

  test("add_months") {
    checkEvaluation(AddMonths(Literal(Date.valueOf("2015-01-30")), Literal(1)),
      DateTimeUtils.fromJavaDate(Date.valueOf("2015-02-28")))
    checkEvaluation(AddMonths(Literal(Date.valueOf("2016-03-30")), Literal(-1)),
      DateTimeUtils.fromJavaDate(Date.valueOf("2016-02-29")))
    checkEvaluation(
      AddMonths(Literal(Date.valueOf("2015-01-30")), Literal.create(null, IntegerType)),
      null)
    checkEvaluation(AddMonths(Literal.create(null, DateType), Literal(1)), null)
    checkEvaluation(AddMonths(Literal.create(null, DateType), Literal.create(null, IntegerType)),
      null)
    // Valid range of DateType is [0001-01-01, 9999-12-31]
    val maxMonthInterval = 10000 * 12
    checkEvaluation(
      AddMonths(Literal(LocalDate.parse("0001-01-01")), Literal(maxMonthInterval)),
      LocalDate.of(10001, 1, 1).toEpochDay.toInt)
    checkEvaluation(
      AddMonths(Literal(Date.valueOf("9999-12-31")), Literal(-1 * maxMonthInterval)), -719529)
    // Test evaluation results between Interpreted mode and Codegen mode
    forAll (
      LiteralGenerator.randomGen(DateType),
      LiteralGenerator.monthIntervalLiterGen
    ) { (l1: Literal, l2: Literal) =>
      cmpInterpretWithCodegen(EmptyRow, AddMonths(l1, l2))
    }
  }

  test("months_between") {
    val sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    for (zid <- outstandingZoneIds) {
      val timeZoneId = Option(zid.getId)
      sdf.setTimeZone(TimeZone.getTimeZone(zid))

      checkEvaluation(
        MonthsBetween(
          Literal(new Timestamp(sdf.parse("1997-02-28 10:30:00").getTime)),
          Literal(new Timestamp(sdf.parse("1996-10-30 00:00:00").getTime)),
          Literal.TrueLiteral,
          timeZoneId = timeZoneId), 3.94959677)
      checkEvaluation(
        MonthsBetween(
          Literal(new Timestamp(sdf.parse("1997-02-28 10:30:00").getTime)),
          Literal(new Timestamp(sdf.parse("1996-10-30 00:00:00").getTime)),
          Literal.FalseLiteral,
          timeZoneId = timeZoneId), 3.9495967741935485)

      Seq(Literal.FalseLiteral, Literal.TrueLiteral). foreach { roundOff =>
        checkEvaluation(
          MonthsBetween(
            Literal(new Timestamp(sdf.parse("2015-01-30 11:52:00").getTime)),
            Literal(new Timestamp(sdf.parse("2015-01-30 11:50:00").getTime)),
            roundOff,
            timeZoneId = timeZoneId), 0.0)
        checkEvaluation(
          MonthsBetween(
            Literal(new Timestamp(sdf.parse("2015-01-31 00:00:00").getTime)),
            Literal(new Timestamp(sdf.parse("2015-03-31 22:00:00").getTime)),
            roundOff,
            timeZoneId = timeZoneId), -2.0)
        checkEvaluation(
          MonthsBetween(
            Literal(new Timestamp(sdf.parse("2015-03-31 22:00:00").getTime)),
            Literal(new Timestamp(sdf.parse("2015-02-28 00:00:00").getTime)),
            roundOff,
            timeZoneId = timeZoneId), 1.0)
      }
      val t = Literal(Timestamp.valueOf("2015-03-31 22:00:00"))
      val tnull = Literal.create(null, TimestampType)
      checkEvaluation(MonthsBetween(t, tnull, Literal.TrueLiteral, timeZoneId = timeZoneId), null)
      checkEvaluation(MonthsBetween(tnull, t, Literal.TrueLiteral, timeZoneId = timeZoneId), null)
      checkEvaluation(
        MonthsBetween(tnull, tnull, Literal.TrueLiteral, timeZoneId = timeZoneId), null)
      checkEvaluation(
        MonthsBetween(t, t, Literal.create(null, BooleanType), timeZoneId = timeZoneId), null)
      checkConsistencyBetweenInterpretedAndCodegen(
        (time1: Expression, time2: Expression, roundOff: Expression) =>
          MonthsBetween(time1, time2, roundOff, timeZoneId = timeZoneId),
        TimestampType, TimestampType, BooleanType)
    }
  }

  test("last_day") {
    checkEvaluation(LastDay(Literal(Date.valueOf("2015-02-28"))), Date.valueOf("2015-02-28"))
    checkEvaluation(LastDay(Literal(Date.valueOf("2015-03-27"))), Date.valueOf("2015-03-31"))
    checkEvaluation(LastDay(Literal(Date.valueOf("2015-04-26"))), Date.valueOf("2015-04-30"))
    checkEvaluation(LastDay(Literal(Date.valueOf("2015-05-25"))), Date.valueOf("2015-05-31"))
    checkEvaluation(LastDay(Literal(Date.valueOf("2015-06-24"))), Date.valueOf("2015-06-30"))
    checkEvaluation(LastDay(Literal(Date.valueOf("2015-07-23"))), Date.valueOf("2015-07-31"))
    checkEvaluation(LastDay(Literal(Date.valueOf("2015-08-01"))), Date.valueOf("2015-08-31"))
    checkEvaluation(LastDay(Literal(Date.valueOf("2015-09-02"))), Date.valueOf("2015-09-30"))
    checkEvaluation(LastDay(Literal(Date.valueOf("2015-10-03"))), Date.valueOf("2015-10-31"))
    checkEvaluation(LastDay(Literal(Date.valueOf("2015-11-04"))), Date.valueOf("2015-11-30"))
    checkEvaluation(LastDay(Literal(Date.valueOf("2015-12-05"))), Date.valueOf("2015-12-31"))
    checkEvaluation(LastDay(Literal(Date.valueOf("2016-01-06"))), Date.valueOf("2016-01-31"))
    checkEvaluation(LastDay(Literal(Date.valueOf("2016-02-07"))), Date.valueOf("2016-02-29"))
    checkEvaluation(LastDay(Literal.create(null, DateType)), null)
    checkConsistencyBetweenInterpretedAndCodegen(LastDay, DateType)
  }

  test("next_day") {
    def testNextDay(input: String, dayOfWeek: String, output: String): Unit = {
      checkEvaluation(
        NextDay(Literal(Date.valueOf(input)), NonFoldableLiteral(dayOfWeek)),
        DateTimeUtils.fromJavaDate(Date.valueOf(output)))
      checkEvaluation(
        NextDay(Literal(Date.valueOf(input)), Literal(dayOfWeek)),
        DateTimeUtils.fromJavaDate(Date.valueOf(output)))
    }
    testNextDay("2015-07-23", "Mon", "2015-07-27")
    testNextDay("2015-07-23", "mo", "2015-07-27")
    testNextDay("2015-07-23", "Tue", "2015-07-28")
    testNextDay("2015-07-23", "tu", "2015-07-28")
    testNextDay("2015-07-23", "we", "2015-07-29")
    testNextDay("2015-07-23", "wed", "2015-07-29")
    testNextDay("2015-07-23", "Thu", "2015-07-30")
    testNextDay("2015-07-23", "TH", "2015-07-30")
    testNextDay("2015-07-23", "Fri", "2015-07-24")
    testNextDay("2015-07-23", "fr", "2015-07-24")

    checkEvaluation(NextDay(Literal(Date.valueOf("2015-07-23")), Literal("xx")), null)
    checkEvaluation(NextDay(Literal.create(null, DateType), Literal("xx")), null)
    checkEvaluation(
      NextDay(Literal(Date.valueOf("2015-07-23")), Literal.create(null, StringType)), null)
    // Test escaping of dayOfWeek
    GenerateUnsafeProjection.generate(
      NextDay(Literal(Date.valueOf("2015-07-23")), Literal("\"quote")) :: Nil)
  }

  private def testTruncDate(input: Date, fmt: String, expected: Date): Unit = {
    checkEvaluation(TruncDate(Literal.create(input, DateType), Literal.create(fmt, StringType)),
      expected)
    checkEvaluation(
      TruncDate(Literal.create(input, DateType), NonFoldableLiteral.create(fmt, StringType)),
      expected)
  }

  test("TruncDate") {
    val date = Date.valueOf("2015-07-22")
    Seq("yyyy", "YYYY", "year", "YEAR", "yy", "YY").foreach { fmt =>
      testTruncDate(date, fmt, Date.valueOf("2015-01-01"))
    }
    Seq("month", "MONTH", "mon", "MON", "mm", "MM").foreach { fmt =>
      testTruncDate(date, fmt, Date.valueOf("2015-07-01"))
    }
    testTruncDate(date, "DD", null)
    testTruncDate(date, "SECOND", null)
    testTruncDate(date, "HOUR", null)
    testTruncDate(null, "MON", null)
    // Test escaping of format
    GenerateUnsafeProjection.generate(TruncDate(Literal(0, DateType), Literal("\"quote")) :: Nil)
  }

  private def testTruncTimestamp(input: Timestamp, fmt: String, expected: Timestamp): Unit = {
    checkEvaluation(
      TruncTimestamp(Literal.create(fmt, StringType), Literal.create(input, TimestampType)),
      expected)
    checkEvaluation(
      TruncTimestamp(
        NonFoldableLiteral.create(fmt, StringType), Literal.create(input, TimestampType)),
      expected)
  }

  test("TruncTimestamp") {
    withDefaultTimeZone(UTC) {
      val inputDate = Timestamp.valueOf("2015-07-22 05:30:06")

      Seq("yyyy", "YYYY", "year", "YEAR", "yy", "YY").foreach { fmt =>
        testTruncTimestamp(
          inputDate, fmt,
          Timestamp.valueOf("2015-01-01 00:00:00"))
      }

      Seq("month", "MONTH", "mon", "MON", "mm", "MM").foreach { fmt =>
        testTruncTimestamp(
          inputDate, fmt,
          Timestamp.valueOf("2015-07-01 00:00:00"))
      }

      Seq("DAY", "day", "DD", "dd").foreach { fmt =>
        testTruncTimestamp(
          inputDate, fmt,
          Timestamp.valueOf("2015-07-22 00:00:00"))
      }

      Seq("HOUR", "hour").foreach { fmt =>
        testTruncTimestamp(
          inputDate, fmt,
          Timestamp.valueOf("2015-07-22 05:00:00"))
      }

      Seq("MINUTE", "minute").foreach { fmt =>
        testTruncTimestamp(
          inputDate, fmt,
          Timestamp.valueOf("2015-07-22 05:30:00"))
      }

      Seq("SECOND", "second").foreach { fmt =>
        testTruncTimestamp(
          inputDate, fmt,
          Timestamp.valueOf("2015-07-22 05:30:06"))
      }

      Seq("WEEK", "week").foreach { fmt =>
        testTruncTimestamp(
          inputDate, fmt,
          Timestamp.valueOf("2015-07-20 00:00:00"))
      }

      Seq("QUARTER", "quarter").foreach { fmt =>
        testTruncTimestamp(
          inputDate, fmt,
          Timestamp.valueOf("2015-07-01 00:00:00"))
      }

      testTruncTimestamp(null, "MON", null)
    }
  }

  test("unsupported fmt fields for trunc/date_trunc results null") {
    Seq("INVALID", "decade", "century", "millennium", "whatever", null).foreach { field =>
      testTruncDate(Date.valueOf("2000-03-08"), field, null)
      testTruncDate(null, field, null)
      testTruncTimestamp(Timestamp.valueOf("2000-03-08 11:12:13"), field, null)
      testTruncTimestamp(null, field, null)
    }
  }

  test("from_unixtime") {
    Seq("legacy", "corrected").foreach { legacyParserPolicy =>
      withSQLConf(SQLConf.LEGACY_TIME_PARSER_POLICY.key -> legacyParserPolicy) {
        val fmt1 = "yyyy-MM-dd HH:mm:ss"
        val sdf1 = new SimpleDateFormat(fmt1, Locale.US)
        val fmt2 = "yyyy-MM-dd HH:mm:ss.SSS"
        val sdf2 = new SimpleDateFormat(fmt2, Locale.US)
        for (zid <- outstandingZoneIds) {
          val timeZoneId = Option(zid.getId)
          val tz = TimeZone.getTimeZone(zid)
          sdf1.setTimeZone(tz)
          sdf2.setTimeZone(tz)

          checkEvaluation(
            FromUnixTime(Literal(0L), Literal(fmt1), timeZoneId),
            sdf1.format(new Timestamp(0)))
          checkEvaluation(FromUnixTime(
            Literal(1000L), Literal(fmt1), timeZoneId),
            sdf1.format(new Timestamp(1000000)))
          checkEvaluation(
            FromUnixTime(Literal(-1000L), Literal(fmt2), timeZoneId),
            sdf2.format(new Timestamp(-1000000)))
          checkEvaluation(
            FromUnixTime(
              Literal.create(null, LongType),
              Literal.create(null, StringType), timeZoneId),
            null)
          checkEvaluation(
            FromUnixTime(Literal.create(null, LongType), Literal(fmt1), timeZoneId),
            null)
          checkEvaluation(
            FromUnixTime(Literal(1000L), Literal.create(null, StringType), timeZoneId),
            null)

          // SPARK-28072 The codegen path for non-literal input should also work
          checkEvaluation(
            expression = FromUnixTime(
              BoundReference(ordinal = 0, dataType = LongType, nullable = true),
              BoundReference(ordinal = 1, dataType = StringType, nullable = true),
              timeZoneId),
            expected = UTF8String.fromString(sdf1.format(new Timestamp(0))),
            inputRow = InternalRow(0L, UTF8String.fromString(fmt1)))
        }
      }
    }
    // Test escaping of format
    GenerateUnsafeProjection.generate(FromUnixTime(Literal(0L), Literal("\""), UTC_OPT) :: Nil)
  }

  test("unix_timestamp") {
    Seq("legacy", "corrected").foreach { legacyParserPolicy =>
      withSQLConf(SQLConf.LEGACY_TIME_PARSER_POLICY.key -> legacyParserPolicy) {
        val sdf1 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val fmt2 = "yyyy-MM-dd HH:mm:ss.SSS"
        val sdf2 = new SimpleDateFormat(fmt2, Locale.US)
        val fmt3 = "yy-MM-dd"
        val sdf3 = new SimpleDateFormat(fmt3, Locale.US)
        sdf3.setTimeZone(TimeZoneUTC)

        withDefaultTimeZone(UTC) {
          for (zid <- outstandingZoneIds) {
            val timeZoneId = Option(zid.getId)
            val tz = TimeZone.getTimeZone(zid)
            sdf1.setTimeZone(tz)
            sdf2.setTimeZone(tz)

            val date1 = Date.valueOf("2015-07-24")
            checkEvaluation(UnixTimestamp(
              Literal(sdf1.format(new Timestamp(0))),
              Literal("yyyy-MM-dd HH:mm:ss"), timeZoneId), 0L)
            checkEvaluation(UnixTimestamp(
              Literal(sdf1.format(new Timestamp(1000000))),
              Literal("yyyy-MM-dd HH:mm:ss"), timeZoneId),
              1000L)
            checkEvaluation(
              UnixTimestamp(
                Literal(new Timestamp(1000000)), Literal("yyyy-MM-dd HH:mm:ss"), timeZoneId),
              1000L)
            checkEvaluation(
              UnixTimestamp(Literal(date1), Literal("yyyy-MM-dd HH:mm:ss"), timeZoneId),
              MICROSECONDS.toSeconds(
                DateTimeUtils.daysToMicros(DateTimeUtils.fromJavaDate(date1), tz.toZoneId)))
            checkEvaluation(
              UnixTimestamp(Literal(sdf2.format(new Timestamp(-1000000))),
                Literal(fmt2), timeZoneId),
              -1000L)
            checkEvaluation(UnixTimestamp(
              Literal(sdf3.format(Date.valueOf("2015-07-24"))), Literal(fmt3), timeZoneId),
              MICROSECONDS.toSeconds(DateTimeUtils.daysToMicros(
                DateTimeUtils.fromJavaDate(Date.valueOf("2015-07-24")), tz.toZoneId)))
            val t1 = UnixTimestamp(
              CurrentTimestamp(), Literal("yyyy-MM-dd HH:mm:ss")).eval().asInstanceOf[Long]
            val t2 = UnixTimestamp(
              CurrentTimestamp(), Literal("yyyy-MM-dd HH:mm:ss")).eval().asInstanceOf[Long]
            assert(t2 - t1 <= 1)
            checkEvaluation(
              UnixTimestamp(
                Literal.create(null, DateType), Literal.create(null, StringType), timeZoneId),
              null)
            checkEvaluation(
              UnixTimestamp(
                Literal.create(null, DateType),
                Literal("yyyy-MM-dd HH:mm:ss"), timeZoneId),
              null)
            checkEvaluation(
              UnixTimestamp(Literal(date1), Literal.create(null, StringType), timeZoneId),
              MICROSECONDS.toSeconds(
                DateTimeUtils.daysToMicros(DateTimeUtils.fromJavaDate(date1), tz.toZoneId)))
          }
        }
      }
    }
    // Test escaping of format
    GenerateUnsafeProjection.generate(
      UnixTimestamp(Literal("2015-07-24"), Literal("\""), UTC_OPT) :: Nil)
  }

  test("to_unix_timestamp") {
    Seq("legacy", "corrected").foreach { legacyParserPolicy =>
      withSQLConf(SQLConf.LEGACY_TIME_PARSER_POLICY.key -> legacyParserPolicy) {
        val fmt1 = "yyyy-MM-dd HH:mm:ss"
        val sdf1 = new SimpleDateFormat(fmt1, Locale.US)
        val fmt2 = "yyyy-MM-dd HH:mm:ss.SSS"
        val sdf2 = new SimpleDateFormat(fmt2, Locale.US)
        val fmt3 = "yy-MM-dd"
        val sdf3 = new SimpleDateFormat(fmt3, Locale.US)
        sdf3.setTimeZone(TimeZoneUTC)

        withDefaultTimeZone(UTC) {
          for (zid <- outstandingZoneIds) {
            val timeZoneId = Option(zid.getId)
            val tz = TimeZone.getTimeZone(zid)
            sdf1.setTimeZone(tz)
            sdf2.setTimeZone(tz)

            val date1 = Date.valueOf("2015-07-24")
            checkEvaluation(ToUnixTimestamp(
              Literal(sdf1.format(new Timestamp(0))), Literal(fmt1), timeZoneId), 0L)
            checkEvaluation(ToUnixTimestamp(
              Literal(sdf1.format(new Timestamp(1000000))), Literal(fmt1), timeZoneId),
              1000L)
            checkEvaluation(ToUnixTimestamp(
              Literal(new Timestamp(1000000)), Literal(fmt1)),
              1000L)
            checkEvaluation(
              ToUnixTimestamp(Literal(date1), Literal(fmt1), timeZoneId),
              MICROSECONDS.toSeconds(
                DateTimeUtils.daysToMicros(DateTimeUtils.fromJavaDate(date1), zid)))
            checkEvaluation(
              ToUnixTimestamp(
                Literal(sdf2.format(new Timestamp(-1000000))),
                Literal(fmt2), timeZoneId),
              -1000L)
            checkEvaluation(ToUnixTimestamp(
              Literal(sdf3.format(Date.valueOf("2015-07-24"))), Literal(fmt3), timeZoneId),
              MICROSECONDS.toSeconds(DateTimeUtils.daysToMicros(
                DateTimeUtils.fromJavaDate(Date.valueOf("2015-07-24")), zid)))
            val t1 = ToUnixTimestamp(
              CurrentTimestamp(), Literal(fmt1)).eval().asInstanceOf[Long]
            val t2 = ToUnixTimestamp(
              CurrentTimestamp(), Literal(fmt1)).eval().asInstanceOf[Long]
            assert(t2 - t1 <= 1)
            checkEvaluation(ToUnixTimestamp(
              Literal.create(null, DateType), Literal.create(null, StringType), timeZoneId), null)
            checkEvaluation(
              ToUnixTimestamp(
                Literal.create(null, DateType), Literal(fmt1), timeZoneId),
              null)
            checkEvaluation(ToUnixTimestamp(
              Literal(date1), Literal.create(null, StringType), timeZoneId),
              MICROSECONDS.toSeconds(
                DateTimeUtils.daysToMicros(DateTimeUtils.fromJavaDate(date1), zid)))

            // SPARK-28072 The codegen path for non-literal input should also work
            checkEvaluation(
              expression = ToUnixTimestamp(
                BoundReference(ordinal = 0, dataType = StringType, nullable = true),
                BoundReference(ordinal = 1, dataType = StringType, nullable = true),
                timeZoneId),
              expected = 0L,
              inputRow = InternalRow(
                UTF8String.fromString(sdf1.format(new Timestamp(0))), UTF8String.fromString(fmt1)))
          }
        }
      }
    }
    // Test escaping of format
    GenerateUnsafeProjection.generate(
      ToUnixTimestamp(Literal("2015-07-24"), Literal("\""), UTC_OPT) :: Nil)
  }

  test("datediff") {
    checkEvaluation(
      DateDiff(Literal(Date.valueOf("2015-07-24")), Literal(Date.valueOf("2015-07-21"))), 3)
    checkEvaluation(
      DateDiff(Literal(Date.valueOf("2015-07-21")), Literal(Date.valueOf("2015-07-24"))), -3)
    checkEvaluation(DateDiff(Literal.create(null, DateType), Literal(Date.valueOf("2015-07-24"))),
      null)
    checkEvaluation(DateDiff(Literal(Date.valueOf("2015-07-24")), Literal.create(null, DateType)),
      null)
    checkEvaluation(
      DateDiff(Literal.create(null, DateType), Literal.create(null, DateType)),
      null)
  }

  test("to_utc_timestamp") {
    def test(t: String, tz: String, expected: String): Unit = {
      checkEvaluation(
        ToUTCTimestamp(
          Literal.create(if (t != null) Timestamp.valueOf(t) else null, TimestampType),
          Literal.create(tz, StringType)),
        if (expected != null) Timestamp.valueOf(expected) else null)
      checkEvaluation(
        ToUTCTimestamp(
          Literal.create(if (t != null) Timestamp.valueOf(t) else null, TimestampType),
          NonFoldableLiteral.create(tz, StringType)),
        if (expected != null) Timestamp.valueOf(expected) else null)
    }
    test("2015-07-24 00:00:00", LA.getId, "2015-07-24 07:00:00")
    test("2015-01-24 00:00:00", LA.getId, "2015-01-24 08:00:00")
    test(null, "UTC", null)
    test("2015-07-24 00:00:00", null, null)
    test(null, null, null)
  }

  test("to_utc_timestamp - invalid time zone id") {
    Seq("Invalid time zone", "\"quote", "UTC*42").foreach { invalidTz =>
      val msg = intercept[java.time.DateTimeException] {
        GenerateUnsafeProjection.generate(
          ToUTCTimestamp(
            Literal(Timestamp.valueOf("2015-07-24 00:00:00")), Literal(invalidTz)) :: Nil)
      }.getMessage
      assert(msg.contains(invalidTz))
    }
  }

  test("from_utc_timestamp") {
    def test(t: String, tz: String, expected: String): Unit = {
      checkEvaluation(
        FromUTCTimestamp(
          Literal.create(if (t != null) Timestamp.valueOf(t) else null, TimestampType),
          Literal.create(tz, StringType)),
        if (expected != null) Timestamp.valueOf(expected) else null)
      checkEvaluation(
        FromUTCTimestamp(
          Literal.create(if (t != null) Timestamp.valueOf(t) else null, TimestampType),
          NonFoldableLiteral.create(tz, StringType)),
        if (expected != null) Timestamp.valueOf(expected) else null)
    }
    test("2015-07-24 00:00:00", LA.getId, "2015-07-23 17:00:00")
    test("2015-01-24 00:00:00", LA.getId, "2015-01-23 16:00:00")
    test(null, "UTC", null)
    test("2015-07-24 00:00:00", null, null)
    test(null, null, null)
  }

  test("from_utc_timestamp - invalid time zone id") {
    Seq("Invalid time zone", "\"quote", "UTC*42").foreach { invalidTz =>
      val msg = intercept[java.time.DateTimeException] {
        GenerateUnsafeProjection.generate(FromUTCTimestamp(Literal(0), Literal(invalidTz)) :: Nil)
      }.getMessage
      assert(msg.contains(invalidTz))
    }
  }

  test("creating values of DateType via make_date") {
    Seq(true, false).foreach({ ansi =>
      withSQLConf(SQLConf.ANSI_ENABLED.key -> ansi.toString) {
        checkEvaluation(MakeDate(Literal(2013), Literal(7), Literal(15)), Date.valueOf("2013-7-15"))
        checkEvaluation(MakeDate(Literal.create(null, IntegerType), Literal(7), Literal(15)), null)
        checkEvaluation(MakeDate(Literal(2019), Literal.create(null, IntegerType), Literal(19)),
          null)
        checkEvaluation(MakeDate(Literal(2019), Literal(7), Literal.create(null, IntegerType)),
          null)
      }
    })

    // ansi test
    withSQLConf(SQLConf.ANSI_ENABLED.key -> "true") {
      checkExceptionInExpression[DateTimeException](MakeDate(Literal(Int.MaxValue), Literal(13),
        Literal(19)), EmptyRow, "Invalid value for Year")
      checkExceptionInExpression[DateTimeException](MakeDate(Literal(2019),
        Literal(13), Literal(19)), EmptyRow, "Invalid value for Month")
      checkExceptionInExpression[DateTimeException](MakeDate(Literal(2019), Literal(7),
        Literal(32)), EmptyRow, "Invalid value for Day")
    }

    // non-ansi test
    withSQLConf(SQLConf.ANSI_ENABLED.key -> "false") {
      checkEvaluation(MakeDate(Literal(Int.MaxValue), Literal(13), Literal(19)), null)
      checkEvaluation(MakeDate(Literal(2019), Literal(13), Literal(19)), null)
      checkEvaluation(MakeDate(Literal(2019), Literal(7), Literal(32)), null)
    }
  }

  test("creating values of TimestampType via make_timestamp") {
    val expected = Timestamp.valueOf("2013-7-15 8:15:23.5")

    Seq(true, false).foreach { ansi =>
      withSQLConf(SQLConf.ANSI_ENABLED.key -> ansi.toString) {
        var makeTimestampExpr = MakeTimestamp(
          Literal(2013), Literal(7), Literal(15), Literal(8), Literal(15),
          Literal(Decimal(BigDecimal(23.5), 8, 6)), Some(Literal(ZoneId.systemDefault().getId)))
        checkEvaluation(makeTimestampExpr, expected)
        checkEvaluation(makeTimestampExpr.copy(year = Literal.create(null, IntegerType)), null)
        checkEvaluation(makeTimestampExpr.copy(month = Literal.create(null, IntegerType)), null)
        checkEvaluation(makeTimestampExpr.copy(day = Literal.create(null, IntegerType)), null)
        checkEvaluation(makeTimestampExpr.copy(hour = Literal.create(null, IntegerType)), null)
        checkEvaluation(makeTimestampExpr.copy(min = Literal.create(null, IntegerType)), null)
        checkEvaluation(makeTimestampExpr.copy(sec = Literal.create(null, DecimalType(8, 6))), null)
        checkEvaluation(makeTimestampExpr.copy(timezone = None), expected)

        Seq(
          (makeTimestampExpr.copy(year = Literal(Int.MaxValue)), "Invalid value for Year"),
          (makeTimestampExpr.copy(month = Literal(13)), "Invalid value for Month"),
          (makeTimestampExpr.copy(day = Literal(32)), "Invalid value for Day"),
          (makeTimestampExpr.copy(hour = Literal(25)), "Invalid value for Hour"),
          (makeTimestampExpr.copy(min = Literal(65)), "Invalid value for Min"),
          (makeTimestampExpr.copy(sec = Literal(Decimal(
            BigDecimal(70.0), 8, 6))), "Invalid value for Second")
        ).foreach { entry =>
          if (ansi) {
            checkExceptionInExpression[DateTimeException](entry._1, EmptyRow, entry._2)
          } else {
            checkEvaluation(entry._1, null)
          }
        }

        makeTimestampExpr = MakeTimestamp(Literal(2019), Literal(6), Literal(30),
          Literal(23), Literal(59), Literal(Decimal(BigDecimal(60.0), 8, 6)))
        if (ansi) {
          checkExceptionInExpression[DateTimeException](makeTimestampExpr.copy(sec = Literal(
            Decimal(BigDecimal(60.5), 8, 6))), EmptyRow, "The fraction of sec must be zero")
        } else {
          checkEvaluation(makeTimestampExpr, Timestamp.valueOf("2019-07-01 00:00:00"))
        }

        makeTimestampExpr = MakeTimestamp(Literal(2019), Literal(8), Literal(12), Literal(0),
          Literal(0), Literal(Decimal(BigDecimal(58.000001), 8, 6)))
        checkEvaluation(makeTimestampExpr, Timestamp.valueOf("2019-08-12 00:00:58.000001"))
      }
    }

    // non-ansi test
    withSQLConf(SQLConf.ANSI_ENABLED.key -> "false") {
      val makeTimestampExpr = MakeTimestamp(Literal(2019), Literal(6), Literal(30),
        Literal(23), Literal(59), Literal(Decimal(BigDecimal(60.0), 8, 6)))
      checkEvaluation(makeTimestampExpr.copy(sec = Literal(Decimal(BigDecimal(60.5), 8, 6))), null)
    }

    Seq(true, false).foreach { ansi =>
      withSQLConf(SQLConf.ANSI_ENABLED.key -> ansi.toString) {
        val makeTimestampExpr = MakeTimestamp(Literal(2019), Literal(8), Literal(12),
          Literal(0), Literal(0), Literal(Decimal(BigDecimal(58.000001), 8, 6)))
        checkEvaluation(makeTimestampExpr, Timestamp.valueOf("2019-08-12 00:00:58.000001"))
      }
    }
  }

  test("ISO 8601 week-numbering year") {
    checkEvaluation(YearOfWeek(MakeDate(Literal(2006), Literal(1), Literal(1))), 2005)
    checkEvaluation(YearOfWeek(MakeDate(Literal(2006), Literal(1), Literal(2))), 2006)
  }

  test("extract the seconds part with fraction from timestamps") {
    outstandingTimezonesIds.foreach { timezone =>
      val timestamp = MakeTimestamp(Literal(2019), Literal(8), Literal(10),
        Literal(0), Literal(0), Literal(Decimal(10.123456, 8, 6)),
        Some(Literal(timezone)), Some(timezone))
      def secFrac(ts: MakeTimestamp): SecondWithFraction = SecondWithFraction(ts, Some(timezone))

      checkEvaluation(secFrac(timestamp), Decimal(10.123456, 8, 6))
      checkEvaluation(
        secFrac(timestamp.copy(sec = Literal(Decimal(59000001, 8, 6)))),
        Decimal(59000001, 8, 6))
      checkEvaluation(
        secFrac(timestamp.copy(sec = Literal(Decimal(1, 8, 6)))),
        Decimal(0.000001, 8, 6))
      checkEvaluation(
        secFrac(timestamp.copy(year = Literal(10))),
        Decimal(10.123456, 8, 6))
    }
  }

  test("timestamps difference") {
    val end = Instant.parse("2019-10-04T11:04:01.123456Z")
    checkEvaluation(SubtractTimestamps(Literal(end), Literal(end)),
      new CalendarInterval(0, 0, 0))
    checkEvaluation(SubtractTimestamps(Literal(end), Literal(Instant.EPOCH)),
      IntervalUtils.stringToInterval(UTF8String.fromString("interval " +
        "436163 hours 4 minutes 1 seconds 123 milliseconds 456 microseconds")))
    checkEvaluation(SubtractTimestamps(Literal(Instant.EPOCH), Literal(end)),
      IntervalUtils.stringToInterval(UTF8String.fromString("interval " +
        "-436163 hours -4 minutes -1 seconds -123 milliseconds -456 microseconds")))
    checkEvaluation(
      SubtractTimestamps(
        Literal(Instant.parse("9999-12-31T23:59:59.999999Z")),
        Literal(Instant.parse("0001-01-01T00:00:00Z"))),
      IntervalUtils.stringToInterval(UTF8String.fromString("interval " +
        "87649415 hours 59 minutes 59 seconds 999 milliseconds 999 microseconds")))
  }

  test("subtract dates") {
    val end = LocalDate.of(2019, 10, 5)
    checkEvaluation(SubtractDates(Literal(end), Literal(end)),
      new CalendarInterval(0, 0, 0))
    checkEvaluation(SubtractDates(Literal(end.plusDays(1)), Literal(end)),
      IntervalUtils.stringToInterval(UTF8String.fromString("interval 1 days")))
    checkEvaluation(SubtractDates(Literal(end.minusDays(1)), Literal(end)),
      IntervalUtils.stringToInterval(UTF8String.fromString("interval -1 days")))
    val epochDate = Literal(LocalDate.ofEpochDay(0))
    checkEvaluation(SubtractDates(Literal(end), epochDate),
      IntervalUtils.stringToInterval(UTF8String.fromString("interval 49 years 9 months 4 days")))
    checkEvaluation(SubtractDates(epochDate, Literal(end)),
      IntervalUtils.stringToInterval(UTF8String.fromString("interval -49 years -9 months -4 days")))
    checkEvaluation(
      SubtractDates(
        Literal(LocalDate.of(10000, 1, 1)),
        Literal(LocalDate.of(1, 1, 1))),
      IntervalUtils.stringToInterval(UTF8String.fromString("interval 9999 years")))
  }

  test("to_timestamp exception mode") {
    withSQLConf(SQLConf.LEGACY_TIME_PARSER_POLICY.key -> "legacy") {
      checkEvaluation(
        GetTimestamp(
          Literal("2020-01-27T20:06:11.847-0800"),
          Literal("yyyy-MM-dd'T'HH:mm:ss.SSSz")), 1580184371847000L)
    }
    withSQLConf(SQLConf.LEGACY_TIME_PARSER_POLICY.key -> "corrected") {
      checkEvaluation(
        GetTimestamp(
          Literal("2020-01-27T20:06:11.847-0800"),
          Literal("yyyy-MM-dd'T'HH:mm:ss.SSSz")), null)
    }
    withSQLConf(SQLConf.LEGACY_TIME_PARSER_POLICY.key -> "exception") {
      checkExceptionInExpression[SparkUpgradeException](
        GetTimestamp(
          Literal("2020-01-27T20:06:11.847-0800"),
          Literal("yyyy-MM-dd'T'HH:mm:ss.SSSz")), "Fail to parse")
    }
  }

  test("Consistent error handling for datetime formatting and parsing functions") {

    def checkException[T <: Exception : ClassTag](c: String): Unit = {
      checkExceptionInExpression[T](new ParseToTimestamp(Literal("1"), Literal(c)).child, c)
      checkExceptionInExpression[T](new ParseToDate(Literal("1"), Literal(c)).child, c)
      checkExceptionInExpression[T](ToUnixTimestamp(Literal("1"), Literal(c)), c)
      checkExceptionInExpression[T](UnixTimestamp(Literal("1"), Literal(c)), c)
      if (!Set("E", "F", "q", "Q").contains(c)) {
        checkExceptionInExpression[T](DateFormatClass(CurrentTimestamp(), Literal(c)), c)
        checkExceptionInExpression[T](FromUnixTime(Literal(0L), Literal(c)), c)
      }
    }

    Seq('Y', 'W', 'w', 'E', 'u', 'F').foreach { l =>
      checkException[SparkUpgradeException](l.toString)
    }

    Seq('q', 'Q', 'e', 'c', 'A', 'n', 'N', 'p').foreach { l =>
      checkException[IllegalArgumentException](l.toString)
    }
  }

  test("SPARK-31896: Handle am-pm timestamp parsing when hour is missing") {
    checkEvaluation(
      new ParseToTimestamp(Literal("PM"), Literal("a")).child,
      Timestamp.valueOf("1970-01-01 12:00:00.0"))
    checkEvaluation(
      new ParseToTimestamp(Literal("11:11 PM"), Literal("mm:ss a")).child,
      Timestamp.valueOf("1970-01-01 12:11:11.0"))
  }

  def testIntegralInput(testFunc: Number => Unit): Unit = {
    def checkResult(input: Long): Unit = {
      if (input.toByte == input) {
        testFunc(input.toByte)
      } else if (input.toShort == input) {
        testFunc(input.toShort)
      } else if (input.toInt == input) {
        testFunc(input.toInt)
      } else {
        testFunc(input)
      }
    }
    checkResult(0)
    checkResult(Byte.MaxValue)
    checkResult(Byte.MinValue)
    checkResult(Short.MaxValue)
    checkResult(Short.MinValue)
    checkResult(Int.MaxValue)
    checkResult(Int.MinValue)
    checkResult(Int.MaxValue.toLong + 100)
    checkResult(Int.MinValue.toLong - 100)
  }

  test("DATE_FROM_UNIX_DATE") {
    def testIntegralFunc(value: Number): Unit = {
      checkEvaluation(
        DateFromUnixDate(Literal(value.intValue())),
        LocalDate.ofEpochDay(value.intValue()))
    }
    // test null input
    checkEvaluation(DateFromUnixDate(Literal(null, IntegerType)), null)
    // test integral input
    testIntegralInput(testIntegralFunc)
  }

  test("UNIX_DATE") {
    def testIntegralFunc(value: Number): Unit = {
      checkEvaluation(
        UnixDate(Literal(LocalDate.ofEpochDay(value.intValue()))),
        value.intValue())
    }
    // test null input
    checkEvaluation(UnixDate(Literal(null, DateType)), null)
    // test various inputs
    testIntegralInput(testIntegralFunc)
  }

  test("UNIX_SECONDS") {
    checkEvaluation(UnixSeconds(Literal(null, TimestampType)), null)
    var timestamp = Literal(new Timestamp(0L))
    checkEvaluation(UnixSeconds(timestamp), 0L)
    timestamp = Literal(new Timestamp(1000L))
    checkEvaluation(UnixSeconds(timestamp), 1L)
    timestamp = Literal(new Timestamp(-1000L))
    checkEvaluation(UnixSeconds(timestamp), -1L)
    // -1ms is considered to be in -1st second, as 0-999ms is in 0th second.
    timestamp = Literal(new Timestamp(-1L))
    checkEvaluation(UnixSeconds(timestamp), -1L)
    timestamp = Literal(new Timestamp(-1000L))
    checkEvaluation(UnixSeconds(timestamp), -1L)
    // Truncates higher levels of precision
    timestamp = Literal(new Timestamp(1999L))
    checkEvaluation(UnixSeconds(timestamp), 1L)
  }

  test("UNIX_MILLIS") {
    checkEvaluation(UnixMillis(Literal(null, TimestampType)), null)
    var timestamp = Literal(new Timestamp(0L))
    checkEvaluation(UnixMillis(timestamp), 0L)
    timestamp = Literal(new Timestamp(1000L))
    checkEvaluation(UnixMillis(timestamp), 1000L)
    timestamp = Literal(new Timestamp(-1000L))
    checkEvaluation(UnixMillis(timestamp), -1000L)
    // Truncates higher levels of precision
    val timestampWithNanos = new Timestamp(1000L)
    timestampWithNanos.setNanos(999999)
    checkEvaluation(UnixMillis(Literal(timestampWithNanos)), 1000L)
  }

  test("UNIX_MICROS") {
    checkEvaluation(UnixMicros(Literal(null, TimestampType)), null)
    var timestamp = Literal(new Timestamp(0L))
    checkEvaluation(UnixMicros(timestamp), 0L)
    timestamp = Literal(new Timestamp(1000L))
    checkEvaluation(UnixMicros(timestamp), 1000000L)
    timestamp = Literal(new Timestamp(-1000L))
    checkEvaluation(UnixMicros(timestamp), -1000000L)
    val timestampWithNanos = new Timestamp(1000L)
    timestampWithNanos.setNanos(1000) // 1 microsecond
    checkEvaluation(UnixMicros(Literal(timestampWithNanos)), 1000001L)
  }

  test("TIMESTAMP_SECONDS") {
    def testIntegralFunc(value: Number): Unit = {
      checkEvaluation(
        SecondsToTimestamp(Literal(value)),
        Instant.ofEpochSecond(value.longValue()))
    }

    // test null input
    checkEvaluation(
      SecondsToTimestamp(Literal(null, IntegerType)),
      null)

    // test integral input
    testIntegralInput(testIntegralFunc)
    // test overflow
    checkExceptionInExpression[ArithmeticException](
      SecondsToTimestamp(Literal(Long.MaxValue, LongType)), EmptyRow, "long overflow")

    def testFractionalInput(input: String): Unit = {
      Seq(input.toFloat, input.toDouble, Decimal(input)).foreach { value =>
        checkEvaluation(
          SecondsToTimestamp(Literal(value)),
          (input.toDouble * MICROS_PER_SECOND).toLong)
      }
    }

    testFractionalInput("1.0")
    testFractionalInput("-1.0")
    testFractionalInput("1.234567")
    testFractionalInput("-1.234567")

    // test overflow for decimal input
    checkExceptionInExpression[ArithmeticException](
      SecondsToTimestamp(Literal(Decimal("9" * 38))), "Overflow"
    )
    // test truncation error for decimal input
    checkExceptionInExpression[ArithmeticException](
      SecondsToTimestamp(Literal(Decimal("0.1234567"))), "Rounding necessary"
    )

    // test NaN
    checkEvaluation(
      SecondsToTimestamp(Literal(Double.NaN)),
      null)
    checkEvaluation(
      SecondsToTimestamp(Literal(Float.NaN)),
      null)
    // double input can truncate
    checkEvaluation(
      SecondsToTimestamp(Literal(123.456789123)),
      Instant.ofEpochSecond(123, 456789000))
    checkEvaluation(SecondsToTimestamp(Literal(16777215.0f)), Instant.ofEpochSecond(16777215))
  }

  test("TIMESTAMP_MILLIS") {
    def testIntegralFunc(value: Number): Unit = {
      checkEvaluation(
        MillisToTimestamp(Literal(value)),
        Instant.ofEpochMilli(value.longValue()))
    }

    // test null input
    checkEvaluation(
      MillisToTimestamp(Literal(null, IntegerType)),
      null)

    // test integral input
    testIntegralInput(testIntegralFunc)
    // test overflow
    checkExceptionInExpression[ArithmeticException](
      MillisToTimestamp(Literal(Long.MaxValue, LongType)), EmptyRow, "long overflow")
  }

  test("TIMESTAMP_MICROS") {
    def testIntegralFunc(value: Number): Unit = {
      checkEvaluation(
        MicrosToTimestamp(Literal(value)),
        value.longValue())
    }

    // test null input
    checkEvaluation(
      MicrosToTimestamp(Literal(null, IntegerType)),
      null)

    // test integral input
    testIntegralInput(testIntegralFunc)
    // test max/min input
    testIntegralFunc(Long.MaxValue)
    testIntegralFunc(Long.MinValue)
  }

  test("SPARK-33498: GetTimestamp,UnixTimestamp,ToUnixTimestamp with parseError") {
    Seq(true, false).foreach { ansiEnabled =>
      Seq("LEGACY", "CORRECTED", "EXCEPTION").foreach { policy =>
        withSQLConf(SQLConf.LEGACY_TIME_PARSER_POLICY.key -> policy,
          SQLConf.ANSI_ENABLED.key -> ansiEnabled.toString) {

          val exprSeq = Seq[Expression](
            GetTimestamp(Literal("2020-01-27T20:06:11.847"), Literal("yyyy-MM-dd HH:mm:ss.SSS")),
            GetTimestamp(Literal("Unparseable"), Literal("yyyy-MM-dd HH:mm:ss.SSS")),
            UnixTimestamp(Literal("2020-01-27T20:06:11.847"), Literal("yyyy-MM-dd HH:mm:ss.SSS")),
            UnixTimestamp(Literal("Unparseable"), Literal("yyyy-MM-dd HH:mm:ss.SSS")),
            ToUnixTimestamp(Literal("2020-01-27T20:06:11.847"), Literal("yyyy-MM-dd HH:mm:ss.SSS")),
            ToUnixTimestamp(Literal("Unparseable"), Literal("yyyy-MM-dd HH:mm:ss.SSS"))
          )

          if (!ansiEnabled) {
            exprSeq.foreach(checkEvaluation(_, null))
          } else if (policy == "LEGACY") {
            exprSeq.foreach(checkExceptionInExpression[ParseException](_, "Unparseable"))
          } else {
            exprSeq.foreach(
              checkExceptionInExpression[DateTimeParseException](_, "could not be parsed"))
          }

          // LEGACY works, CORRECTED failed, EXCEPTION with SparkUpgradeException
          val exprSeq2 = Seq[(Expression, Long)](
            (GetTimestamp(Literal("2020-01-27T20:06:11.847!!!"),
              Literal("yyyy-MM-dd'T'HH:mm:ss.SSS")), 1580184371847000L),
            (UnixTimestamp(Literal("2020-01-27T20:06:11.847!!!"),
              Literal("yyyy-MM-dd'T'HH:mm:ss.SSS")), 1580184371L),
            (ToUnixTimestamp(Literal("2020-01-27T20:06:11.847!!!"),
              Literal("yyyy-MM-dd'T'HH:mm:ss.SSS")), 1580184371L)
          )

          if (policy == "LEGACY") {
            exprSeq2.foreach(pair => checkEvaluation(pair._1, pair._2))
          } else if (policy == "EXCEPTION") {
            exprSeq2.foreach(pair =>
              checkExceptionInExpression[SparkUpgradeException](
                pair._1,
                  "You may get a different result due to the upgrading of Spark 3.0"))
          } else {
            if (ansiEnabled) {
              exprSeq2.foreach(pair =>
                checkExceptionInExpression[DateTimeParseException](pair._1, "could not be parsed"))
            } else {
              exprSeq2.foreach(pair => checkEvaluation(pair._1, null))
            }
          }
        }
      }
    }
  }
 }
