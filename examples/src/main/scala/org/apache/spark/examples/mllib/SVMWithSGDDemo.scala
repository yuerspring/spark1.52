package org.apache.spark.examples.mllib
import org.apache.spark.SparkContext
import org.apache.spark.mllib.classification.SVMWithSGD
import org.apache.spark.mllib.evaluation.BinaryClassificationMetrics
import org.apache.spark.mllib.util.MLUtils
import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.apache.spark.SparkConf
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.evaluation.MulticlassMetrics
import org.apache.spark.mllib.classification.LogisticRegressionWithLBFGS
/**
 * 线性支持向量机用于大规模分类任务的标准方法
 */
object SVMWithSGDDemo {
  def main(args: Array[String]) {
    // 屏蔽不必要的日志显示终端上
    Logger.getLogger("org.apache.spark").setLevel(Level.ERROR)
    Logger.getLogger("org.eclipse.jetty.server").setLevel(Level.OFF)
    // 设置运行环境
    val conf = new SparkConf().setAppName("SVMWithSGDDemo").setMaster("local[4]")
    val sc = new SparkContext(conf)
/**
 *  libSVM的数据格式
 *  <label> <index1>:<value1> <index2>:<value2> ...
 *  其中<label>是训练数据集的目标值,对于分类,它是标识某类的整数(支持多个类);对于回归,是任意实数
 *  <index>是以1开始的整数,可以是不连续
 *  <value>为实数,也就是我们常说的自变量
 */
    // 加载LIBSVM格式数据    
    // Load and parse the data file
    // Load training data in LIBSVM format.
    //加载训练libsvm格式的数据
    val data = MLUtils.loadLibSVMFile(sc, "../data/mllib/sample_libsvm_data.txt")
    // Split data into training (60%) and test (40%).
    //将数据切分训练数据(60%)和测试数据(40%)
    val splits = data.randomSplit(Array(0.6, 0.4), seed = 11L)
    val training = splits(0).cache()
    val test = splits(1)
    //运行训练数据模型构建模型
    // Run training algorithm to build the model    
    val numIterations = 100
    val model = SVMWithSGD.train(training, numIterations)
    //清除默认阈值
    // Clear the default threshold.
    model.clearThreshold()
    //在测试数据上计算原始分数
    // Compute raw scores on the test set.
    val scoreAndLabels = test.map { point =>
      val score = model.predict(point.features)
      println(">>" + score + "\t" + point.label)
      (score, point.label)
    }
    //获得评估指标
    // Get evaluation metrics.
    val metrics = new BinaryClassificationMetrics(scoreAndLabels)
    val auROC = metrics.areaUnderROC()
    //平均准确率
    println("Area under ROC = " + auROC)

    
    /**逻辑回归***/
    //逻辑回归,基于lbfgs优化损失函数,支持多分类
    val modelBFGS = new LogisticRegressionWithLBFGS()
      .setNumClasses(10)
      .run(training)
    //在测试数据上计算原始分数
    // Compute raw scores on the test set.
    val predictionAndLabels = test.map {
    //LabeledPoint标记点是局部向量,向量可以是密集型或者稀疏型,每个向量会关联了一个标签(label)
      case LabeledPoint(label, features) =>
        val prediction = model.predict(features)
        (prediction, label)
    }
    //获取评估指标
    // Get evaluation metrics.
    val metricsBFGS = new MulticlassMetrics(predictionAndLabels)
    val precision = metricsBFGS.precision
    println("Precision = " + precision)

  }

}