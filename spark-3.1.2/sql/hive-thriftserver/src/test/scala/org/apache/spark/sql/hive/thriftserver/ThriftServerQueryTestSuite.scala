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

package org.apache.spark.sql.hive.thriftserver

import java.io.File
import java.sql.{SQLException, Statement, Timestamp}
import java.util.{Locale, MissingFormatArgumentException}

import scala.util.control.NonFatal

import org.apache.commons.lang3.exception.ExceptionUtils

import org.apache.spark.SparkException
import org.apache.spark.sql.SQLQueryTestSuite
import org.apache.spark.sql.catalyst.analysis.NoSuchTableException
import org.apache.spark.sql.catalyst.util.fileToString
import org.apache.spark.sql.execution.HiveResult.{getTimeFormatters, toHiveString, TimeFormatters}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._

/**
 * Re-run all the tests in SQLQueryTestSuite via Thrift Server.
 *
 * To run the entire test suite:
 * {{{
 *   build/sbt "hive-thriftserver/testOnly *ThriftServerQueryTestSuite" -Phive-thriftserver
 * }}}
 *
 * This test suite won't generate golden files. To re-generate golden files for entire suite, run:
 * {{{
 *   SPARK_GENERATE_GOLDEN_FILES=1 build/sbt "sql/testOnly *SQLQueryTestSuite"
 * }}}
 *
 * TODO:
 *   1. Support UDF testing.
 *   2. Support DESC command.
 *   3. Support SHOW command.
 */
class ThriftServerQueryTestSuite extends SQLQueryTestSuite with SharedThriftServer {


  override def mode: ServerMode.Value = ServerMode.binary

  override protected def testFile(fileName: String): String = {
    copyAndGetResourceFile(fileName, ".data").getAbsolutePath
  }

  /** List of test cases to ignore, in lower cases. */
  override def ignoreList: Set[String] = super.ignoreList ++ Set(
    // Missing UDF
    "postgreSQL/boolean.sql",
    "postgreSQL/case.sql",
    // SPARK-28624
    "date.sql",
    // SPARK-28620
    "postgreSQL/float4.sql",
    // SPARK-28636
    "decimalArithmeticOperations.sql",
    "literals.sql",
    "subquery/scalar-subquery/scalar-subquery-predicate.sql",
    "subquery/in-subquery/in-limit.sql",
    "subquery/in-subquery/in-group-by.sql",
    "subquery/in-subquery/simple-in.sql",
    "subquery/in-subquery/in-order-by.sql",
    "subquery/in-subquery/in-set-operations.sql"
  )

  override def runQueries(
      queries: Seq[String],
      testCase: TestCase,
      configSet: Seq[(String, String)]): Unit = {
    // We do not test with configSet.
    withJdbcStatement { statement =>

      configSet.foreach { case (k, v) =>
        statement.execute(s"SET $k = $v")
      }

      testCase match {
        case _: PgSQLTest | _: AnsiTest =>
          statement.execute(s"SET ${SQLConf.ANSI_ENABLED.key} = true")
        case _ =>
          statement.execute(s"SET ${SQLConf.ANSI_ENABLED.key} = false")
      }

      // Run the SQL queries preparing them for comparison.
      val outputs: Seq[QueryOutput] = queries.map { sql =>
        val (_, output) = handleExceptions(getNormalizedResult(statement, sql))
        // We might need to do some query canonicalization in the future.
        QueryOutput(
          sql = sql,
          schema = "",
          output = output.mkString("\n").replaceAll("\\s+$", ""))
      }

      // Read back the golden file.
      val expectedOutputs: Seq[QueryOutput] = {
        val goldenOutput = fileToString(new File(testCase.resultFile))
        val segments = goldenOutput.split("-- !query.*\n")

        // each query has 3 segments, plus the header
        assert(segments.size == outputs.size * 3 + 1,
          s"Expected ${outputs.size * 3 + 1} blocks in result file but got ${segments.size}. " +
            "Try regenerate the result files.")
        Seq.tabulate(outputs.size) { i =>
          val sql = segments(i * 3 + 1).trim
          val schema = segments(i * 3 + 2).trim
          val originalOut = segments(i * 3 + 3)
          val output = if (schema != emptySchema && isNeedSort(sql)) {
            originalOut.split("\n").sorted.mkString("\n")
          } else {
            originalOut
          }
          QueryOutput(
            sql = sql,
            schema = "",
            output = output.replaceAll("\\s+$", "")
          )
        }
      }

      // Compare results.
      assertResult(expectedOutputs.size, s"Number of queries should be ${expectedOutputs.size}") {
        outputs.size
      }

      outputs.zip(expectedOutputs).zipWithIndex.foreach { case ((output, expected), i) =>
        assertResult(expected.sql, s"SQL query did not match for query #$i\n${expected.sql}") {
          output.sql
        }

        expected match {
          // Skip desc command, see HiveResult.hiveResultString
          case d if d.sql.toUpperCase(Locale.ROOT).startsWith("DESC ")
            || d.sql.toUpperCase(Locale.ROOT).startsWith("DESC\n")
            || d.sql.toUpperCase(Locale.ROOT).startsWith("DESCRIBE ")
            || d.sql.toUpperCase(Locale.ROOT).startsWith("DESCRIBE\n") =>

          // Skip show command, see HiveResult.hiveResultString
          case s if s.sql.toUpperCase(Locale.ROOT).startsWith("SHOW ")
            || s.sql.toUpperCase(Locale.ROOT).startsWith("SHOW\n") =>

          case _ if output.output.startsWith(classOf[NoSuchTableException].getPackage.getName) =>
            assert(expected.output.startsWith(classOf[NoSuchTableException].getPackage.getName),
              s"Exception did not match for query #$i\n${expected.sql}, " +
                s"expected: ${expected.output}, but got: ${output.output}")

          case _ if output.output.startsWith(classOf[SparkException].getName) &&
            output.output.contains("overflow") =>
            assert(expected.output.contains(classOf[ArithmeticException].getName) &&
              expected.output.contains("overflow"),
              s"Exception did not match for query #$i\n${expected.sql}, " +
                s"expected: ${expected.output}, but got: ${output.output}")

          case _ if output.output.startsWith(classOf[RuntimeException].getName) =>
            assert(expected.output.contains("Exception"),
              s"Exception did not match for query #$i\n${expected.sql}, " +
                s"expected: ${expected.output}, but got: ${output.output}")

          case _ if output.output.startsWith(classOf[ArithmeticException].getName) &&
            output.output.contains("causes overflow") =>
            assert(expected.output.contains(classOf[ArithmeticException].getName) &&
              expected.output.contains("causes overflow"),
              s"Exception did not match for query #$i\n${expected.sql}, " +
                s"expected: ${expected.output}, but got: ${output.output}")

          case _ if output.output.startsWith(classOf[MissingFormatArgumentException].getName) &&
            output.output.contains("Format specifier") =>
            assert(expected.output.contains(classOf[MissingFormatArgumentException].getName) &&
              expected.output.contains("Format specifier"),
              s"Exception did not match for query #$i\n${expected.sql}, " +
                s"expected: ${expected.output}, but got: ${output.output}")

          // SQLException should not exactly match. We only assert the result contains Exception.
          case _ if output.output.startsWith(classOf[SQLException].getName) =>
            assert(expected.output.contains("Exception"),
              s"Exception did not match for query #$i\n${expected.sql}, " +
                s"expected: ${expected.output}, but got: ${output.output}")

          case _ =>
            assertResult(expected.output, s"Result did not match for query #$i\n${expected.sql}") {
              output.output
            }
        }
      }
    }
  }

  override def createScalaTestCase(testCase: TestCase): Unit = {
    if (ignoreList.exists(t =>
      testCase.name.toLowerCase(Locale.ROOT).contains(t.toLowerCase(Locale.ROOT)))) {
      // Create a test case to ignore this case.
      ignore(testCase.name) { /* Do nothing */ }
    } else {
      // Create a test case to run this case.
      test(testCase.name) {
        runTest(testCase)
      }
    }
  }

  override lazy val listTestCases: Seq[TestCase] = {
    listFilesRecursively(new File(inputFilePath)).flatMap { file =>
      val resultFile = file.getAbsolutePath.replace(inputFilePath, goldenFilePath) + ".out"
      val absPath = file.getAbsolutePath
      val testCaseName = absPath.stripPrefix(inputFilePath).stripPrefix(File.separator)

      if (file.getAbsolutePath.startsWith(s"$inputFilePath${File.separator}udf")) {
        Seq.empty
      } else if (file.getAbsolutePath.startsWith(s"$inputFilePath${File.separator}postgreSQL")) {
        PgSQLTestCase(testCaseName, absPath, resultFile) :: Nil
      } else if (file.getAbsolutePath.startsWith(s"$inputFilePath${File.separator}ansi")) {
        AnsiTestCase(testCaseName, absPath, resultFile) :: Nil
      } else {
        RegularTestCase(testCaseName, absPath, resultFile) :: Nil
      }
    }
  }

  test("Check if ThriftServer can work") {
    withJdbcStatement { statement =>
      val rs = statement.executeQuery("select 1L")
      rs.next()
      assert(rs.getLong(1) === 1L)
    }
  }

  /** ThriftServer wraps the root exception, so it needs to be extracted. */
  override def handleExceptions(result: => (String, Seq[String])): (String, Seq[String]) = {
    super.handleExceptions {
      try {
        result
      } catch {
        case NonFatal(e) => throw ExceptionUtils.getRootCause(e)
      }
    }
  }

  private def getNormalizedResult(statement: Statement, sql: String): (String, Seq[String]) = {
    val rs = statement.executeQuery(sql)
    val cols = rs.getMetaData.getColumnCount
    val timeFormatters = getTimeFormatters
    val buildStr = () => (for (i <- 1 to cols) yield {
      getHiveResult(rs.getObject(i), timeFormatters)
    }).mkString("\t")

    val answer = Iterator.continually(rs.next()).takeWhile(identity).map(_ => buildStr()).toSeq
      .map(replaceNotIncludedMsg)
    if (isNeedSort(sql)) {
      ("", answer.sorted)
    } else {
      ("", answer)
    }
  }

  // Returns true if sql is retrieving data.
  private def isNeedSort(sql: String): Boolean = {
    val upperCase = sql.toUpperCase(Locale.ROOT)
    upperCase.startsWith("SELECT ") || upperCase.startsWith("SELECT\n") ||
      upperCase.startsWith("WITH ") || upperCase.startsWith("WITH\n") ||
      upperCase.startsWith("VALUES ") || upperCase.startsWith("VALUES\n") ||
      // postgreSQL/union.sql
      upperCase.startsWith("(")
  }

  private def getHiveResult(obj: Object, timeFormatters: TimeFormatters): String = {
    obj match {
      case null =>
        toHiveString((null, StringType), false, timeFormatters)
      case d: java.sql.Date =>
        toHiveString((d, DateType), false, timeFormatters)
      case t: Timestamp =>
        toHiveString((t, TimestampType), false, timeFormatters)
      case d: java.math.BigDecimal =>
        toHiveString((d, DecimalType.fromDecimal(Decimal(d))), false, timeFormatters)
      case bin: Array[Byte] =>
        toHiveString((bin, BinaryType), false, timeFormatters)
      case other =>
        other.toString
    }
  }
}
