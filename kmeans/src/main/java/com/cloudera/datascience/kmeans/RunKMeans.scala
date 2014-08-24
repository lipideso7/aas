/*
 * Copyright 2014 Sandy Ryza, Josh Wills, Sean Owen, Uri Laserson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cloudera.datascience.kmeans

import org.apache.spark.mllib.clustering._
import org.apache.spark.mllib.linalg._
import org.apache.spark.rdd._
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.SparkContext._

object RunKMeans {

  def main(args: Array[String]): Unit = {
    val sc = new SparkContext(new SparkConf().setAppName("K-means"))
    val rawData = sc.textFile("/user/spark/kddcup.data", 120)
    clusteringTake0(rawData)
    clusteringTake1(rawData)
    clusteringTake2(rawData)
    clusteringTake3(rawData)
    clusteringTake4(rawData)
    anomalies(rawData)
  }

  // Clustering, Take 0

  def clusteringTake0(rawData: RDD[String]): Unit = {

    rawData.map(_.split(',').last).countByValue.toSeq.sortBy(_._2).reverse.foreach(println)

    val labelsAndData = rawData.map { line =>
      val buffer = line.split(',').toBuffer
      buffer.remove(1, 3)
      val label = buffer.remove(buffer.length - 1)
      val vector = Vectors.dense(buffer.map(_.toDouble).toArray)
      (label, vector)
    }

    val data = labelsAndData.values.cache()

    val kmeans = new KMeans()
    val model = kmeans.run(data)

    model.clusterCenters.foreach(println)

    val clusterLabelCount = labelsAndData.map { case (label, datum) =>
      val cluster = model.predict(datum)
      (cluster, label)
    }.countByValue

    clusterLabelCount.toSeq.sorted.foreach { case ((cluster, label), count) =>
      println(f"$cluster%1s$label%18s$count%8s")
    }

    data.unpersist(true)
  }

  // Clustering, Take 1

  def distance(a: Vector, b: Vector) =
    math.sqrt(a.toArray.zip(b.toArray).map(p => p._1 - p._2).map(d => d * d).sum)

  def distToCentroid(datum: Vector, model: KMeansModel) = {
    val centroid = model.clusterCenters(model.predict(datum))
    distance(centroid, datum)
  }

  def clusteringScore(data: RDD[Vector], k: Int): Double = {
    val kmeans = new KMeans()
    kmeans.setK(k)
    val model = kmeans.run(data)
    data.map(datum => distToCentroid(datum, model)).mean()
  }

  def clusteringScore2(data: RDD[Vector], k: Int): Double = {
    val kmeans = new KMeans()
    kmeans.setK(k)
    kmeans.setRuns(10)
    kmeans.setEpsilon(1.0e-6)
    val model = kmeans.run(data)
    data.map(datum => distToCentroid(datum, model)).mean()
  }

  def clusteringTake1(rawData: RDD[String]): Unit = {

    val data = rawData.map { line =>
      val buffer = line.split(',').toBuffer
      buffer.remove(1, 3)
      buffer.remove(buffer.length - 1)
      Vectors.dense(buffer.map(_.toDouble).toArray)
    }.cache()

    (5 to 30 by 5).map(k => (k, clusteringScore(data, k))).
      foreach(println)

    (30 to 100 by 10).par.map(k => (k, clusteringScore2(data, k))).
      toList.foreach(println)

    data.unpersist(true)

  }

  def visualizationInR(rawData: RDD[String]): Unit = {

    val data = rawData.map { line =>
      val buffer = line.split(',').toBuffer
      buffer.remove(1, 3)
      buffer.remove(buffer.length - 1)
      Vectors.dense(buffer.map(_.toDouble).toArray)
    }.cache()

    val kmeans = new KMeans()
    kmeans.setK(100)
    kmeans.setRuns(10)
    kmeans.setEpsilon(1.0e-6)
    val model = kmeans.run(data)

    val sample = data.map(datum =>
      model.predict(datum) + "," + datum.toArray.mkString(",")
    ).filter(_.hashCode % 20 == 0)

    sample.saveAsTextFile("/user/spark/sample")

    data.unpersist(true)

  }

  // Clustering, Take 2

  def buildNormalizationFunction(data: RDD[Vector]): (Vector => Vector) = {
    val dataAsArray = data.map(_.toArray)
    val numCols = dataAsArray.take(1)(0).length
    val n = dataAsArray.count()
    val sums = dataAsArray.reduce(
      (a, b) => a.zip(b).map(t => t._1 + t._2))
    val sumSquares = dataAsArray.fold(
        new Array[Double](numCols)
      )(
        (a, b) => a.zip(b).map(t => t._1 + t._2 * t._2)
      )
    val stdevs = sumSquares.zip(sums).map {
      case (sumSq, sum) => math.sqrt(n * sumSq - sum * sum) / n
    }
    val means = sums.map(_ / n)

    (datum: Vector) => {
      val normalizedArray = (datum.toArray, means, stdevs).zipped.map(
        (value, mean, stdev) =>
          if (stdev <= 0)  (value - mean) else  (value - mean) / stdev
      )
      Vectors.dense(normalizedArray)
    }
  }

  def clusteringTake2(rawData: RDD[String]): Unit = {
    val data = rawData.map { line =>
      val buffer = line.split(',').toBuffer
      buffer.remove(1, 3)
      buffer.remove(buffer.length - 1)
      Vectors.dense(buffer.map(_.toDouble).toArray)
    }

    val normalizedData = data.map(buildNormalizationFunction(data)).cache()

    (60 to 120 by 10).par.map(k =>
      (k, clusteringScore2(normalizedData, k))).toList.foreach(println)

    normalizedData.unpersist(true)
  }

  // Clustering, Take 3

  def buildCategoricalAndLabelFunction(rawData: RDD[String]): (String => (String,Vector)) = {
    val protocols = rawData.map(_.split(',')(1)).distinct().collect().zipWithIndex.toMap
    val services = rawData.map(_.split(',')(2)).distinct().collect().zipWithIndex.toMap
    val tcpStates = rawData.map(_.split(',')(3)).distinct().collect().zipWithIndex.toMap
    (line: String) => {
      val buffer = line.split(",").toBuffer
      val protocol = buffer.remove(1)
      val service = buffer.remove(1)
      val tcpState = buffer.remove(1)
      val label = buffer.remove(buffer.length - 1)
      val vector = buffer.map(_.toDouble)

      val newProtocolFeatures = new Array[Double](protocols.size)
      newProtocolFeatures(protocols(protocol)) = 1.0
      val newServiceFeatures = new Array[Double](services.size)
      newServiceFeatures(services(service)) = 1.0
      val newTcpStateFeatures = new Array[Double](tcpStates.size)
      newTcpStateFeatures(tcpStates(tcpState)) = 1.0

      vector.insertAll(1, newTcpStateFeatures)
      vector.insertAll(1, newServiceFeatures)
      vector.insertAll(1, newProtocolFeatures)

      (label, Vectors.dense(vector.toArray))
    }
  }

  def clusteringTake3(rawData: RDD[String]): Unit = {
    val parseFunction = buildCategoricalAndLabelFunction(rawData)
    val data = rawData.map(parseFunction).values
    val normalizedData = data.map(buildNormalizationFunction(data)).cache()

    (80 to 160 by 10).map(k =>
      (k, clusteringScore2(normalizedData, k))).toList.foreach(println)

    normalizedData.unpersist(true)
  }

  // Clustering, Take 4

  def entropy(counts: Iterable[Int]) = {
    val values = counts.filter(_ > 0)
    val n: Double = values.sum
    values.map { v =>
      val p = v / n
      -p * math.log(p)
    }.sum
  }

  def clusteringScore3(normalizedLabelsAndData: RDD[(String,Vector)], k: Int) = {
    val kmeans = new KMeans()
    kmeans.setK(k)
    kmeans.setRuns(10)
    kmeans.setEpsilon(1.0e-6)
    val model = kmeans.run(normalizedLabelsAndData.values)
    val labelsInCluster = normalizedLabelsAndData.mapValues(model.predict).
      map(t => (t._2,t._1)).groupByKey().values
    val labelCounts = labelsInCluster.map(_.groupBy(l => l).map(_._2.size))
    labelCounts.map(m => m.sum * entropy(m)).sum / normalizedLabelsAndData.count()
  }

  def clusteringTake4(rawData: RDD[String]): Unit = {
    val parseFunction = buildCategoricalAndLabelFunction(rawData)
    val labelsAndData = rawData.map(parseFunction)
    val normalizedLabelsAndData =
      labelsAndData.mapValues(buildNormalizationFunction(labelsAndData.values)).cache()

    (80 to 160 by 10).map(k =>
      (k, clusteringScore3(normalizedLabelsAndData, k))).toList.foreach(println)

    normalizedLabelsAndData.unpersist(true)
  }

  // Detect anomalies

  def buildAnomalyDetector(data: RDD[Vector],
                           normalizeFunction: (Vector => Vector)): (Vector => Boolean) = {
    val normalizedData = data.map(normalizeFunction)
    normalizedData.cache()

    val kmeans = new KMeans()
    kmeans.setK(150)
    kmeans.setRuns(10)
    kmeans.setEpsilon(1.0e-6)
    val model = kmeans.run(normalizedData)

    normalizedData.unpersist(true)

    val distances = normalizedData.map(datum => distToCentroid(datum, model))
    val threshold = distances.top(100).last

    (datum: Vector) => distToCentroid(normalizeFunction(datum), model) > threshold
  }

  def anomalies(rawData: RDD[String]) = {
    val parseFunction = buildCategoricalAndLabelFunction(rawData)
    val originalAndData = rawData.map(line => (line, parseFunction(line)._2))
    val data = originalAndData.values
    val normalizeFunction = buildNormalizationFunction(data)
    val anomalyDetector = buildAnomalyDetector(data, normalizeFunction)
    val anomalies = originalAndData.filter(
      originalAndDatum => anomalyDetector(originalAndDatum._2)
    ).keys
    anomalies.take(10).foreach(println)
  }

}