/*
 * Copyright 2019 Pierre Nodet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.ml.regression

import java.util.UUID

import org.apache.hadoop.fs.Path
import org.apache.spark.SparkContext
import org.apache.spark.ml.Predictor
import org.apache.spark.ml.bagging.BaggingParams
import org.apache.spark.ml.ensemble.HasSubBag.SubSpace
import org.apache.spark.ml.ensemble.{
  EnsemblePredictionModelType,
  EnsemblePredictorType,
  HasBaseLearner
}
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.ml.param._
import org.apache.spark.ml.param.shared.HasWeightCol
import org.apache.spark.ml.util.Instrumentation.instrumented
import org.apache.spark.ml.util._
import org.apache.spark.sql.Dataset
import org.apache.spark.storage.StorageLevel
import org.apache.spark.util.ThreadUtils
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.{DefaultFormats, JObject}

import scala.concurrent.Future
import scala.concurrent.duration.Duration

private[ml] trait BaggingRegressorParams extends BaggingParams {}

private[ml] object BaggingRegressorParams {

  def saveImpl(
      instance: BaggingRegressorParams,
      path: String,
      sc: SparkContext,
      extraMetadata: Option[JObject] = None): Unit = {

    val params = instance.extractParamMap().toSeq
    val jsonParams = render(
      params
        .filter { case ParamPair(p, _) => p.name != "baseLearner" }
        .map { case ParamPair(p, v) => p.name -> parse(p.jsonEncode(v)) }
        .toList)

    DefaultParamsWriter.saveMetadata(instance, path, sc, extraMetadata, Some(jsonParams))
    HasBaseLearner.saveImpl(instance, path, sc)

  }

  def loadImpl(
      path: String,
      sc: SparkContext,
      expectedClassName: String): (DefaultParamsReader.Metadata, EnsemblePredictorType) = {

    val metadata = DefaultParamsReader.loadMetadata(path, sc, expectedClassName)
    val learner = HasBaseLearner.loadImpl(path, sc)
    (metadata, learner)

  }

}

class BaggingRegressor(override val uid: String)
    extends Predictor[Vector, BaggingRegressor, BaggingRegressionModel]
    with BaggingRegressorParams
    with MLWritable {

  def this() = this(Identifiable.randomUID("BaggingRegressor"))

  /** @group setParam */
  def setBaseLearner(value: Predictor[_, _, _]): this.type =
    set(baseLearner, value.asInstanceOf[EnsemblePredictorType])

  /** @group setParam */
  def setWeightCol(value: String): this.type = set(weightCol, value)

  /** @group setParam */
  def setReplacement(value: Boolean): this.type = set(replacement, value)

  /** @group setParam */
  def setSampleRatio(value: Double): this.type = set(sampleRatio, value)

  /** @group setParam */
  def setSubspaceRatio(value: Double): this.type = set(subspaceRatio, value)

  /** @group setParam */
  def setNumBaseLearners(value: Int): this.type = set(numBaseLearners, value)

  /**
   * Set the maximum level of parallelism to evaluate models in parallel.
   * Default is 1 for serial evaluation
   *
   * @group expertSetParam
   */
  def setParallelism(value: Int): this.type = set(parallelism, value)

  override def copy(extra: ParamMap): BaggingRegressor = {
    val copied = new BaggingRegressor(uid)
    copyValues(copied, extra)
    copied.setBaseLearner(copied.getBaseLearner.copy(extra))
  }

  override protected def train(dataset: Dataset[_]): BaggingRegressionModel = instrumented {
    instr =>
      instr.logPipelineStage(this)
      instr.logDataset(dataset)
      instr.logParams(
        this,
        labelCol,
        weightCol,
        featuresCol,
        predictionCol,
        numBaseLearners,
        sampleRatio,
        replacement,
        subspaceRatio,
        seed)

      val weightColIsUsed = isDefined(weightCol) && $(weightCol).nonEmpty && {
        getBaseLearner match {
          case _: HasWeightCol => true
          case c =>
            instr.logWarning(s"weightCol is ignored, as it is not supported by $c now.")
            false
        }
      }

      val df = if (weightColIsUsed) {
        dataset.select($(labelCol), $(weightCol), $(featuresCol))
      } else {
        dataset.select($(labelCol), $(featuresCol))
      }

      val optWeightColName = if (weightColIsUsed) {
        Some($(weightCol))
      } else {
        None
      }

      val handlePersistence = dataset.storageLevel == StorageLevel.NONE && (df.storageLevel == StorageLevel.NONE)
      if (handlePersistence) {
        df.persist(StorageLevel.MEMORY_AND_DISK)
      }

      val bagColName = "gbm$bag" + UUID.randomUUID().toString
      val bagged = df.transform(
        withBag(getReplacement, getSampleRatio, getNumBaseLearners, getSeed, bagColName))

      val numFeatures = getNumFeatures(df, getFeaturesCol)

      val futureModels = Array
        .range(0, getNumBaseLearners)
        .map(iter =>
          Future[(Array[Int], EnsemblePredictionModelType)] {

            val subspace = mkSubspace(getSampleRatio, numFeatures, getSeed + iter)

            val subbag =
              bagged.transform(extractSubBag(bagColName, iter, getFeaturesCol, subspace))

            val bagger =
              fitBaseLearner(
                getBaseLearner,
                getLabelCol,
                getFeaturesCol,
                getPredictionCol,
                optWeightColName)(subbag)

            (subspace, bagger)

          }(getExecutionContext))

      val (subspaces, models) = futureModels.map(ThreadUtils.awaitResult(_, Duration.Inf)).unzip

      if (handlePersistence) {
        df.unpersist()
      }

      new BaggingRegressionModel(subspaces, models)

  }

  override def write: MLWriter = new BaggingRegressor.BaggingRegressorWriter(this)

}

object BaggingRegressor extends MLReadable[BaggingRegressor] {

  override def read: MLReader[BaggingRegressor] = new BaggingRegressorReader

  override def load(path: String): BaggingRegressor = super.load(path)

  private[BaggingRegressor] class BaggingRegressorWriter(instance: BaggingRegressor)
      extends MLWriter {

    override protected def saveImpl(path: String): Unit = {
      BaggingRegressorParams.saveImpl(instance, path, sc)
    }

  }

  private class BaggingRegressorReader extends MLReader[BaggingRegressor] {

    /** Checked against metadata when loading model */
    private val className = classOf[BaggingRegressor].getName

    override def load(path: String): BaggingRegressor = {
      val (metadata, learner) = BaggingRegressorParams.loadImpl(path, sc, className)
      val bc = new BaggingRegressor(metadata.uid)
      metadata.getAndSetParams(bc)
      bc.setBaseLearner(learner)
    }
  }

}

class BaggingRegressionModel(
    override val uid: String,
    val subspaces: Array[SubSpace],
    val models: Array[EnsemblePredictionModelType])
    extends RegressionModel[Vector, BaggingRegressionModel]
    with BaggingRegressorParams
    with MLWritable {

  def this(subSpaces: Array[Array[Int]], models: Array[EnsemblePredictionModelType]) =
    this(Identifiable.randomUID("BaggingRegressionModel"), subSpaces, models)

  val numBaseModels: Int = models.length

  override def predict(features: Vector): Double = {
    breeze.linalg.sum(
      models
        .zip(subspaces)
        .map {
          case (model, subspace) =>
            model.predict(slicer(subspace)(features))
        }) / numBaseModels
  }

  override def copy(extra: ParamMap): BaggingRegressionModel = {
    val copied = new BaggingRegressionModel(uid, subspaces, models)
    copyValues(copied, extra).setParent(parent)
  }

  override def write: MLWriter = new BaggingRegressionModel.BaggingRegressionModelWriter(this)

}

object BaggingRegressionModel extends MLReadable[BaggingRegressionModel] {

  override def read: MLReader[BaggingRegressionModel] = new BaggingRegressionModelReader

  override def load(path: String): BaggingRegressionModel = super.load(path)

  private[BaggingRegressionModel] class BaggingRegressionModelWriter(
      instance: BaggingRegressionModel)
      extends MLWriter {

    private case class Data(subspace: SubSpace)

    override protected def saveImpl(path: String): Unit = {
      val extraJson = "numBaseModels" -> instance.numBaseModels
      BaggingRegressorParams.saveImpl(instance, path, sc, Some(extraJson))
      instance.models.map(_.asInstanceOf[MLWritable]).zipWithIndex.foreach {
        case (model, idx) =>
          val modelPath = new Path(path, s"model-$idx").toString
          model.save(modelPath)
      }
      instance.subspaces.zipWithIndex.foreach {
        case (subSpace, idx) =>
          val data = Data(subSpace)
          val dataPath = new Path(path, s"data-$idx").toString
          sparkSession.createDataFrame(Seq(data)).repartition(1).write.json(dataPath)
      }

    }
  }

  private class BaggingRegressionModelReader extends MLReader[BaggingRegressionModel] {

    /** Checked against metadata when loading model */
    private val className = classOf[BaggingRegressionModel].getName

    override def load(path: String): BaggingRegressionModel = {
      implicit val format: DefaultFormats = DefaultFormats
      val (metadata, baseLearner) = BaggingRegressorParams.loadImpl(path, sc, className)
      val numModels = metadata.getParamValue("numBaseLearners").extract[Int]
      val models = (0 until numModels).toArray.map { idx =>
        val modelPath = new Path(path, s"model-$idx").toString
        DefaultParamsReader.loadParamsInstance[EnsemblePredictionModelType](modelPath, sc)
      }
      val subspaces = (0 until numModels).toArray.map { idx =>
        val dataPath = new Path(path, s"data-$idx").toString
        val data = sparkSession.read.json(dataPath).select("subspace").head()
        data.getAs[Seq[Long]](0).map(_.toInt).toArray
      }
      val bcModel = new BaggingRegressionModel(metadata.uid, subspaces, models)
      metadata.getAndSetParams(bcModel)
      bcModel.set("baseLearner", baseLearner)
      bcModel
    }
  }
}
