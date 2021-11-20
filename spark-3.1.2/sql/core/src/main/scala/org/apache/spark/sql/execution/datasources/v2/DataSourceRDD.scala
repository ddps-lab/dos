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

package org.apache.spark.sql.execution.datasources.v2

import scala.language.existentials

import org.apache.spark._
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.connector.read.{InputPartition, PartitionReader, PartitionReaderFactory}
import org.apache.spark.sql.vectorized.ColumnarBatch

class DataSourceRDDPartition(val index: Int, val inputPartition: InputPartition)
  extends Partition with Serializable

// TODO: we should have 2 RDDs: an RDD[InternalRow] for row-based scan, an `RDD[ColumnarBatch]` for
// columnar scan.
class DataSourceRDD(
    sc: SparkContext,
    @transient private val inputPartitions: Seq[InputPartition],
    partitionReaderFactory: PartitionReaderFactory,
    columnarReads: Boolean)
  extends RDD[InternalRow](sc, Nil) {

  override protected def getPartitions: Array[Partition] = {
    inputPartitions.zipWithIndex.map {
      case (inputPartition, index) => new DataSourceRDDPartition(index, inputPartition)
    }.toArray
  }

  private def castPartition(split: Partition): DataSourceRDDPartition = split match {
    case p: DataSourceRDDPartition => p
    case _ => throw new SparkException(s"[BUG] Not a DataSourceRDDPartition: $split")
  }

  override def compute(split: Partition, context: TaskContext): Iterator[InternalRow] = {
    val inputPartition = castPartition(split).inputPartition
    val (iter, reader) = if (columnarReads) {
      val batchReader = partitionReaderFactory.createColumnarReader(inputPartition)
      val iter = new MetricsBatchIterator(new PartitionIterator[ColumnarBatch](batchReader))
      (iter, batchReader)
    } else {
      val rowReader = partitionReaderFactory.createReader(inputPartition)
      val iter = new MetricsRowIterator(new PartitionIterator[InternalRow](rowReader))
      (iter, rowReader)
    }
    context.addTaskCompletionListener[Unit](_ => reader.close())
    // TODO: SPARK-25083 remove the type erasure hack in data source scan
    new InterruptibleIterator(context, iter.asInstanceOf[Iterator[InternalRow]])
  }

  override def getPreferredLocations(split: Partition): Seq[String] = {
    castPartition(split).inputPartition.preferredLocations()
  }
}

private class PartitionIterator[T](reader: PartitionReader[T]) extends Iterator[T] {
  private[this] var valuePrepared = false

  override def hasNext: Boolean = {
    if (!valuePrepared) {
      valuePrepared = reader.next()
    }
    valuePrepared
  }

  override def next(): T = {
    if (!hasNext) {
      throw new java.util.NoSuchElementException("End of stream")
    }
    valuePrepared = false
    reader.get()
  }
}

private class MetricsHandler extends Logging with Serializable {
  private val inputMetrics = TaskContext.get().taskMetrics().inputMetrics
  private val startingBytesRead = inputMetrics.bytesRead
  private val getBytesRead = SparkHadoopUtil.get.getFSBytesReadOnThreadCallback()

  def updateMetrics(numRows: Int, force: Boolean = false): Unit = {
    inputMetrics.incRecordsRead(numRows)
    val shouldUpdateBytesRead =
      inputMetrics.recordsRead % SparkHadoopUtil.UPDATE_INPUT_METRICS_INTERVAL_RECORDS == 0
    if (shouldUpdateBytesRead || force) {
      inputMetrics.setBytesRead(startingBytesRead + getBytesRead())
    }
  }
}

private abstract class MetricsIterator[I](iter: Iterator[I]) extends Iterator[I] {
  protected val metricsHandler = new MetricsHandler

  override def hasNext: Boolean = {
    if (iter.hasNext) {
      true
    } else {
      metricsHandler.updateMetrics(0, force = true)
      false
    }
  }
}

private class MetricsRowIterator(
    iter: Iterator[InternalRow]) extends MetricsIterator[InternalRow](iter) {
  override def next(): InternalRow = {
    val item = iter.next
    metricsHandler.updateMetrics(1)
    item
  }
}

private class MetricsBatchIterator(
    iter: Iterator[ColumnarBatch]) extends MetricsIterator[ColumnarBatch](iter) {
  override def next(): ColumnarBatch = {
    val batch: ColumnarBatch = iter.next
    metricsHandler.updateMetrics(batch.numRows)
    batch
  }
}
