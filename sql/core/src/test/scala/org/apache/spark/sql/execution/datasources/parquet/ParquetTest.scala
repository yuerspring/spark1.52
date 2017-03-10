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

package org.apache.spark.sql.execution.datasources.parquet

import java.io.File

import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

import org.apache.spark.sql.test.SQLTestUtils
import org.apache.spark.sql.{DataFrame, SaveMode, SQLContext}

/**
 * A helper trait that provides convenient facilities for Parquet testing.
 * 帮助性能,为Parquet测试提供方便的设施
 *
 * NOTE: Considering classes `Tuple1` ... `Tuple22` all extend `Product`, it would be more
 * convenient to use tuples rather than special case classes when writing test cases/suites.
 * 在编写测试用例/套件时使用元组而不是特殊情况类会更方便,
 * Especially, `Tuple1.apply` can be used to easily wrap a single type/value.
 * 特别是,`Tuple1.apply`可以用来轻松包装一个类型/值
 */
private[sql] trait ParquetTest extends SQLTestUtils {
  protected def _sqlContext: SQLContext

  /**
   * Writes `data` to a Parquet file, which is then passed to `f` and will be deleted after `f`
   * returns.
   * 将`data`写入Parquet文件,然后将其传递给`f`,并在`f`返回后被删除
   */
  protected def withParquetFile[T <: Product: ClassTag: TypeTag]
      (data: Seq[T])
      (f: String => Unit): Unit = {
    withTempPath { file =>
      // _sqlContext.createDataFrame(data).show()
      _sqlContext.createDataFrame(data).write.parquet(file.getCanonicalPath)
      f(file.getCanonicalPath)
    }
  }

  /**
   * Writes `data` to a Parquet file and reads it back as a [[DataFrame]],
   * which is then passed to `f`. The Parquet file will be deleted after `f` returns.
   * 将`data`写入Parquet文件,并将其读回[[DataFrame]],然后传递给`f`, `f`返回后,Parquet文件将被删除。
   */
  protected def withParquetDataFrame[T <: Product: ClassTag: TypeTag]
      (data: Seq[T])
      (f: DataFrame => Unit): Unit = {
    withParquetFile(data)(path => f(_sqlContext.read.parquet(path)))
  }

  /**
   * Writes `data` to a Parquet file, reads it back as a [[DataFrame]] and registers it as a
   * temporary table named `tableName`, then call `f`. The temporary table together with the
   * Parquet file will be dropped/deleted after `f` returns.
   * 将`data`写入Parquet文件,将其读取为[[DataFrame]]并将其注册为名为`tableName`的临时表,然后调用`f`,
   *  `f`返回后,临时表和Parquet文件将被删除/删除
   */
  protected def withParquetTable[T <: Product: ClassTag: TypeTag]
      (data: Seq[T], tableName: String)
      (f: => Unit): Unit = {
    withParquetDataFrame(data) { df =>
      //注册数据集及表
      _sqlContext.registerDataFrameAsTable(df, tableName)
      withTempTable(tableName)(f)
    }
  }
  //生产Parquet文件
  protected def makeParquetFile[T <: Product: ClassTag: TypeTag](
      data: Seq[T], path: File): Unit = {
       //当数据输出的位置已存在时,重写
    _sqlContext.createDataFrame(data).write.mode(SaveMode.Overwrite).parquet(path.getCanonicalPath)
  }
  //生产Parquet文件
  protected def makeParquetFile[T <: Product: ClassTag: TypeTag](
      df: DataFrame, path: File): Unit = {
       //当数据输出的位置已存在时,重写
    df.write.mode(SaveMode.Overwrite).parquet(path.getCanonicalPath)
  }
//生产分区目录
  protected def makePartitionDir(
      basePath: File,
      defaultPartitionName: String,
      partitionCols: (String, Any)*): File = {
    val partNames = partitionCols.map { case (k, v) =>
      val valueString = if (v == null || v == "") defaultPartitionName else v.toString
      s"$k=$valueString"
    }

    val partDir = partNames.foldLeft(basePath) { (parent, child) =>
      new File(parent, child)
    }

    assert(partDir.mkdirs(), s"Couldn't create directory $partDir")
    partDir
  }
}
