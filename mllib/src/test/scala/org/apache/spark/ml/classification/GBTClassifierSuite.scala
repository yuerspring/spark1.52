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

package org.apache.spark.ml.classification

import org.apache.spark.SparkFunSuite
import org.apache.spark.ml.impl.TreeTests
import org.apache.spark.ml.param.ParamsSuite
import org.apache.spark.ml.regression.DecisionTreeRegressionModel
import org.apache.spark.ml.tree.LeafNode
import org.apache.spark.ml.util.MLTestingUtils
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.tree.{EnsembleTestHelper, GradientBoostedTrees => OldGBT}
import org.apache.spark.mllib.tree.configuration.{Algo => OldAlgo}
import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame
import org.apache.spark.util.Utils


/**
 * Test suite for [[GBTClassifier]].
 * 梯度提升树(GBT)分类
 * 
 */
class GBTClassifierSuite extends SparkFunSuite with MLlibTestSparkContext {

  import GBTClassifierSuite.compareAPIs

  // Combinations for estimators, learning rates and subsamplingRate
  //组合评估,学习率和子采样率
  private val testCombinations =
    Array((10, 1.0, 1.0), (10, 0.1, 1.0), (10, 0.5, 0.75), (10, 0.1, 0.75))

  private var data: RDD[LabeledPoint] = _
  private var trainData: RDD[LabeledPoint] = _
  private var validationData: RDD[LabeledPoint] = _

  override def beforeAll() {
    super.beforeAll()
    data = sc.parallelize(EnsembleTestHelper.generateOrderedLabeledPoints(numFeatures = 10, 100), 2)
    trainData =
      sc.parallelize(EnsembleTestHelper.generateOrderedLabeledPoints(numFeatures = 20, 120), 2)
    validationData =
      sc.parallelize(EnsembleTestHelper.generateOrderedLabeledPoints(numFeatures = 20, 80), 2)
  }

  test("params") {
    ParamsSuite.checkParams(new GBTClassifier)
    //梯度提升树(GBT)分类
    val model = new GBTClassificationModel("gbtc",
      Array(new DecisionTreeRegressionModel("dtr", new LeafNode(0.0, 0.0, null))),
      Array(1.0))
    ParamsSuite.checkParams(model)
  }
  //具有连续特征的二分类:对数损失
  test("Binary classification with continuous features: Log Loss") {
    val categoricalFeatures = Map.empty[Int, Int]
    testCombinations.foreach {
      case (maxIter, learningRate, subsamplingRate) =>
       //梯度提升树(GBT)分类
        val gbt = new GBTClassifier()
          .setMaxDepth(2)
          .setSubsamplingRate(subsamplingRate)
          .setLossType("logistic")
          .setMaxIter(maxIter)
          .setStepSize(learningRate)
        compareAPIs(data, None, gbt, categoricalFeatures)
    }
  }

  test("Checkpointing") {//检查点
    val tempDir = Utils.createTempDir()
    val path = tempDir.toURI.toString
    sc.setCheckpointDir(path)

    val categoricalFeatures = Map.empty[Int, Int]
    val df: DataFrame = TreeTests.setMetadata(data, categoricalFeatures, numClasses = 2)
    //梯度提升树(GBT)分类
    val gbt = new GBTClassifier()
      .setMaxDepth(2)
      .setLossType("logistic")
      .setMaxIter(5)
      .setStepSize(0.1)
      .setCheckpointInterval(2)
    val model = gbt.fit(df)

    // copied model must have the same parent.
    //复制的模型必须有相同的父
    MLTestingUtils.checkCopy(model)

    sc.checkpointDir = None
    Utils.deleteRecursively(tempDir)
  }

  // TODO: Reinstate test once runWithValidation is implemented   SPARK-7132
  /*
  test("runWithValidation stops early and performs better on a validation dataset") {
    val categoricalFeatures = Map.empty[Int, Int]
    // Set maxIter large enough so that it stops early.
    val maxIter = 20
    //梯度提升树(GBT)分类
    GBTClassifier.supportedLossTypes.foreach { loss =>
      val gbt = new GBTClassifier()
        .setMaxIter(maxIter)
        .setMaxDepth(2)
        .setLossType(loss)
        .setValidationTol(0.0)
      compareAPIs(trainData, None, gbt, categoricalFeatures)
      compareAPIs(trainData, Some(validationData), gbt, categoricalFeatures)
    }
  }
  */

  /////////////////////////////////////////////////////////////////////////////
  // Tests of model save/load
  /////////////////////////////////////////////////////////////////////////////

  // TODO: Reinstate test once save/load are implemented  SPARK-6725
  /*
  test("model save/load") {
    val tempDir = Utils.createTempDir()
    val path = tempDir.toURI.toString

    val trees = Range(0, 3).map(_ => OldDecisionTreeSuite.createModel(OldAlgo.Regression)).toArray
    val treeWeights = Array(0.1, 0.3, 1.1)
    val oldModel = new OldGBTModel(OldAlgo.Classification, trees, treeWeights)
    //梯度提升树(GBT)分类
    val newModel = GBTClassificationModel.fromOld(oldModel)

    // Save model, load it back, and compare.
    try {
      newModel.save(sc, path)
      val sameNewModel = GBTClassificationModel.load(sc, path)
      TreeTests.checkEqual(newModel, sameNewModel)
    } finally {
      Utils.deleteRecursively(tempDir)
    }
  }
  */
}

private object GBTClassifierSuite {

  /**
   * Train 2 models on the given dataset, one using the old API and one using the new API.
   * 在给定数据集上训练2种模型,一个使用旧的API和一个使用新的API
   * Convert the old model to the new format, compare them, and fail if they are not exactly equal.
   * 将旧模型转换为新格式,比较它们,如果它们不完全相等,则会失败
   */
  def compareAPIs(
      data: RDD[LabeledPoint],
      validationData: Option[RDD[LabeledPoint]],
      gbt: GBTClassifier, //梯度提升树(GBT)分类
      categoricalFeatures: Map[Int, Int]): Unit = {
    val oldBoostingStrategy =
      gbt.getOldBoostingStrategy(categoricalFeatures, OldAlgo.Classification)
    val oldGBT = new OldGBT(oldBoostingStrategy)
    val oldModel = oldGBT.run(data)
    val newData: DataFrame = TreeTests.setMetadata(data, categoricalFeatures, numClasses = 2)
    val newModel = gbt.fit(newData)
    // Use parent from newTree since this is not checked anyways.
    //梯度提升树(GBT)分类
    val oldModelAsNew = GBTClassificationModel.fromOld(
      oldModel, newModel.parent.asInstanceOf[GBTClassifier], categoricalFeatures)
    TreeTests.checkEqual(oldModelAsNew, newModel)
  }
}
