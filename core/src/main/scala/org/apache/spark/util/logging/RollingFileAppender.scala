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

package org.apache.spark.util.logging

import java.io.{File, FileFilter, InputStream}

import com.google.common.io.Files
import org.apache.spark.SparkConf
import RollingFileAppender._

/**
 * Continuously appends data from input stream into the given file, and rolls
 * over the file after the given interval. The rolled over files are named
 * based on the given pattern.
  * 连续地将数据从输入流附加到给定文件中，并在给定间隔后滚动文件。 翻转的文件根据给定的模式命名
 *
 * @param inputStream             Input stream to read data from 输入流从中读取数据
 * @param activeFile              File to write data to 将数据写入的文件
 * @param rollingPolicy           Policy based on which files will be rolled over. 基于哪些文件将被转动的策略
 * @param conf                    SparkConf that is used to pass on extra configurations 用于传递额外配置的SparkConf
 * @param bufferSize              Optional buffer size. Used mainly for testing. 可选缓冲区大小,主要用于测试。
 */
private[spark] class RollingFileAppender(
    inputStream: InputStream,
    activeFile: File,
    val rollingPolicy: RollingPolicy,
    conf: SparkConf,
    bufferSize: Int = DEFAULT_BUFFER_SIZE
  ) extends FileAppender(inputStream, activeFile, bufferSize) {

  private val maxRetainedFiles = conf.getInt(RETAINED_FILES_PROPERTY, -1)

  /** Stop the appender
    * 停止appender
    *  */
  override def stop() {
    super.stop()
  }

  /** Append bytes to file after rolling over is necessary
    * 在翻转后将文件追加到文件是必要的
    *  */
  override protected def appendToFile(bytes: Array[Byte], len: Int) {
    if (rollingPolicy.shouldRollover(len)) {
      rollover()
      rollingPolicy.rolledOver()
    }
    super.appendToFile(bytes, len)
    rollingPolicy.bytesWritten(len)
  }

  /** Rollover the file, by closing the output stream and moving it over
    * 通过关闭输出流并将其移动，翻转文件
    * */
  private def rollover() {
    try {
      closeFile()
      moveFile()
      openFile()
      if (maxRetainedFiles > 0) {
        deleteOldFiles()
      }
    } catch {
      case e: Exception =>
        logError(s"Error rolling over $activeFile", e)
    }
  }

  /** Move the active log file to a new rollover file
    * 将活动日志文件移动到新的翻转文件
    * */
  private def moveFile() {
    val rolloverSuffix = rollingPolicy.generateRolledOverFileSuffix()
    val rolloverFile = new File(
      activeFile.getParentFile, activeFile.getName + rolloverSuffix).getAbsoluteFile
    try {
      logDebug(s"Attempting to rollover file $activeFile to file $rolloverFile")
      if (activeFile.exists) {
        if (!rolloverFile.exists) {
          //google.common.io
          Files.move(activeFile, rolloverFile)
          logInfo(s"Rolled over $activeFile to $rolloverFile")
        } else {
          // In case the rollover file name clashes, make a unique file name.
          // The resultant file names are long and ugly, so this is used only
          // if there is a name collision. This can be avoided by the using
          // the right pattern such that name collisions do not occur.
          //如果翻转文件名冲突,请创建一个唯一的文件名,结果文件名长而丑,
          // 因此只有在名称冲突时才使用,这可以通过使用正确的模式来避免,从而不会发生名称冲突。
          var i = 0
          var altRolloverFile: File = null
          do {
            altRolloverFile = new File(activeFile.getParent,
              s"${activeFile.getName}$rolloverSuffix--$i").getAbsoluteFile
            i += 1
          } while (i < 10000 && altRolloverFile.exists)

          logWarning(s"Rollover file $rolloverFile already exists, " +
            s"rolled over $activeFile to file $altRolloverFile")
          Files.move(activeFile, altRolloverFile)
        }
      } else {
        logWarning(s"File $activeFile does not exist")
      }
    }
  }

  /** Retain only last few files
    * 只保留最后几个文件
    *  */
  private[util] def deleteOldFiles() {
    try {
      val rolledoverFiles = activeFile.getParentFile.listFiles(new FileFilter {
        def accept(f: File): Boolean = {
          f.getName.startsWith(activeFile.getName) && f != activeFile
        }
      }).sorted
      val filesToBeDeleted = rolledoverFiles.take(
        math.max(0, rolledoverFiles.size - maxRetainedFiles))
      filesToBeDeleted.foreach { file =>
        logInfo(s"Deleting file executor log file ${file.getAbsolutePath}")
        file.delete()
      }
    } catch {
      case e: Exception =>
        logError("Error cleaning logs in directory " + activeFile.getParentFile.getAbsolutePath, e)
    }
  }
}

/**
 * Companion object to [[org.apache.spark.util.logging.RollingFileAppender]]. Defines
 * names of configurations that configure rolling file appenders.
  * Companion对象[[org.apache.spark.util.logging.RollingFileAppender]]）,定义配置滚动文件追加器的配置名称。
 */
private[spark] object RollingFileAppender {
  val STRATEGY_PROPERTY = "spark.executor.logs.rolling.strategy"
  val STRATEGY_DEFAULT = ""
 /**设置Executor日志回滚的时间间隔
  *  有效地设置包括:
  *  daily:按天级回滚
  *   hourly:按照小时级回滚
  *   minutely:按照分钟级回滚
  *  有效的数字:单位是少,按照设置的秒数进行回滚
  **/
  val INTERVAL_PROPERTY = "spark.executor.logs.rolling.time.interval"
  val INTERVAL_DEFAULT = "daily"
  val SIZE_PROPERTY = "spark.executor.logs.rolling.maxSize"
  val SIZE_DEFAULT = (1024 * 1024).toString
  val RETAINED_FILES_PROPERTY = "spark.executor.logs.rolling.maxRetainedFiles"
  val DEFAULT_BUFFER_SIZE = 8192

  /**
   * Get the sorted list of rolled over files. This assumes that the all the rolled
   * over file names are prefixed with the `activeFileName`, and the active file
   * name has the latest logs. So it sorts all the rolled over logs (that are
   * prefixed with `activeFileName`) and appends the active file
    * 获取已排序的文件列表,这假设所有翻转的文件名以“activeFileName”为前缀,活动文件名称具有最新的日志。
    * 所以它排列所有滚动的日志（以“activeFileName”为前缀）并附加活动文件
   */
  def getSortedRolledOverFiles(directory: String, activeFileName: String): Seq[File] = {
    val rolledOverFiles = new File(directory).getAbsoluteFile.listFiles.filter { file =>
      val fileName = file.getName
      fileName.startsWith(activeFileName) && fileName != activeFileName
    }.sorted
    val activeFile = {
      val file = new File(directory, activeFileName).getAbsoluteFile
      if (file.exists) Some(file) else None
    }
    rolledOverFiles ++ activeFile
  }
}
