package com.gilt.gfc.aws.cloudwatch.periodic.metric.aggregator


import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import com.amazonaws.services.cloudwatch.model._
import com.gilt.gfc.aws.cloudwatch.CloudWatchMetricsClient
import com.gilt.gfc.aws.cloudwatch.periodic.metric.CloudWatchMetricDataAggregator
import com.gilt.gfc.concurrent.JavaConverters._
import com.gilt.gfc.concurrent.ThreadFactoryBuilder
import com.gilt.gfc.logging.Loggable

import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.util.control.NonFatal


/** Used to build CloudWatchMetricDataAggregator incrementally.
  * Since case class is immutable, it's possible to e.g. construct
  * an instance with a few parameters filled in and then use it to construct
  * more specialized metric aggregators.
  */
case class CloudWatchMetricDataAggregatorBuilder private[metric] (
  metricName: Option[String] = None
, metricNamespace: Option[String] = None
, metricUnit: StandardUnit = StandardUnit.None
, metricDimensions: Seq[Seq[Dimension]] = Seq.empty
, interval: FiniteDuration = 1 minute
) extends Loggable {

  import CloudWatchMetricDataAggregatorBuilder.{executor, metricsDataQueue}
  import com.gilt.gfc.aws.cloudwatch.periodic.metric.aggregator.Stats.{NoData, Zero}


  /** Name of the aggregated metric. */
  def withMetricName( n: String
                    ): CloudWatchMetricDataAggregatorBuilder = {

    require(! n.isEmpty, "name must not be empty")
    this.copy(metricName = Some(n))
  }

  /** Full name of the CW metric, @see enterMetricNamespace() for additive version of the same. */
  def withMetricNamespace( ns: String
                         ): CloudWatchMetricDataAggregatorBuilder = {

    require(! ns.isEmpty, "namespace must not be empty")
    this.copy(metricNamespace = Some(ns))
  }

  /** A naming convention around grouping of related metrics into namespaces. */
  def enterMetricNamespace( ns: String
                          ): CloudWatchMetricDataAggregatorBuilder = {

    require(! ns.isEmpty, "namespace must not be empty")

    val newNs = this.metricNamespace match {
      case None => ns
      case Some(thisNs) => s"${thisNs} / ${ns}"
    }

    this.copy(metricNamespace = Some(newNs))
  }

  /** Metric unit. */
  def withUnit( u: StandardUnit
              ): CloudWatchMetricDataAggregatorBuilder = {

    this.copy(metricUnit = u)
  }

  /** Aggregation interval, 1 minute by default. */
  def withInterval( i: FiniteDuration
                  ): CloudWatchMetricDataAggregatorBuilder = {

    require(i.toMinutes > 1, "interval must be greater than 1 minute") // doesn't make sense to aggregate for less than that, you don't get a better resolution in the graphs
    this.copy(interval = i)
  }

  /** All combinations of dimensions that should be associated with this metric.
    * A dimensionless metric is always submitted too along side these.
    */
  def withDimensions( ds: Seq[Seq[Dimension]]
                    ): CloudWatchMetricDataAggregatorBuilder = {

    require(ds.filter(_.isEmpty).isEmpty, "dimensions must not be empty") // individual sets of dimensions shouldn't be empty, we already publish a dimensionless metric anyway
    this.copy(metricDimensions = ds)
  }

  /** Additive version of withDimensions(). */
  def addDimensions( ds: Dimension*
                   ): CloudWatchMetricDataAggregatorBuilder = {

    require(!ds.isEmpty, "dimensions must not be empty")
    this.copy(metricDimensions = this.metricDimensions :+ ds)
  }

  /** Constructs CloudWatchMetricDataAggregator and starts submitting collected metrics. */
  def start(): CloudWatchMetricDataAggregator = new CloudWatchMetricDataAggregator {

    // We could require them but that makes it harder to use partially constructed
    // builder objects. E.g. you might want to fill in a few parameters to define
    // a request counter but then set different namespaces/dimensions on top of that before you
    // start.
    val namespace = metricNamespace.getOrElse(throw new RuntimeException("Please call withMetricNamespace() to give metric a namespace!"))
    val name = metricName.getOrElse(throw new RuntimeException("Please call withMetricName() to give metric a name!"))

    implicit
    val statsToCloudWatchMetricData = Stats.statsToCloudWatchMetricData(name, metricUnit, metricDimensions)

    val exeFuture = executor.scheduleAtFixedRate(interval.toMillis, interval.toMillis, TimeUnit.MILLISECONDS){ dump() }
    val currentValue = new AtomicReference[Stats](Zero)

    info(s"Started CloudWatch metric data aggregation for [${namespace}]-[${name}] with interval ${interval}")

    override
    def stop(): Unit = {
      exeFuture.cancel(false)
    }

    override
    def sampleValue(v: Double): Unit = {
      var prev, next = Zero
      do {
        prev = currentValue.get
        next = prev.addSample(v)
      } while(!currentValue.compareAndSet(prev, next))
    }

    private[this]
    def dump(): Unit = try {
      val v = currentValue.getAndSet(Zero)

      if (v == Zero) {
        metricsDataQueue.enqueue(namespace, NoData) // this sends 1 sample of value 0 to avoid 'insufficient data' state
      } else {
        metricsDataQueue.enqueue(namespace, v)
      }
    } catch {
      case NonFatal(e) => error(e.getMessage, e)
    }
  }
}


private[metric]
object CloudWatchMetricDataAggregatorBuilder
  extends Loggable {

  private
  val metricsDataQueue = new WorkQueue[Stats]()

  private[this]
  val CWPutMetricDataBatchLimit = 20 // http://docs.aws.amazon.com/AmazonCloudWatch/latest/DeveloperGuide/cloudwatch_limits.html


  private
  val executor = {
    import java.util.concurrent._

    Executors.newScheduledThreadPool(
      1, // core pool size
      ThreadFactoryBuilder("CloudWatchMetricDataAggregator", "CloudWatchMetricDataAggregator").build()
    )

  }.asScala


  def start( interval: FiniteDuration
           ): Unit = {
    info(s"Started CW metrics aggregator background task with an interval [${interval}]")

    // Periodically dump all enqueued stats (they preserve original timestamps because we use withTimestamp())
    // to CW, in as few calls as possible.
    executor.scheduleAtFixedRate(interval.toMillis, interval.toMillis, TimeUnit.MILLISECONDS) {
      try {
        val metricNameToData: Map[String, Seq[NamespacedMetricDatum]] = metricsDataQueue.drain().toSeq.groupBy(_._1)

        metricNameToData.foreach { case (metricNamespace, namespacedMetricData) =>
          namespacedMetricData.grouped(CWPutMetricDataBatchLimit).foreach { batch => // send full batches if possible
            // each CW metric batch is bound to a single metric namespace, wrapper is light weight
            CloudWatchMetricsClient(metricNamespace).putMetricData(batch)
            info(s"Published ${batch.size} metrics to [${metricNamespace}]")
          }
        }
      } catch {
        case NonFatal(e) => error(e.getMessage, e)
      }
    }
  }
}