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

package org.apache.spark.deploy

import java.io.{ByteArrayInputStream, DataInputStream}
import java.lang.reflect.Method
import java.security.PrivilegedExceptionAction
import java.util.{Arrays, Comparator}

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal

import com.google.common.primitives.Longs
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem.Statistics
import org.apache.hadoop.fs.{FileStatus, FileSystem, Path, PathFilter}
import org.apache.hadoop.hdfs.security.token.delegation.DelegationTokenIdentifier
import org.apache.hadoop.mapred.JobConf
import org.apache.hadoop.mapreduce.JobContext
import org.apache.hadoop.mapreduce.{TaskAttemptContext => MapReduceTaskAttemptContext}
import org.apache.hadoop.mapreduce.{TaskAttemptID => MapReduceTaskAttemptID}
import org.apache.hadoop.security.{Credentials, UserGroupInformation}

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.util.Utils
import org.apache.spark.{Logging, SparkConf, SparkException}

/**
 * :: DeveloperApi ::
 * Contains util methods to interact with Hadoop from Spark.
 * 包含工具方法Hadoop与Spark相互调用
 */
@DeveloperApi
class SparkHadoopUtil extends Logging {
  private val sparkConf = new SparkConf()
  val conf: Configuration = newConfiguration(sparkConf)
  UserGroupInformation.setConfiguration(conf)

  /**
   * Runs the given function with a Hadoop UserGroupInformation as a thread local variable
   * (distributed to child threads), used for authenticating HDFS and YARN calls.
    * 使用Hadoop UserGroupInformation作为线程局部变量(分布到子线程)运行给定函数,用于验证HDFS和YARN调用
   *
   * IMPORTANT NOTE: If this function is going to be called repeated in the same process
   * you need to look https://issues.apache.org/jira/browse/HDFS-3545 and possibly
   * do a FileSystem.closeAllForUGI in order to avoid leaking Filesystems
    * 重要提示：如果此功能将在同一进程中重复使用,则需要查看https://issues.apache.org/jira/browse/HDFS-3545,
    * 并且可能会执行FileSystem.closeAllForUGI以避免文件系统泄漏
   */
  def runAsSparkUser(func: () => Unit) {
    val user = Utils.getCurrentUserName()
    logDebug("running as user: " + user)
    val ugi = UserGroupInformation.createRemoteUser(user)
    transferCredentials(UserGroupInformation.getCurrentUser(), ugi)
    ugi.doAs(new PrivilegedExceptionAction[Unit] {
      def run: Unit = func()
    })
  }

  def transferCredentials(source: UserGroupInformation, dest: UserGroupInformation) {
    for (token <- source.getTokens()) {
      dest.addToken(token)
    }
  }

  @deprecated("use newConfiguration with SparkConf argument", "1.2.0")
  def newConfiguration(): Configuration = newConfiguration(null)

  /**
   * Return an appropriate (subclass) of Configuration. Creating config can initializes some Hadoop
   * subsystems.
   * 返回一个适当的(子类)的配置,创建配置可以初始化一些Hadoop子系统
   */
  def newConfiguration(conf: SparkConf): Configuration = {
    val hadoopConf = new Configuration()

    // Note: this null check is around more than just access to the "conf" object to maintain
    // the behavior of the old implementation of this code, for backwards compatibility.
    //注意：这个空检查不仅仅是访问“conf”对象,以维护该代码的旧实现的行为,以便向后兼容。
    if (conf != null) {
      // Explicitly check for S3 environment variables
      //明确检查S3环境变量
      if (System.getenv("AWS_ACCESS_KEY_ID") != null &&
          System.getenv("AWS_SECRET_ACCESS_KEY") != null) {
        hadoopConf.set("fs.s3.awsAccessKeyId", System.getenv("AWS_ACCESS_KEY_ID"))
        hadoopConf.set("fs.s3n.awsAccessKeyId", System.getenv("AWS_ACCESS_KEY_ID"))
        hadoopConf.set("fs.s3.awsSecretAccessKey", System.getenv("AWS_SECRET_ACCESS_KEY"))
        hadoopConf.set("fs.s3n.awsSecretAccessKey", System.getenv("AWS_SECRET_ACCESS_KEY"))
      }
      // Copy any "spark.hadoop.foo=bar" system properties into conf as "foo=bar"
      //将任何“spark.hadoop.foo = bar”系统属性复制到conf中，作为“foo = bar”
      conf.getAll.foreach { case (key, value) =>
        if (key.startsWith("spark.hadoop.")) {
          hadoopConf.set(key.substring("spark.hadoop.".length), value)
        }
      }
      val bufferSize = conf.get("spark.buffer.size", "65536")
      hadoopConf.set("io.file.buffer.size", bufferSize)
    }

    hadoopConf
  }

  /**
   * Add any user credentials to the job conf which are necessary for running on a secure Hadoop
   * cluster.
    * 将任何用户凭据添加到在安全的Hadoop集群上运行所必需的作业conf
   */
  def addCredentials(conf: JobConf) {}

  def isYarnMode(): Boolean = { false }

  def getCurrentUserCredentials(): Credentials = { null }

  def addCurrentUserCredentials(creds: Credentials) {}

  def addSecretKeyToUserCredentials(key: String, secret: String) {}

  def getSecretKeyFromUserCredentials(key: String): Array[Byte] = { null }

  def loginUserFromKeytab(principalName: String, keytabFilename: String) {
    UserGroupInformation.loginUserFromKeytab(principalName, keytabFilename)
  }

  /**
   * Returns a function that can be called to find Hadoop FileSystem bytes read. If
   * getFSBytesReadOnThreadCallback is called from thread r at time t, the returned callback will
   * return the bytes read on r since t.  Reflection is required because thread-level FileSystem
   * statistics are only available as of Hadoop 2.5 (see HADOOP-10688).
   * Returns None if the required method can't be found.
    * 返回一个可以调用来查找Hadoop FileSystem字节的函数,如果getFSBytesReadOnThreadCallback在时间t从线程r调用，返回的回调函数将
    *从t返回r读取的字节,线程级FileSystem需要反射统计数据仅供Hadoop 2.5使用（参见HADOOP-10688）。如果找不到所需的方法，则返回None。
   * 返回一个函数,查询Hadoop文件系统读取的字节数
   */
  private[spark] def getFSBytesReadOnThreadCallback(): Option[() => Long] = {
    try {
      val threadStats = getFileSystemThreadStatistics()
      val getBytesReadMethod = getFileSystemThreadStatisticsMethod("getBytesRead")
      val f = () => threadStats.map(getBytesReadMethod.invoke(_).asInstanceOf[Long]).sum
      val baselineBytesRead = f()
      Some(() => f() - baselineBytesRead)
    } catch {
      case e @ (_: NoSuchMethodException | _: ClassNotFoundException) => {
        logDebug("Couldn't find method for retrieving thread-level FileSystem input data", e)
        None
      }
    }
  }

  /**
   * Returns a function that can be called to find Hadoop FileSystem bytes written. If
   * getFSBytesWrittenOnThreadCallback is called from thread r at time t, the returned callback will
   * return the bytes written on r since t.  Reflection is required because thread-level FileSystem
   * statistics are only available as of Hadoop 2.5 (see HADOOP-10688).
   * Returns None if the required method can't be found.
    *
    * 返回一个函数可以调用来查找写入的Hadoop FileSystem字节,如果getFSBytesWrittenOnThreadCallback在时间t从线程r调用，
    * 返回的回调将从t返回写入r的字节,线程级FileSystem需要反射统计数据仅供Hadoop 2.5使用（参见HADOOP-10688）,
    *如果找不到所需的方法,则返回None
   */
  private[spark] def getFSBytesWrittenOnThreadCallback(): Option[() => Long] = {
    try {
      val threadStats = getFileSystemThreadStatistics()
      val getBytesWrittenMethod = getFileSystemThreadStatisticsMethod("getBytesWritten")
      val f = () => threadStats.map(getBytesWrittenMethod.invoke(_).asInstanceOf[Long]).sum
      val baselineBytesWritten = f()
      Some(() => f() - baselineBytesWritten)
    } catch {
      case e @ (_: NoSuchMethodException | _: ClassNotFoundException) => {
        logDebug("Couldn't find method for retrieving thread-level FileSystem output data", e)
        None
      }
    }
  }

  private def getFileSystemThreadStatistics(): Seq[AnyRef] = {
    val stats = FileSystem.getAllStatistics()
    stats.map(Utils.invoke(classOf[Statistics], _, "getThreadStatistics"))
  }

  private def getFileSystemThreadStatisticsMethod(methodName: String): Method = {
    val statisticsDataClass =
      Utils.classForName("org.apache.hadoop.fs.FileSystem$Statistics$StatisticsData")
    statisticsDataClass.getDeclaredMethod(methodName)
  }

  /**
   * Using reflection to get the Configuration from JobContext/TaskAttemptContext. If we directly
   * call `JobContext/TaskAttemptContext.getConfiguration`, it will generate different byte codes
   * for Hadoop 1.+ and Hadoop 2.+ because JobContext/TaskAttemptContext is class in Hadoop 1.+
   * while it's interface in Hadoop 2.+.
    * 使用反射从JobContext / TaskAttemptContext获取配置。
    * 如果我们直接调用`JobContext / TaskAttemptContext.getConfiguration`，它将生成不同的字节码
    *对于Hadoop 1. +和Hadoop 2. +因为JobContext / TaskAttemptContext是Hadoop中的类1. +
    *在Hadoop 2. +的界面。
   */
  def getConfigurationFromJobContext(context: JobContext): Configuration = {
    val method = context.getClass.getMethod("getConfiguration")
    method.invoke(context).asInstanceOf[Configuration]
  }

  /**
   * Using reflection to call `getTaskAttemptID` from TaskAttemptContext. If we directly
   * call `TaskAttemptContext.getTaskAttemptID`, it will generate different byte codes
   * for Hadoop 1.+ and Hadoop 2.+ because TaskAttemptContext is class in Hadoop 1.+
   * while it's interface in Hadoop 2.+.
    *
    * 使用反射来从TaskAttemptContext调用`getTaskAttemptID`。 如果我们直接
    *调用`TaskAttemptContext.getTaskAttemptID`，会产生不同的字节码对于Hadoop 1. +和Hadoop 2. +
    * 因为TaskAttemptContext是Hadoop 1. +中的类在Hadoop 2. +的界面。
   */
  def getTaskAttemptIDFromTaskAttemptContext(
      context: MapReduceTaskAttemptContext): MapReduceTaskAttemptID = {
    val method = context.getClass.getMethod("getTaskAttemptID")
    method.invoke(context).asInstanceOf[MapReduceTaskAttemptID]
  }

  /**
   * Get [[FileStatus]] objects for all leaf children (files) under the given base path. If the
   * given path points to a file, return a single-element collection containing [[FileStatus]] of
   * that file.
    * 为给定基本路径下的所有叶子（文件）获取[[FileStatus]]对象。
    * 如果给定路径指向一个文件，返回一个包含[[FileStatus]]的单元素集合该文件。
   */
  def listLeafStatuses(fs: FileSystem, basePath: Path): Seq[FileStatus] = {
    listLeafStatuses(fs, fs.getFileStatus(basePath))
  }

  /**
   * Get [[FileStatus]] objects for all leaf children (files) under the given base path. If the
   * given path points to a file, return a single-element collection containing [[FileStatus]] of
   * that file.
    * 为给定基本路径下的所有叶子(文件)获取[[FileStatus]]对象,如果给定的路径指向一个文件,则返回一个包含该文件[[FileStatus]]的单元素集合,
   */
  def listLeafStatuses(fs: FileSystem, baseStatus: FileStatus): Seq[FileStatus] = {
    def recurse(status: FileStatus): Seq[FileStatus] = {
      val (directories, leaves) = fs.listStatus(status.getPath).partition(_.isDir)
      leaves ++ directories.flatMap(f => listLeafStatuses(fs, f))
    }

    if (baseStatus.isDir) recurse(baseStatus) else Seq(baseStatus)
  }

  def listLeafDirStatuses(fs: FileSystem, basePath: Path): Seq[FileStatus] = {
    listLeafDirStatuses(fs, fs.getFileStatus(basePath))
  }

  def listLeafDirStatuses(fs: FileSystem, baseStatus: FileStatus): Seq[FileStatus] = {
    def recurse(status: FileStatus): Seq[FileStatus] = {
      val (directories, files) = fs.listStatus(status.getPath).partition(_.isDir)
      val leaves = if (directories.isEmpty) Seq(status) else Seq.empty[FileStatus]
      leaves ++ directories.flatMap(dir => listLeafDirStatuses(fs, dir))
    }

    assert(baseStatus.isDir)
    recurse(baseStatus)
  }

  def globPath(pattern: Path): Seq[Path] = {
    val fs = pattern.getFileSystem(conf)
    Option(fs.globStatus(pattern)).map { statuses =>
      statuses.map(_.getPath.makeQualified(fs.getUri, fs.getWorkingDirectory)).toSeq
    }.getOrElse(Seq.empty[Path])
  }

  def globPathIfNecessary(pattern: Path): Seq[Path] = {
    if (pattern.toString.exists("{}[]*?\\".toSet.contains)) {
      globPath(pattern)
    } else {
      Seq(pattern)
    }
  }

  /**
   * Lists all the files in a directory with the specified prefix, and does not end with the
   * given suffix. The returned {{FileStatus}} instances are sorted by the modification times of
   * the respective files.
    *
    * 列出具有指定前缀的目录中的所有文件,不以给定的后缀结尾,返回的{{FileStatus}}实例按照相应文件的修改时间进行排序。
   * 指定的前缀文件名,列出目录中的所有文件,返回FileStatus实例按文件修改时间排序,
   */
  def listFilesSorted(
      remoteFs: FileSystem,
      dir: Path,
      prefix: String,
      exclusionSuffix: String): Array[FileStatus] = {
    try {
      val fileStatuses = remoteFs.listStatus(dir,
        new PathFilter {
          override def accept(path: Path): Boolean = {
            val name = path.getName
            name.startsWith(prefix) && !name.endsWith(exclusionSuffix)
          }
        })
      Arrays.sort(fileStatuses, new Comparator[FileStatus] {
        override def compare(o1: FileStatus, o2: FileStatus): Int = {
          Longs.compare(o1.getModificationTime, o2.getModificationTime)
        }
      })
      fileStatuses
    } catch {
      case NonFatal(e) =>
        logWarning("Error while attempting to list files from application staging dir", e)
        Array.empty
    }
  }

  /**
   * How much time is remaining (in millis) from now to (fraction * renewal time for the token that
   * is valid the latest)?
   * This will return -ve (or 0) value if the fraction of validity has already expired.
    *
    * 从现在开始剩余多少时间(以毫秒计)(最新的有效令牌的分数*更新时间)?如果有效性的分数已经过期,这将返回-ve(或0)值
   */
  def getTimeFromNowToRenewal(
      sparkConf: SparkConf,
      fraction: Double,
      credentials: Credentials): Long = {
    val now = System.currentTimeMillis()

    val renewalInterval =
      sparkConf.getLong("spark.yarn.token.renewal.interval", (24 hours).toMillis)

    credentials.getAllTokens.filter(_.getKind == DelegationTokenIdentifier.HDFS_DELEGATION_KIND)
      .map { t =>
      val identifier = new DelegationTokenIdentifier()
      identifier.readFields(new DataInputStream(new ByteArrayInputStream(t.getIdentifier)))
      (identifier.getIssueDate + fraction * renewalInterval).toLong - now
    }.foldLeft(0L)(math.max)
  }


  private[spark] def getSuffixForCredentialsPath(credentialsPath: Path): Int = {
    val fileName = credentialsPath.getName
    fileName.substring(
      fileName.lastIndexOf(SparkHadoopUtil.SPARK_YARN_CREDS_COUNTER_DELIM) + 1).toInt
  }


  private val HADOOP_CONF_PATTERN = "(\\$\\{hadoopconf-[^\\}\\$\\s]+\\})".r.unanchored

  /**
   * Substitute variables by looking them up in Hadoop configs. Only variables that match the
   * ${hadoopconf- .. } pattern are substituted.
    * 通过在Hadoop配置中查找变量来代替变量,只有匹配的变量$ {hadoopconf- ..}模式被替代。
   */
  def substituteHadoopVariables(text: String, hadoopConf: Configuration): String = {
    text match {
      case HADOOP_CONF_PATTERN(matched) => {
        logDebug(text + " matched " + HADOOP_CONF_PATTERN)
        val key = matched.substring(13, matched.length() - 1) // remove ${hadoopconf- .. }
        val eval = Option[String](hadoopConf.get(key))
          .map { value =>
            logDebug("Substituted " + matched + " with " + value)
            text.replace(matched, value)
          }
        if (eval.isEmpty) {
          // The variable was not found in Hadoop configs, so return text as is.
          //在Hadoop配置中找不到该变量,因此返回文本。
          text
        } else {
          // Continue to substitute more variables.
          //继续替代更多的变量
          substituteHadoopVariables(eval.get, hadoopConf)
        }
      }
      case _ => {
        logDebug(text + " didn't match " + HADOOP_CONF_PATTERN)
        text
      }
    }
  }

  /**
   * Start a thread to periodically update the current user's credentials with new delegation
   * tokens so that writes to HDFS do not fail.
    * 启动线程以使用新的委托令牌定期更新当前用户的凭据,以便写入HDFS不会失败
   */
  private[spark] def startExecutorDelegationTokenRenewer(conf: SparkConf) {}

  /**
   * Stop the thread that does the delegation token updates.
    * 停止进行委托令更新的线程
   */
  private[spark] def stopExecutorDelegationTokenRenewer() {}

  /**
   * Return a fresh Hadoop configuration, bypassing the HDFS cache mechanism.
   * This is to prevent the DFSClient from using an old cached token to connect to the NameNode.
    * 返回一个新的Hadoop配置,绕过HDFS缓存机制,这是为了防止DFSClient使用旧的缓存令牌连接到NameNode。
   */
  private[spark] def getConfBypassingFSCache(
      hadoopConf: Configuration,
      scheme: String): Configuration = {
    val newConf = new Configuration(hadoopConf)
    val confKey = s"fs.${scheme}.impl.disable.cache"
    newConf.setBoolean(confKey, true)
    newConf
  }
}

object SparkHadoopUtil {

  private lazy val hadoop = new SparkHadoopUtil
  private lazy val yarn = try {
    Utils.classForName("org.apache.spark.deploy.yarn.YarnSparkHadoopUtil")
      .newInstance()
      .asInstanceOf[SparkHadoopUtil]
  } catch {
    case e: Exception => throw new SparkException("Unable to load YARN support", e)
  }

  val SPARK_YARN_CREDS_TEMP_EXTENSION = ".tmp"

  val SPARK_YARN_CREDS_COUNTER_DELIM = "-"

  def get: SparkHadoopUtil = {
    // Check each time to support changing to/from YARN
    // 检查每个时间支持改变YARN
    val yarnMode = java.lang.Boolean.valueOf(
        System.getProperty("SPARK_YARN_MODE", System.getenv("SPARK_YARN_MODE")))
    if (yarnMode) {
      yarn
    } else {
      hadoop
    }
  }
}
