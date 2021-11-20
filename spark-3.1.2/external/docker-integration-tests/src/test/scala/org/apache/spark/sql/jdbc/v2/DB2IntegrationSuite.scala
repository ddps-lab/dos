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

package org.apache.spark.sql.jdbc.v2

import java.sql.Connection

import org.scalatest.time.SpanSugar._

import org.apache.spark.SparkConf
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.execution.datasources.v2.jdbc.JDBCTableCatalog
import org.apache.spark.sql.jdbc.{DatabaseOnDocker, DockerJDBCIntegrationSuite}
import org.apache.spark.sql.types._
import org.apache.spark.tags.DockerTest

/**
 * To run this test suite for a specific version (e.g., ibmcom/db2:11.5.4.0):
 * {{{
 *   DB2_DOCKER_IMAGE_NAME=ibmcom/db2:11.5.4.0
 *     ./build/sbt -Pdocker-integration-tests "testOnly *v2.DB2IntegrationSuite"
 * }}}
 */
@DockerTest
class DB2IntegrationSuite extends DockerJDBCIntegrationSuite with V2JDBCTest {
  override val catalogName: String = "db2"
  override val db = new DatabaseOnDocker {
    override val imageName = sys.env.getOrElse("DB2_DOCKER_IMAGE_NAME", "ibmcom/db2:11.5.4.0")
    override val env = Map(
      "DB2INST1_PASSWORD" -> "rootpass",
      "LICENSE" -> "accept",
      "DBNAME" -> "foo",
      "ARCHIVE_LOGS" -> "false",
      "AUTOCONFIG" -> "false"
    )
    override val usesIpc = false
    override val jdbcPort: Int = 50000
    override val privileged = true
    override def getJdbcUrl(ip: String, port: Int): String =
      s"jdbc:db2://$ip:$port/foo:user=db2inst1;password=rootpass;retrieveMessagesFromServerOnGetMessage=true;" //scalastyle:ignore
  }

  override val connectionTimeout = timeout(3.minutes)

  override def sparkConf: SparkConf = super.sparkConf
    .set("spark.sql.catalog.db2", classOf[JDBCTableCatalog].getName)
    .set("spark.sql.catalog.db2.url", db.getJdbcUrl(dockerIp, externalPort))

  override def dataPreparation(conn: Connection): Unit = {}

  override def testUpdateColumnType(tbl: String): Unit = {
    sql(s"CREATE TABLE $tbl (ID INTEGER)")
    var t = spark.table(tbl)
    var expectedSchema = new StructType().add("ID", IntegerType)
    assert(t.schema === expectedSchema)
    sql(s"ALTER TABLE $tbl ALTER COLUMN id TYPE DOUBLE")
    t = spark.table(tbl)
    expectedSchema = new StructType().add("ID", DoubleType)
    assert(t.schema === expectedSchema)
    // Update column type from DOUBLE to STRING
    val msg1 = intercept[AnalysisException] {
      sql(s"ALTER TABLE $tbl ALTER COLUMN id TYPE VARCHAR(10)")
    }.getMessage
    assert(msg1.contains("Cannot update alt_table field ID: double cannot be cast to varchar"))
  }

  override def testCreateTableWithProperty(tbl: String): Unit = {
    sql(s"CREATE TABLE $tbl (ID INT)" +
      s" TBLPROPERTIES('CCSID'='UNICODE')")
    var t = spark.table(tbl)
    var expectedSchema = new StructType().add("ID", IntegerType)
    assert(t.schema === expectedSchema)
  }
}
