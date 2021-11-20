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

package org.apache.spark.sql.catalyst.util

import java.nio.charset.StandardCharsets
import java.sql.{Date, Timestamp}
import java.time._
import java.time.temporal.{ChronoField, ChronoUnit, IsoFields}
import java.util.{Locale, TimeZone}
import java.util.concurrent.TimeUnit._

import scala.util.control.NonFatal

import sun.util.calendar.ZoneInfo

import org.apache.spark.sql.catalyst.util.DateTimeConstants._
import org.apache.spark.sql.catalyst.util.RebaseDateTime._
import org.apache.spark.sql.types.Decimal
import org.apache.spark.unsafe.types.{CalendarInterval, UTF8String}

/**
 * Helper functions for converting between internal and external date and time representations.
 * Dates are exposed externally as java.sql.Date and are represented internally as the number of
 * dates since the Unix epoch (1970-01-01). Timestamps are exposed externally as java.sql.Timestamp
 * and are stored internally as longs, which are capable of storing timestamps with microsecond
 * precision.
 */
object DateTimeUtils {

  // See http://stackoverflow.com/questions/466321/convert-unix-timestamp-to-julian
  // It's 2440587.5, rounding up to be compatible with Hive.
  final val JULIAN_DAY_OF_EPOCH = 2440588

  final val TimeZoneUTC = TimeZone.getTimeZone("UTC")

  val TIMEZONE_OPTION = "timeZone"

  def getZoneId(timeZoneId: String): ZoneId = {
    // To support the (+|-)h:mm format because it was supported before Spark 3.0.
    ZoneId.of(timeZoneId.replaceFirst("(\\+|\\-)(\\d):", "$10$2:"), ZoneId.SHORT_IDS)
  }
  def getTimeZone(timeZoneId: String): TimeZone = TimeZone.getTimeZone(getZoneId(timeZoneId))

  /**
   * Converts microseconds since 1970-01-01 00:00:00Z to days since 1970-01-01 at the given zone ID.
   */
  def microsToDays(micros: Long, zoneId: ZoneId): Int = {
    localDateToDays(getLocalDateTime(micros, zoneId).toLocalDate)
  }

  /**
   * Converts days since 1970-01-01 at the given zone ID to microseconds since 1970-01-01 00:00:00Z.
   */
  def daysToMicros(days: Int, zoneId: ZoneId): Long = {
    val instant = daysToLocalDate(days).atStartOfDay(zoneId).toInstant
    instantToMicros(instant)
  }

  /**
   * Converts a local date at the default JVM time zone to the number of days since 1970-01-01
   * in the hybrid calendar (Julian + Gregorian) by discarding the time part. The resulted days are
   * rebased from the hybrid to Proleptic Gregorian calendar. The days rebasing is performed via
   * UTC time zone for simplicity because the difference between two calendars is the same in
   * any given time zone and UTC time zone.
   *
   * Note: The date is shifted by the offset of the default JVM time zone for backward compatibility
   *       with Spark 2.4 and earlier versions. The goal of the shift is to get a local date derived
   *       from the number of days that has the same date fields (year, month, day) as the original
   *       `date` at the default JVM time zone.
   *
   * @param date It represents a specific instant in time based on the hybrid calendar which
   *             combines Julian and Gregorian calendars.
   * @return The number of days since the epoch in Proleptic Gregorian calendar.
   */
  def fromJavaDate(date: Date): Int = {
    val millisUtc = date.getTime
    val millisLocal = millisUtc + TimeZone.getDefault.getOffset(millisUtc)
    val julianDays = Math.toIntExact(Math.floorDiv(millisLocal, MILLIS_PER_DAY))
    rebaseJulianToGregorianDays(julianDays)
  }

  /**
   * Converts days since the epoch 1970-01-01 in Proleptic Gregorian calendar to a local date
   * at the default JVM time zone in the hybrid calendar (Julian + Gregorian). It rebases the given
   * days from Proleptic Gregorian to the hybrid calendar at UTC time zone for simplicity because
   * the difference between two calendars doesn't depend on any time zone. The result is shifted
   * by the time zone offset in wall clock to have the same date fields (year, month, day)
   * at the default JVM time zone as the input `daysSinceEpoch` in Proleptic Gregorian calendar.
   *
   * Note: The date is shifted by the offset of the default JVM time zone for backward compatibility
   *       with Spark 2.4 and earlier versions.
   *
   * @param days The number of days since 1970-01-01 in Proleptic Gregorian calendar.
   * @return A local date in the hybrid calendar as `java.sql.Date` from number of days since epoch.
   */
  def toJavaDate(days: Int): Date = {
    val rebasedDays = rebaseGregorianToJulianDays(days)
    val localMillis = Math.multiplyExact(rebasedDays, MILLIS_PER_DAY)
    val timeZoneOffset = TimeZone.getDefault match {
      case zoneInfo: ZoneInfo => zoneInfo.getOffsetsByWall(localMillis, null)
      case timeZone: TimeZone => timeZone.getOffset(localMillis - timeZone.getRawOffset)
    }
    new Date(localMillis - timeZoneOffset)
  }

  /**
   * Converts microseconds since the epoch to an instance of `java.sql.Timestamp`
   * via creating a local timestamp at the system time zone in Proleptic Gregorian
   * calendar, extracting date and time fields like `year` and `hours`, and forming
   * new timestamp in the hybrid calendar from the extracted fields.
   *
   * The conversion is based on the JVM system time zone because the `java.sql.Timestamp`
   * uses the time zone internally.
   *
   * The method performs the conversion via local timestamp fields to have the same date-time
   * representation as `year`, `month`, `day`, ..., `seconds` in the original calendar
   * and in the target calendar.
   *
   * @param micros The number of microseconds since 1970-01-01T00:00:00.000000Z.
   * @return A `java.sql.Timestamp` from number of micros since epoch.
   */
  def toJavaTimestamp(micros: Long): Timestamp = {
    val rebasedMicros = rebaseGregorianToJulianMicros(micros)
    val seconds = Math.floorDiv(rebasedMicros, MICROS_PER_SECOND)
    val ts = new Timestamp(seconds * MILLIS_PER_SECOND)
    val nanos = (rebasedMicros - seconds * MICROS_PER_SECOND) * NANOS_PER_MICROS
    ts.setNanos(nanos.toInt)
    ts
  }

  /**
   * Converts an instance of `java.sql.Timestamp` to the number of microseconds since
   * 1970-01-01T00:00:00.000000Z. It extracts date-time fields from the input, builds
   * a local timestamp in Proleptic Gregorian calendar from the fields, and binds
   * the timestamp to the system time zone. The resulted instant is converted to
   * microseconds since the epoch.
   *
   * The conversion is performed via the system time zone because it is used internally
   * in `java.sql.Timestamp` while extracting date-time fields.
   *
   * The goal of the function is to have the same local date-time in the original calendar
   * - the hybrid calendar (Julian + Gregorian) and in the target calendar which is
   * Proleptic Gregorian calendar, see SPARK-26651.
   *
   * @param t It represents a specific instant in time based on
   *          the hybrid calendar which combines Julian and
   *          Gregorian calendars.
   * @return The number of micros since epoch from `java.sql.Timestamp`.
   */
  def fromJavaTimestamp(t: Timestamp): Long = {
    val micros = millisToMicros(t.getTime) + (t.getNanos / NANOS_PER_MICROS) % MICROS_PER_MILLIS
    rebaseJulianToGregorianMicros(micros)
  }

  /**
   * Returns the number of microseconds since epoch from Julian day and nanoseconds in a day.
   */
  def fromJulianDay(days: Int, nanos: Long): Long = {
    // use Long to avoid rounding errors
    (days - JULIAN_DAY_OF_EPOCH).toLong * MICROS_PER_DAY + nanos / NANOS_PER_MICROS
  }

  /**
   * Returns Julian day and nanoseconds in a day from the number of microseconds
   *
   * Note: support timestamp since 4717 BC (without negative nanoseconds, compatible with Hive).
   */
  def toJulianDay(micros: Long): (Int, Long) = {
    val julianUs = micros + JULIAN_DAY_OF_EPOCH * MICROS_PER_DAY
    val days = julianUs / MICROS_PER_DAY
    val us = julianUs % MICROS_PER_DAY
    (days.toInt, MICROSECONDS.toNanos(us))
  }

  /**
   * Converts the timestamp to milliseconds since epoch. In Spark timestamp values have microseconds
   * precision, so this conversion is lossy.
   */
  def microsToMillis(micros: Long): Long = {
    // When the timestamp is negative i.e before 1970, we need to adjust the millseconds portion.
    // Example - 1965-01-01 10:11:12.123456 is represented as (-157700927876544) in micro precision.
    // In millis precision the above needs to be represented as (-157700927877).
    Math.floorDiv(micros, MICROS_PER_MILLIS)
  }

  /**
   * Converts milliseconds since the epoch to microseconds.
   */
  def millisToMicros(millis: Long): Long = {
    Math.multiplyExact(millis, MICROS_PER_MILLIS)
  }

  private final val gmtUtf8 = UTF8String.fromString("GMT")
  // The method is called by JSON/CSV parser to clean up the legacy timestamp string by removing
  // the "GMT" string. For example, it returns 2000-01-01T00:00+01:00 for 2000-01-01T00:00GMT+01:00.
  def cleanLegacyTimestampStr(s: UTF8String): UTF8String = s.replace(gmtUtf8, UTF8String.EMPTY_UTF8)

  /**
   * Trims and parses a given UTF8 timestamp string to the corresponding a corresponding [[Long]]
   * value. The return type is [[Option]] in order to distinguish between 0L and null. The following
   * formats are allowed:
   *
   * `yyyy`
   * `yyyy-[m]m`
   * `yyyy-[m]m-[d]d`
   * `yyyy-[m]m-[d]d `
   * `yyyy-[m]m-[d]d [h]h:[m]m:[s]s.[ms][ms][ms][us][us][us][zone_id]`
   * `yyyy-[m]m-[d]dT[h]h:[m]m:[s]s.[ms][ms][ms][us][us][us][zone_id]`
   * `[h]h:[m]m:[s]s.[ms][ms][ms][us][us][us][zone_id]`
   * `T[h]h:[m]m:[s]s.[ms][ms][ms][us][us][us][zone_id]`
   *
   * where `zone_id` should have one of the forms:
   *   - Z - Zulu time zone UTC+0
   *   - +|-[h]h:[m]m
   *   - A short id, see https://docs.oracle.com/javase/8/docs/api/java/time/ZoneId.html#SHORT_IDS
   *   - An id with one of the prefixes UTC+, UTC-, GMT+, GMT-, UT+ or UT-,
   *     and a suffix in the formats:
   *     - +|-h[h]
   *     - +|-hh[:]mm
   *     - +|-hh:mm:ss
   *     - +|-hhmmss
   *  - Region-based zone IDs in the form `area/city`, such as `Europe/Paris`
   */
  def stringToTimestamp(s: UTF8String, timeZoneId: ZoneId): Option[Long] = {
    if (s == null) {
      return None
    }
    var tz: Option[String] = None
    val segments: Array[Int] = Array[Int](1, 1, 1, 0, 0, 0, 0, 0, 0)
    var i = 0
    var currentSegmentValue = 0
    val bytes = s.trimAll().getBytes
    val specialTimestamp = convertSpecialTimestamp(bytes, timeZoneId)
    if (specialTimestamp.isDefined) return specialTimestamp
    var j = 0
    var digitsMilli = 0
    var justTime = false
    while (j < bytes.length) {
      val b = bytes(j)
      val parsedValue = b - '0'.toByte
      if (parsedValue < 0 || parsedValue > 9) {
        if (j == 0 && b == 'T') {
          justTime = true
          i += 3
        } else if (i < 2) {
          if (b == '-') {
            if (i == 0 && j != 4) {
              // year should have exact four digits
              return None
            }
            segments(i) = currentSegmentValue
            currentSegmentValue = 0
            i += 1
          } else if (i == 0 && b == ':') {
            justTime = true
            segments(3) = currentSegmentValue
            currentSegmentValue = 0
            i = 4
          } else {
            return None
          }
        } else if (i == 2) {
          if (b == ' ' || b == 'T') {
            segments(i) = currentSegmentValue
            currentSegmentValue = 0
            i += 1
          } else {
            return None
          }
        } else if (i == 3 || i == 4) {
          if (b == ':') {
            segments(i) = currentSegmentValue
            currentSegmentValue = 0
            i += 1
          } else {
            return None
          }
        } else if (i == 5 || i == 6) {
          if (b == '-' || b == '+') {
            segments(i) = currentSegmentValue
            currentSegmentValue = 0
            i += 1
            tz = Some(new String(bytes, j, 1))
          } else if (b == '.' && i == 5) {
            segments(i) = currentSegmentValue
            currentSegmentValue = 0
            i += 1
          } else {
            segments(i) = currentSegmentValue
            currentSegmentValue = 0
            i += 1
            tz = Some(new String(bytes, j, bytes.length - j))
            j = bytes.length - 1
          }
          if (i == 6  && b != '.') {
            i += 1
          }
        } else {
          if (i < segments.length && (b == ':' || b == ' ')) {
            segments(i) = currentSegmentValue
            currentSegmentValue = 0
            i += 1
          } else {
            return None
          }
        }
      } else {
        if (i == 6) {
          digitsMilli += 1
        }
        currentSegmentValue = currentSegmentValue * 10 + parsedValue
      }
      j += 1
    }

    segments(i) = currentSegmentValue
    if (!justTime && i == 0 && j != 4) {
      // year should have exact four digits
      return None
    }

    while (digitsMilli < 6) {
      segments(6) *= 10
      digitsMilli += 1
    }

    // We are truncating the nanosecond part, which results in loss of precision
    while (digitsMilli > 6) {
      segments(6) /= 10
      digitsMilli -= 1
    }
    try {
      val zoneId = tz match {
        case None => timeZoneId
        case Some("+") => ZoneOffset.ofHoursMinutes(segments(7), segments(8))
        case Some("-") => ZoneOffset.ofHoursMinutes(-segments(7), -segments(8))
        case Some(zoneName: String) => getZoneId(zoneName.trim)
      }
      val nanoseconds = MICROSECONDS.toNanos(segments(6))
      val localTime = LocalTime.of(segments(3), segments(4), segments(5), nanoseconds.toInt)
      val localDate = if (justTime) {
        LocalDate.now(zoneId)
      } else {
        LocalDate.of(segments(0), segments(1), segments(2))
      }
      val localDateTime = LocalDateTime.of(localDate, localTime)
      val zonedDateTime = ZonedDateTime.of(localDateTime, zoneId)
      val instant = Instant.from(zonedDateTime)
      Some(instantToMicros(instant))
    } catch {
      case NonFatal(_) => None
    }
  }

  def stringToTimestampAnsi(s: UTF8String, timeZoneId: ZoneId): Long = {
    val timestamp = stringToTimestamp(s, timeZoneId)
    if (timestamp.isEmpty) {
      throw new DateTimeException(s"Cannot cast $s to TimestampType.")
    } else {
      timestamp.get
    }
  }

  /**
   * Gets the number of microseconds since the epoch of 1970-01-01 00:00:00Z from the given
   * instance of `java.time.Instant`. The epoch microsecond count is a simple incrementing count of
   * microseconds where microsecond 0 is 1970-01-01 00:00:00Z.
   */
  def instantToMicros(instant: Instant): Long = {
    val us = Math.multiplyExact(instant.getEpochSecond, MICROS_PER_SECOND)
    val result = Math.addExact(us, NANOSECONDS.toMicros(instant.getNano))
    result
  }

  /**
   * Obtains an instance of `java.time.Instant` using microseconds from
   * the epoch of 1970-01-01 00:00:00Z.
   */
  def microsToInstant(micros: Long): Instant = {
    val secs = Math.floorDiv(micros, MICROS_PER_SECOND)
    // Unfolded Math.floorMod(us, MICROS_PER_SECOND) to reuse the result of
    // the above calculation of `secs` via `floorDiv`.
    val mos = micros - secs * MICROS_PER_SECOND
    Instant.ofEpochSecond(secs, mos * NANOS_PER_MICROS)
  }

  /**
   * Converts the local date to the number of days since 1970-01-01.
   */
  def localDateToDays(localDate: LocalDate): Int = Math.toIntExact(localDate.toEpochDay)

  /**
   * Obtains an instance of `java.time.LocalDate` from the epoch day count.
   */
  def daysToLocalDate(days: Int): LocalDate = LocalDate.ofEpochDay(days)

  /**
   * Trims and parses a given UTF8 date string to a corresponding [[Int]] value.
   * The return type is [[Option]] in order to distinguish between 0 and null. The following
   * formats are allowed:
   *
   * `yyyy`
   * `yyyy-[m]m`
   * `yyyy-[m]m-[d]d`
   * `yyyy-[m]m-[d]d `
   * `yyyy-[m]m-[d]d *`
   * `yyyy-[m]m-[d]dT*`
   */
  def stringToDate(s: UTF8String, zoneId: ZoneId): Option[Int] = {
    if (s == null) {
      return None
    }
    val segments: Array[Int] = Array[Int](1, 1, 1)
    var i = 0
    var currentSegmentValue = 0
    val bytes = s.trimAll().getBytes
    val specialDate = convertSpecialDate(bytes, zoneId)
    if (specialDate.isDefined) return specialDate
    var j = 0
    while (j < bytes.length && (i < 3 && !(bytes(j) == ' ' || bytes(j) == 'T'))) {
      val b = bytes(j)
      if (i < 2 && b == '-') {
        if (i == 0 && j != 4) {
          // year should have exact four digits
          return None
        }
        segments(i) = currentSegmentValue
        currentSegmentValue = 0
        i += 1
      } else {
        val parsedValue = b - '0'.toByte
        if (parsedValue < 0 || parsedValue > 9) {
          return None
        } else {
          currentSegmentValue = currentSegmentValue * 10 + parsedValue
        }
      }
      j += 1
    }
    if (i == 0 && j != 4) {
      // year should have exact four digits
      return None
    }
    if (i < 2 && j < bytes.length) {
      // For the `yyyy` and `yyyy-[m]m` formats, entire input must be consumed.
      return None
    }
    segments(i) = currentSegmentValue
    try {
      val localDate = LocalDate.of(segments(0), segments(1), segments(2))
      Some(localDateToDays(localDate))
    } catch {
      case NonFatal(_) => None
    }
  }

  // Gets the local date-time parts (year, month, day and time) of the instant expressed as the
  // number of microseconds since the epoch at the given time zone ID.
  private def getLocalDateTime(micros: Long, zoneId: ZoneId): LocalDateTime = {
    microsToInstant(micros).atZone(zoneId).toLocalDateTime
  }

  /**
   * Returns the hour value of a given timestamp value. The timestamp is expressed in microseconds.
   */
  def getHours(micros: Long, zoneId: ZoneId): Int = {
    getLocalDateTime(micros, zoneId).getHour
  }

  /**
   * Returns the minute value of a given timestamp value. The timestamp is expressed in
   * microseconds since the epoch.
   */
  def getMinutes(micros: Long, zoneId: ZoneId): Int = {
    getLocalDateTime(micros, zoneId).getMinute
  }

  /**
   * Returns the second value of a given timestamp value. The timestamp is expressed in
   * microseconds since the epoch.
   */
  def getSeconds(micros: Long, zoneId: ZoneId): Int = {
    getLocalDateTime(micros, zoneId).getSecond
  }

  /**
   * Returns the seconds part and its fractional part with microseconds.
   */
  def getSecondsWithFraction(micros: Long, zoneId: ZoneId): Decimal = {
    Decimal(getMicroseconds(micros, zoneId), 8, 6)
  }

  /**
   * Returns local seconds, including fractional parts, multiplied by 1000000.
   *
   * @param micros The number of microseconds since the epoch.
   * @param zoneId The time zone id which milliseconds should be obtained in.
   */
  def getMicroseconds(micros: Long, zoneId: ZoneId): Int = {
    val lt = getLocalDateTime(micros, zoneId)
    (lt.getLong(ChronoField.MICRO_OF_SECOND) + lt.getSecond * MICROS_PER_SECOND).toInt
  }

  /**
   * Returns the 'day in year' value for the given number of days since 1970-01-01.
   */
  def getDayInYear(days: Int): Int = daysToLocalDate(days).getDayOfYear

  /**
   * Returns the year value for the given number of days since 1970-01-01.
   */
  def getYear(days: Int): Int = daysToLocalDate(days).getYear

  /**
   * Returns the year which conforms to ISO 8601. Each ISO 8601 week-numbering
   * year begins with the Monday of the week containing the 4th of January.
   */
  def getWeekBasedYear(days: Int): Int = daysToLocalDate(days).get(IsoFields.WEEK_BASED_YEAR)

  /** Returns the quarter for the given number of days since 1970-01-01. */
  def getQuarter(days: Int): Int = daysToLocalDate(days).get(IsoFields.QUARTER_OF_YEAR)

  /**
   * Returns the month value for the given number of days since 1970-01-01.
   * January is month 1.
   */
  def getMonth(days: Int): Int = daysToLocalDate(days).getMonthValue

  /**
   * Returns the 'day of month' value for the given number of days since 1970-01-01.
   */
  def getDayOfMonth(days: Int): Int = daysToLocalDate(days).getDayOfMonth

  /**
   * Returns the day of the week for the given number of days since 1970-01-01
   * (1 = Sunday, 2 = Monday, ..., 7 = Saturday).
   */
  def getDayOfWeek(days: Int): Int = LocalDate.ofEpochDay(days).getDayOfWeek.plus(1).getValue

  /**
   * Returns the day of the week for the given number of days since 1970-01-01
   * (0 = Monday, 1 = Tuesday, ..., 6 = Sunday).
   */
  def getWeekDay(days: Int): Int = LocalDate.ofEpochDay(days).getDayOfWeek.ordinal()

  /**
   * Returns the week of the year of the given date expressed as the number of days from 1970-01-01.
   * A week is considered to start on a Monday and week 1 is the first week with > 3 days.
   */
  def getWeekOfYear(days: Int): Int = {
    LocalDate.ofEpochDay(days).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
  }

  /**
   * Adds an year-month interval to a date represented as days since 1970-01-01.
   * @return a date value, expressed in days since 1970-01-01.
   */
  def dateAddMonths(days: Int, months: Int): Int = {
    localDateToDays(daysToLocalDate(days).plusMonths(months))
  }

  /**
   * Adds a full interval (months, days, microseconds) a timestamp represented as the number of
   * microseconds since 1970-01-01 00:00:00Z.
   * @return A timestamp value, expressed in microseconds since 1970-01-01 00:00:00Z.
   */
  def timestampAddInterval(
      start: Long,
      months: Int,
      days: Int,
      microseconds: Long,
      zoneId: ZoneId): Long = {
    val resultTimestamp = microsToInstant(start)
      .atZone(zoneId)
      .plusMonths(months)
      .plusDays(days)
      .plus(microseconds, ChronoUnit.MICROS)
    instantToMicros(resultTimestamp.toInstant)
  }

  /**
   * Adds the interval's months and days to a date expressed as days since the epoch.
   * @return A date value, expressed in days since 1970-01-01.
   *
   * @throws DateTimeException if the result exceeds the supported date range
   * @throws IllegalArgumentException if the interval has `microseconds` part
   */
  def dateAddInterval(
     start: Int,
     interval: CalendarInterval): Int = {
    require(interval.microseconds == 0,
      "Cannot add hours, minutes or seconds, milliseconds, microseconds to a date")
    val ld = daysToLocalDate(start).plusMonths(interval.months).plusDays(interval.days)
    localDateToDays(ld)
  }

  /**
   * Splits date (expressed in days since 1970-01-01) into four fields:
   * year, month (Jan is Month 1), dayInMonth, daysToMonthEnd (0 if it's last day of month).
   */
  private def splitDate(days: Int): (Int, Int, Int, Int) = {
    val ld = daysToLocalDate(days)
    (ld.getYear, ld.getMonthValue, ld.getDayOfMonth, ld.lengthOfMonth() - ld.getDayOfMonth)
  }

  /**
   * Returns number of months between micros1 and micros2. micros1 and micros2 are expressed in
   * microseconds since 1970-01-01. If micros1 is later than micros2, the result is positive.
   *
   * If micros1 and micros2 are on the same day of month, or both are the last day of month,
   * returns, time of day will be ignored.
   *
   * Otherwise, the difference is calculated based on 31 days per month.
   * The result is rounded to 8 decimal places if `roundOff` is set to true.
   */
  def monthsBetween(
      micros1: Long,
      micros2: Long,
      roundOff: Boolean,
      zoneId: ZoneId): Double = {
    val date1 = microsToDays(micros1, zoneId)
    val date2 = microsToDays(micros2, zoneId)
    val (year1, monthInYear1, dayInMonth1, daysToMonthEnd1) = splitDate(date1)
    val (year2, monthInYear2, dayInMonth2, daysToMonthEnd2) = splitDate(date2)

    val months1 = year1 * 12 + monthInYear1
    val months2 = year2 * 12 + monthInYear2

    val monthDiff = (months1 - months2).toDouble

    if (dayInMonth1 == dayInMonth2 || ((daysToMonthEnd1 == 0) && (daysToMonthEnd2 == 0))) {
      return monthDiff
    }
    // using milliseconds can cause precision loss with more than 8 digits
    // we follow Hive's implementation which uses seconds
    val secondsInDay1 = MICROSECONDS.toSeconds(micros1 - daysToMicros(date1, zoneId))
    val secondsInDay2 = MICROSECONDS.toSeconds(micros2 - daysToMicros(date2, zoneId))
    val secondsDiff = (dayInMonth1 - dayInMonth2) * SECONDS_PER_DAY + secondsInDay1 - secondsInDay2
    val secondsInMonth = DAYS.toSeconds(31)
    val diff = monthDiff + secondsDiff / secondsInMonth.toDouble
    if (roundOff) {
      // rounding to 8 digits
      math.round(diff * 1e8) / 1e8
    } else {
      diff
    }
  }

  // Thursday = 0 since 1970/Jan/01 => Thursday
  private val SUNDAY = 3
  private val MONDAY = 4
  private val TUESDAY = 5
  private val WEDNESDAY = 6
  private val THURSDAY = 0
  private val FRIDAY = 1
  private val SATURDAY = 2

  /*
   * Returns day of week from String. Starting from Thursday, marked as 0.
   * (Because 1970-01-01 is Thursday).
   */
  def getDayOfWeekFromString(string: UTF8String): Int = {
    val dowString = string.toString.toUpperCase(Locale.ROOT)
    dowString match {
      case "SU" | "SUN" | "SUNDAY" => SUNDAY
      case "MO" | "MON" | "MONDAY" => MONDAY
      case "TU" | "TUE" | "TUESDAY" => TUESDAY
      case "WE" | "WED" | "WEDNESDAY" => WEDNESDAY
      case "TH" | "THU" | "THURSDAY" => THURSDAY
      case "FR" | "FRI" | "FRIDAY" => FRIDAY
      case "SA" | "SAT" | "SATURDAY" => SATURDAY
      case _ => -1
    }
  }

  /**
   * Returns the first date which is later than startDate and is of the given dayOfWeek.
   * dayOfWeek is an integer ranges in [0, 6], and 0 is Thu, 1 is Fri, etc,.
   */
  def getNextDateForDayOfWeek(startDay: Int, dayOfWeek: Int): Int = {
    startDay + 1 + ((dayOfWeek - 1 - startDay) % 7 + 7) % 7
  }

  /** Returns last day of the month for the given number of days since 1970-01-01. */
  def getLastDayOfMonth(days: Int): Int = {
    val localDate = daysToLocalDate(days)
    (days - localDate.getDayOfMonth) + localDate.lengthOfMonth()
  }

  // The constants are visible for testing purpose only.
  private[sql] val TRUNC_INVALID = -1
  // The levels from TRUNC_TO_MICROSECOND to TRUNC_TO_DAY are used in truncations
  // of TIMESTAMP values only.
  private[sql] val TRUNC_TO_MICROSECOND = 0
  private[sql] val MIN_LEVEL_OF_TIMESTAMP_TRUNC = TRUNC_TO_MICROSECOND
  private[sql] val TRUNC_TO_MILLISECOND = 1
  private[sql] val TRUNC_TO_SECOND = 2
  private[sql] val TRUNC_TO_MINUTE = 3
  private[sql] val TRUNC_TO_HOUR = 4
  private[sql] val TRUNC_TO_DAY = 5
  // The levels from TRUNC_TO_WEEK to TRUNC_TO_YEAR are used in truncations
  // of DATE and TIMESTAMP values.
  private[sql] val TRUNC_TO_WEEK = 6
  private[sql] val MIN_LEVEL_OF_DATE_TRUNC = TRUNC_TO_WEEK
  private[sql] val TRUNC_TO_MONTH = 7
  private[sql] val TRUNC_TO_QUARTER = 8
  private[sql] val TRUNC_TO_YEAR = 9

  /**
   * Returns the trunc date from original date and trunc level.
   * Trunc level should be generated using `parseTruncLevel()`, should be between 6 and 9.
   */
  def truncDate(days: Int, level: Int): Int = {
    level match {
      case TRUNC_TO_WEEK => getNextDateForDayOfWeek(days - 7, MONDAY)
      case TRUNC_TO_MONTH => days - getDayOfMonth(days) + 1
      case TRUNC_TO_QUARTER =>
        localDateToDays(daysToLocalDate(days).`with`(IsoFields.DAY_OF_QUARTER, 1L))
      case TRUNC_TO_YEAR => days - getDayInYear(days) + 1
      case _ =>
        // caller make sure that this should never be reached
        sys.error(s"Invalid trunc level: $level")
    }
  }

  private def truncToUnit(micros: Long, zoneId: ZoneId, unit: ChronoUnit): Long = {
    val truncated = microsToInstant(micros).atZone(zoneId).truncatedTo(unit)
    instantToMicros(truncated.toInstant)
  }

  /**
   * Returns the trunc date time from original date time and trunc level.
   * Trunc level should be generated using `parseTruncLevel()`, should be between 0 and 9.
   */
  def truncTimestamp(micros: Long, level: Int, zoneId: ZoneId): Long = {
    // Time zone offsets have a maximum precision of seconds (see `java.time.ZoneOffset`). Hence
    // truncation to microsecond, millisecond, and second can be done
    // without using time zone information. This results in a performance improvement.
    level match {
      case TRUNC_TO_MICROSECOND => micros
      case TRUNC_TO_MILLISECOND =>
        micros - Math.floorMod(micros, MICROS_PER_MILLIS)
      case TRUNC_TO_SECOND =>
        micros - Math.floorMod(micros, MICROS_PER_SECOND)
      case TRUNC_TO_MINUTE => truncToUnit(micros, zoneId, ChronoUnit.MINUTES)
      case TRUNC_TO_HOUR => truncToUnit(micros, zoneId, ChronoUnit.HOURS)
      case TRUNC_TO_DAY => truncToUnit(micros, zoneId, ChronoUnit.DAYS)
      case _ => // Try to truncate date levels
        val dDays = microsToDays(micros, zoneId)
        daysToMicros(truncDate(dDays, level), zoneId)
    }
  }

  /**
   * Returns the truncate level, could be from TRUNC_TO_MICROSECOND to TRUNC_TO_YEAR,
   * or TRUNC_INVALID, TRUNC_INVALID means unsupported truncate level.
   */
  def parseTruncLevel(format: UTF8String): Int = {
    if (format == null) {
      TRUNC_INVALID
    } else {
      format.toString.toUpperCase(Locale.ROOT) match {
        case "MICROSECOND" => TRUNC_TO_MICROSECOND
        case "MILLISECOND" => TRUNC_TO_MILLISECOND
        case "SECOND" => TRUNC_TO_SECOND
        case "MINUTE" => TRUNC_TO_MINUTE
        case "HOUR" => TRUNC_TO_HOUR
        case "DAY" | "DD" => TRUNC_TO_DAY
        case "WEEK" => TRUNC_TO_WEEK
        case "MON" | "MONTH" | "MM" => TRUNC_TO_MONTH
        case "QUARTER" => TRUNC_TO_QUARTER
        case "YEAR" | "YYYY" | "YY" => TRUNC_TO_YEAR
        case _ => TRUNC_INVALID
      }
    }
  }

  /**
   * Converts the timestamp `micros` from one timezone to another.
   *
   * Time-zone rules, such as daylight savings, mean that not every local date-time
   * is valid for the `toZone` time zone, thus the local date-time may be adjusted.
   */
  def convertTz(micros: Long, fromZone: ZoneId, toZone: ZoneId): Long = {
    val rebasedDateTime = getLocalDateTime(micros, toZone).atZone(fromZone)
    instantToMicros(rebasedDateTime.toInstant)
  }

  /**
   * Returns a timestamp of given timezone from UTC timestamp, with the same string
   * representation in their timezone.
   */
  def fromUTCTime(micros: Long, timeZone: String): Long = {
    convertTz(micros, ZoneOffset.UTC, getZoneId(timeZone))
  }

  /**
   * Returns a utc timestamp from a given timestamp from a given timezone, with the same
   * string representation in their timezone.
   */
  def toUTCTime(micros: Long, timeZone: String): Long = {
    convertTz(micros, getZoneId(timeZone), ZoneOffset.UTC)
  }

  /**
   * Obtains the current instant as microseconds since the epoch at the UTC time zone.
   */
  def currentTimestamp(): Long = instantToMicros(Instant.now())

  /**
   * Obtains the current date as days since the epoch in the specified time-zone.
   */
  def currentDate(zoneId: ZoneId): Int = localDateToDays(LocalDate.now(zoneId))

  private def today(zoneId: ZoneId): ZonedDateTime = {
    Instant.now().atZone(zoneId).`with`(LocalTime.MIDNIGHT)
  }

  private val specialValueRe = """(\p{Alpha}+)\p{Blank}*(.*)""".r

  /**
   * Extracts special values from an input string ignoring case.
   *
   * @param input A trimmed string
   * @param zoneId Zone identifier used to get the current date.
   * @return Some special value in lower case or None.
   */
  private def extractSpecialValue(input: String, zoneId: ZoneId): Option[String] = {
    def isValid(value: String, timeZoneId: String): Boolean = {
      // Special value can be without any time zone
      if (timeZoneId.isEmpty) return true
      // "now" must not have the time zone field
      if (value.compareToIgnoreCase("now") == 0) return false
      // If the time zone field presents in the input, it must be resolvable
      try {
        getZoneId(timeZoneId)
        true
      } catch {
        case NonFatal(_) => false
      }
    }

    assert(input.trim.length == input.length)
    if (input.length < 3 || !input(0).isLetter) return None
    input match {
      case specialValueRe(v, z) if isValid(v, z) => Some(v.toLowerCase(Locale.US))
      case _ => None
    }
  }

  /**
   * Converts notational shorthands that are converted to ordinary timestamps.
   *
   * @param input A trimmed string
   * @param zoneId Zone identifier used to get the current date.
   * @return Some of microseconds since the epoch if the conversion completed
   *         successfully otherwise None.
   */
  def convertSpecialTimestamp(input: String, zoneId: ZoneId): Option[Long] = {
    extractSpecialValue(input, zoneId).flatMap {
      case "epoch" => Some(0)
      case "now" => Some(currentTimestamp())
      case "today" => Some(instantToMicros(today(zoneId).toInstant))
      case "tomorrow" => Some(instantToMicros(today(zoneId).plusDays(1).toInstant))
      case "yesterday" => Some(instantToMicros(today(zoneId).minusDays(1).toInstant))
      case _ => None
    }
  }

  private def convertSpecialTimestamp(bytes: Array[Byte], zoneId: ZoneId): Option[Long] = {
    if (bytes.length > 0 && Character.isAlphabetic(bytes(0))) {
      convertSpecialTimestamp(new String(bytes, StandardCharsets.UTF_8), zoneId)
    } else {
      None
    }
  }

  /**
   * Converts notational shorthands that are converted to ordinary dates.
   *
   * @param input A trimmed string
   * @param zoneId Zone identifier used to get the current date.
   * @return Some of days since the epoch if the conversion completed successfully otherwise None.
   */
  def convertSpecialDate(input: String, zoneId: ZoneId): Option[Int] = {
    extractSpecialValue(input, zoneId).flatMap {
      case "epoch" => Some(0)
      case "now" | "today" => Some(currentDate(zoneId))
      case "tomorrow" => Some(Math.addExact(currentDate(zoneId), 1))
      case "yesterday" => Some(Math.subtractExact(currentDate(zoneId), 1))
      case _ => None
    }
  }

  private def convertSpecialDate(bytes: Array[Byte], zoneId: ZoneId): Option[Int] = {
    if (bytes.length > 0 && Character.isAlphabetic(bytes(0))) {
      convertSpecialDate(new String(bytes, StandardCharsets.UTF_8), zoneId)
    } else {
      None
    }
  }

  /**
   * Subtracts two dates expressed as days since 1970-01-01.
   *
   * @param endDay The end date, exclusive
   * @param startDay The start date, inclusive
   * @return An interval between two dates. The interval can be negative
   *         if the end date is before the start date.
   */
  def subtractDates(endDay: Int, startDay: Int): CalendarInterval = {
    val period = Period.between(daysToLocalDate(startDay), daysToLocalDate(endDay))
    val months = Math.toIntExact(period.toTotalMonths)
    val days = period.getDays
    new CalendarInterval(months, days, 0)
  }
}
