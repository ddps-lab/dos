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

import scala.collection.JavaConverters._

import org.apache.log4j.Level

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.connector.catalog.NamespaceChange
import org.apache.spark.sql.execution.datasources.v2.jdbc.JDBCTableCatalog
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.tags.DockerTest

@DockerTest
private[v2] trait V2JDBCNamespaceTest extends SharedSparkSession {
  val catalog = new JDBCTableCatalog()

  def builtinNamespaces: Array[Array[String]]

  test("listNamespaces: basic behavior") {
    catalog.createNamespace(Array("foo"), Map("comment" -> "test comment").asJava)
    assert(catalog.listNamespaces() === Array(Array("foo")) ++ builtinNamespaces)
    assert(catalog.listNamespaces(Array("foo")) === Array())
    assert(catalog.namespaceExists(Array("foo")) === true)

    val logAppender = new LogAppender("catalog comment")
    withLogAppender(logAppender) {
      catalog.alterNamespace(Array("foo"), NamespaceChange
        .setProperty("comment", "comment for foo"))
      catalog.alterNamespace(Array("foo"), NamespaceChange.removeProperty("comment"))
    }
    val createCommentWarning = logAppender.loggingEvents
      .filter(_.getLevel == Level.WARN)
      .map(_.getRenderedMessage)
      .exists(_.contains("catalog comment"))
    assert(createCommentWarning === false)

    catalog.dropNamespace(Array("foo"))
    assert(catalog.namespaceExists(Array("foo")) === false)
    assert(catalog.listNamespaces() === builtinNamespaces)
    val msg = intercept[AnalysisException] {
      catalog.listNamespaces(Array("foo"))
    }.getMessage
    assert(msg.contains("Namespace 'foo' not found"))
  }
}
