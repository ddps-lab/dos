---
layout: global
title: "Migration Guide: Spark Core"
displayTitle: "Migration Guide: Spark Core"
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

* Table of contents
{:toc}

## Upgrading from Core 3.0 to 3.1

- In Spark 3.0 and below, `SparkContext` can be created in executors. Since Spark 3.1, an exception will be thrown when creating `SparkContext` in executors. You can allow it by setting the configuration `spark.executor.allowSparkContext` when creating `SparkContext` in executors.

- In Spark 3.0 and below, Spark propagated the Hadoop classpath from `yarn.application.classpath` and `mapreduce.application.classpath` into the Spark application submitted to YARN when Spark distribution is with the built-in Hadoop. Since Spark 3.1, it does not propagate anymore when the Spark distribution is with the built-in Hadoop in order to prevent the failure from the different transitive dependencies picked up from the Hadoop cluster such as Guava and Jackson. To restore the behavior before Spark 3.1, you can set `spark.yarn.populateHadoopClasspath` to `true`.

## Upgrading from Core 2.4 to 3.0

- The `org.apache.spark.ExecutorPlugin` interface and related configuration has been replaced with
  `org.apache.spark.api.plugin.SparkPlugin`, which adds new functionality. Plugins using the old
  interface must be modified to extend the new interfaces. Check the
  [Monitoring](monitoring.html) guide for more details.

- Deprecated method `TaskContext.isRunningLocally` has been removed. Local execution was removed and it always has returned `false`.

- Deprecated method `shuffleBytesWritten`, `shuffleWriteTime` and `shuffleRecordsWritten` in `ShuffleWriteMetrics` have been removed. Instead, use `bytesWritten`, `writeTime ` and `recordsWritten` respectively.

- Deprecated method `AccumulableInfo.apply` have been removed because creating `AccumulableInfo` is disallowed.

- Deprecated accumulator v1 APIs have been removed and please use v2 APIs instead.

- Event log file will be written as UTF-8 encoding, and Spark History Server will replay event log files as UTF-8 encoding. Previously Spark wrote the event log file as default charset of driver JVM process, so Spark History Server of Spark 2.x is needed to read the old event log files in case of incompatible encoding.

- A new protocol for fetching shuffle blocks is used. It's recommended that external shuffle services be upgraded when running Spark 3.0 apps. You can still use old external shuffle services by setting the configuration `spark.shuffle.useOldFetchProtocol` to `true`. Otherwise, Spark may run into errors with messages like `IllegalArgumentException: Unexpected message type: <number>`.

- `SPARK_WORKER_INSTANCES` is deprecated in Standalone mode. It's recommended to launch multiple executors in one worker and launch one worker per node instead of launching multiple workers per node and launching one executor per worker.
