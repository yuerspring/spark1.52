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

package org.apache.spark.sql.execution

import scala.util.Random

import org.apache.spark.AccumulatorSuite
import org.apache.spark.sql.{RandomDataGenerator, Row, SQLConf}
import org.apache.spark.sql.catalyst.dsl.expressions._
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.sql.types._

/**
 * A test suite that generates randomized data to test the [[TungstenSort]] operator.
 * 一个测试用例随机生成数据测试[tungstensort]操作
 */
class TungstenSortSuite extends SparkPlanTest with SharedSQLContext {

  override def beforeAll(): Unit = {
    super.beforeAll()
    ctx.conf.setConf(SQLConf.CODEGEN_ENABLED, true)
  }

  override def afterAll(): Unit = {
    try {
      ctx.conf.setConf(SQLConf.CODEGEN_ENABLED, SQLConf.CODEGEN_ENABLED.defaultValue.get)
    } finally {
      super.afterAll()
    }
  }

  test("sort followed by limit") {//排序下列限制
    checkThatPlansAgree(
      (1 to 100).map(v => Tuple1(v)).toDF("a"),
      //Nil是一个空的List,::向队列的头部追加数据,创造新的列表
      (child: SparkPlan) => Limit(10, TungstenSort('a.asc :: Nil, true, child)),
      //Nil是一个空的List,::向队列的头部追加数据,创造新的列表
      (child: SparkPlan) => Limit(10, Sort('a.asc :: Nil, global = true, child)),
      sortAnswers = false
    )
  }

  test("sorting does not crash for large inputs") {//大输入的排序不崩溃
  //Nil是一个空的List,::向队列的头部追加数据,创造新的列表
    val sortOrder = 'a.asc :: Nil
    val stringLength = 1024 * 1024 * 2
    checkThatPlansAgree(
      Seq(Tuple1("a" * stringLength), Tuple1("b" * stringLength)).toDF("a").repartition(1),
      TungstenSort(sortOrder, global = true, _: SparkPlan, testSpillFrequency = 1),
      Sort(sortOrder, global = true, _: SparkPlan),
      sortAnswers = false
    )
  }

  test("sorting updates peak execution memory") {//排序更新执行内存值
    val sc = ctx.sparkContext
    AccumulatorSuite.verifyPeakExecutionMemorySet(sc, "unsafe external sort") {
      checkThatPlansAgree(
        (1 to 100).map(v => Tuple1(v)).toDF("a"),
        //Nil是一个空的List,::向队列的头部追加数据,创造新的列表
        (child: SparkPlan) => TungstenSort('a.asc :: Nil, true, child),
        //Nil是一个空的List,::向队列的头部追加数据,创造新的列表
        (child: SparkPlan) => Sort('a.asc :: Nil, global = true, child),
        sortAnswers = false)
    }
  }

  // Test sorting on different data types
  //不同数据类型的测试排序
  for (
    dataType <- DataTypeTestUtils.atomicTypes ++ Set(NullType);
    nullable <- Seq(true, false);
    //Nil是一个空的List,::向队列的头部追加数据,创造新的列表
    sortOrder <- Seq('a.asc :: Nil, 'a.desc :: Nil);
    randomDataGenerator <- RandomDataGenerator.forType(dataType, nullable)
  ) {
    test(s"sorting on $dataType with nullable=$nullable, sortOrder=$sortOrder") {
      val inputData = Seq.fill(1000)(randomDataGenerator())
      val inputDf = ctx.createDataFrame(
        ctx.sparkContext.parallelize(Random.shuffle(inputData).map(v => Row(v))),
        //Nil是一个空的List,::向队列的头部追加数据,创造新的列表
        StructType(StructField("a", dataType, nullable = true) :: Nil)
      )
      assert(TungstenSort.supportsSchema(inputDf.schema))
      checkThatPlansAgree(
        inputDf,
        plan => ConvertToSafe(
          TungstenSort(sortOrder, global = true, plan: SparkPlan, testSpillFrequency = 23)),
        Sort(sortOrder, global = true, _: SparkPlan),
        sortAnswers = false
      )
    }
  }
}
