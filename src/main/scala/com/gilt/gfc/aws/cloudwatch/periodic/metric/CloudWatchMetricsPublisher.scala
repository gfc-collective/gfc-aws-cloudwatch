package com.gilt.gfc.aws.cloudwatch.periodic.metric

import java.util.concurrent.{ScheduledFuture, TimeUnit}

import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.util.control.NonFatal
import com.amazonaws.services.cloudwatch.{AmazonCloudWatch, AmazonCloudWatchClientBuilder}
import com.gilt.gfc.aws.cloudwatch.{CloudWatchMetricsClient, ToCloudWatchMetricsData}
import com.gilt.gfc.aws.cloudwatch.periodic.metric.aggregator.{NamespacedMetricDatum, Stats, WorkQueue}
import com.gilt.gfc.concurrent.JavaConverters._
import com.gilt.gfc.concurrent.{AsyncScheduledExecutorService, ThreadFactoryBuilder}
import com.gilt.gfc.logging.Loggable


trait CloudWatchMetricsPublisher {
  /** Stops background tasks. */
  def stop(): ScheduledFuture[_]

  /** Completely shuts down, can not be restarted. */
  def shutdown(): Unit

  private[metric] def executor: AsyncScheduledExecutorService

  private[metric] def enqueue(metricNamespace: String, datum: Stats)(implicit ev: ToCloudWatchMetricsData[Stats])
}

object CloudWatchMetricsPublisher {
  private
  lazy val defaultExecutor: AsyncScheduledExecutorService = {
    import java.util.concurrent._

    Executors.newScheduledThreadPool(
      1, // core pool size
      ThreadFactoryBuilder("CloudWatchMetricDataAggregator", "CloudWatchMetricDataAggregator").build()
    )

  }.asScala

  /** Starts a background task that periodically dumps aggregated metric data to CW.
    *
    * @param interval how frequently to dump data to CW. This is intentionally different
    *                 from the metric aggregation interval. E.g. you may be aggregating
    *                 metrics for 1min but dump them to CW every 5min, thus taking advantage
    *                 of larger batch size in API calls and reducing costs.
    */
  def start(
      interval: FiniteDuration
    , awsCloudWatch: AmazonCloudWatch = AmazonCloudWatchClientBuilder.defaultClient()
    , executorService: AsyncScheduledExecutorService = defaultExecutor
  ): CloudWatchMetricsPublisher = CloudWatchMetricsPublisherImpl(interval, awsCloudWatch, executorService)

}

private[metric]
case class CloudWatchMetricsPublisherImpl(interval: FiniteDuration
                                        , awsCloudWatch: AmazonCloudWatch
                                        , override val executor: AsyncScheduledExecutorService
  ) extends CloudWatchMetricsPublisher with Loggable {

  import aggregator._

  private
  val metricsDataQueue = new WorkQueue[Stats]()

  private[this]
  val CWPutMetricDataBatchLimit = 20 // http://docs.aws.amazon.com/AmazonCloudWatch/latest/DeveloperGuide/cloudwatch_limits.html



  // Periodically dump all enqueued stats (they preserve original timestamps because we use withTimestamp())
  // to CW, in as few calls as possible.
  private[this]
  val runningFuture = executor.scheduleAtFixedRate(interval, interval) { publish() }

  info(s"Started CW metrics publisher background task with an interval [${interval}]")

  override def stop(): ScheduledFuture[_] = synchronized {
    info("Stopping CW metrics publisher")
    publish()
    val res = runningFuture
    try {
      runningFuture.cancel(false)
    } catch {
      case e: Throwable =>
        error(s"Failed to stop cleanly: ${e.getMessage}", e)
    }
    res
  }


  override def shutdown(): Unit = synchronized {
    info("Shutting down CW metrics publisher")
    try {
      try {
        stop().get(3L, TimeUnit.SECONDS) // give it a chance to shut down cleanly
      } finally {
        executor.shutdown()
      }
    } catch {
      case e: Throwable =>
        error(s"Failed to shut down cleanly: ${e.getMessage}", e)
    }
  }

  override private[metric]
  def enqueue(metricNamespace: String, datum: Stats)(implicit ev: ToCloudWatchMetricsData[Stats]): Unit = {
    metricsDataQueue.enqueue(metricNamespace, datum)
  }

  private[this]
  def publish(): Unit = try {
    val metricNameToData: Map[String, Seq[NamespacedMetricDatum]] = metricsDataQueue.drain().toSeq.groupBy(_._1)

    metricNameToData.foreach { case (metricNamespace, namespacedMetricData) =>
      namespacedMetricData.grouped(CWPutMetricDataBatchLimit).foreach { batch => // send full batches if possible
        // each CW metric batch is bound to a single metric namespace, wrapper is light weight
        CloudWatchMetricsClient(metricNamespace, awsCloudWatch).putMetricData(batch)
        info(s"Published ${batch.size} metrics to [${metricNamespace}]")
      }
    }
  } catch {
    case NonFatal(e) => error(e.getMessage, e)
  }
}
