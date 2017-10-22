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

// scalastyle:off println
package org.apache.spark.examples.graphx

// $example on$
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.graphx.GraphLoader
// $example off$


/**
 * A connected components algorithm example.
 * The connected components algorithm labels each connected component of the graph
 * with the ID of its lowest-numbered vertex.
 * For example, in a social network, connected components can approximate clusters.
 * GraphX contains an implementation of the algorithm in the
 * [`ConnectedComponents` object][ConnectedComponents],
 * and we compute the connected components of the example social network dataset.
 *
 * Run with
 * {{{
 * bin/run-example graphx.ConnectedComponentsExample
 * }}}
 */
object ConnectedComponentsExample {
  def main(args: Array[String]): Unit = {
    // Creates a SparkSession.
    val conf = new SparkConf()
    conf.setAppName("My First Spark Graphx").setMaster("local")
    // Define Spark Context which we will use to initialize our SQL Context
    val sc = new SparkContext(conf)

    // $example on$
    // Load the graph as in the PageRank example
    val graph = GraphLoader.edgeListFile(sc, "data/graphx/followers.txt")
    // Find the connected components
    //连接的组件算法将图中每个连接的组件与其最低编号顶点的ID进行标记
    val cc = graph.connectedComponents().vertices
    println("===========")
    /**
    (4,1)
    (1,1)
    (6,3)
    (3,3)
    (7,3)
    (2,1)**/
    cc.foreach(println)
    // Join the connected components with the usernames
    val users = sc.textFile("data/graphx/users.txt").map { line =>
      val fields = line.split(",")
      (fields(0).toLong, fields(1))
    }
    val ccByUsername = users.join(cc).map {
      case (id, (username, cc)) => (username, cc)
    }
    // Print the result
    /**
      (justinbieber,1)
      (BarackObama,1)
      (matei_zaharia,3)
      (jeresig,3)
      (odersky,3)
      (ladygaga,1)
      */
    println(ccByUsername.collect().mkString("\n"))
    // $example off$
    sc.stop()
  }
}
// scalastyle:on println
