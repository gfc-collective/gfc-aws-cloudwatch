package org.gfccollective.aws.cloudwatch.periodic.metric

import com.amazonaws.services.cloudwatch.{AmazonCloudWatch, AmazonCloudWatchClientBuilder}
import org.gfccollective.aws.cloudwatch.periodic.metric.aggregator.CloudWatchMetricDataAggregatorBuilder

import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

/**
 * Maintains state necessary to aggregate metrics on the client side.
 * Interface is different and less flexible than underlying CW API but
 * it results in fewer API calls, which are expensive.
 */
trait CloudWatchMetricDataAggregator {

  /** Handles a single metric datum sample value. */
  def sampleValue(v: Double): Unit

  /** A common use case, increment by 1. */
  def increment(): Unit = { sampleValue(1) }

  /** Stops background tasks. */
  def stop(): Unit
}



object CloudWatchMetricDataAggregator {

  /** Constructs a builder (immutable) that collects all the necessary parameters as well
    * as allows for a partial construction of CloudWatchMetricDataAggregator objects,
    * with common groups of parameters shared by multiple aggregated metrics.
    *
    * N.B. fully constructed CloudWatchMetricDataAggregator instances start to collect
    *      metrics immediately, so a global background task that dumps them to CW needs
    *      to be started first, please call start() somewhere early in your app startup sequence.
    *
    * @return builder instance that you can start customizing
    */
  def builder(publisher: CloudWatchMetricsPublisher): CloudWatchMetricDataAggregatorBuilder = {
    CloudWatchMetricDataAggregatorBuilder().withPublisher(publisher)
  }


  /** Constructs a builder (immutable) that collects all the necessary parameters as well
    * as allows for a partial construction of CloudWatchMetricDataAggregator objects,
    * with common groups of parameters shared by multiple aggregated metrics.
    *
    * N.B. fully constructed CloudWatchMetricDataAggregator instances start to collect
    *      metrics immediately, so a global background task that dumps them to CW needs
    *      to be started first, please call start() somewhere early in your app startup sequence.
    *
    * @return builder instance that you can start customizing
    */
  @deprecated("Use builder(CloudWatchMetricsPublisher)", "1.2.0")
  def builder(): CloudWatchMetricDataAggregatorBuilder = {

    // This is needed to make sure we consume what aggregated metrics start to produce, otherwise
    // system will OOM.
    publisher.map(CloudWatchMetricDataAggregatorBuilder().withPublisher(_)).getOrElse(
      throw new AssertionError("Please call start() before building any aggregated metrics.")
    )
  }


  /** Starts a background task that periodically dumps aggregated metric data to CW.
    *
    * @param interval how frequently to dump data to CW. This is intentionally different
    *                 from the metric aggregation interval. E.g. you may be aggregating
    *                 metrics for 1min but dump them to CW every 5min, thus taking advantage
    *                 of larger batch size in API calls and reducing costs.
    */
  @deprecated("Use CloudWatchMetricsPublisher.start()", "1.2.0")
  def start( interval: FiniteDuration
           ): Unit = synchronized {
    if (publisher.isEmpty) {
      publisher = Some(CloudWatchMetricsPublisher.start(interval))
    }
  }


  /** Stops background tasks. */
  @deprecated("Use CloudWatchMetricsPublisher.stop()", "1.2.0")
  def stop(): Unit = synchronized {
    publisher.foreach(_.stop())
  }


  /** Completely shuts down, can not be restarted. */
  @deprecated("Use CloudWatchMetricsPublisher.shutdown()", "1.2.0")
  def shutdown(): Unit = synchronized {
    publisher.foreach(_.shutdown())
  }


  @volatile private[this]
  var publisher: Option[CloudWatchMetricsPublisher] = None
}
