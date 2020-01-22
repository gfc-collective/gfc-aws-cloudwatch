package org.gfccollective.aws.cloudwatch.periodic.metric.aggregator


import java.util.concurrent.atomic.AtomicReference

import com.amazonaws.services.cloudwatch.model._
import org.gfccollective.aws.cloudwatch.periodic.metric.{CloudWatchMetricDataAggregator, CloudWatchMetricsPublisher}
import org.gfccollective.logging.Loggable

import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.util.control.NonFatal


/** Used to build CloudWatchMetricDataAggregator incrementally.
  * Since case class is immutable, it's possible to e.g. construct
  * an instance with a few parameters filled in and then use it to construct
  * more specialized metric aggregators.
  */
case class CloudWatchMetricDataAggregatorBuilder private[metric] (
  publisherOpt: Option[CloudWatchMetricsPublisher] = None
, metricName: Option[String] = None
, metricNamespace: Option[String] = None
, metricUnit: StandardUnit = StandardUnit.None
, metricDimensions: Seq[Seq[Dimension]] = Seq.empty
, interval: FiniteDuration = 1 minute
, sendZeroSample: Boolean = true
) extends Loggable {

  import CloudWatchMetricDataAggregatorBuilder._
  import org.gfccollective.aws.cloudwatch.periodic.metric.aggregator.Stats.{NoData, Zero}

  /** Name of the aggregated metric. */
  def withPublisher( p: CloudWatchMetricsPublisher
                    ): CloudWatchMetricDataAggregatorBuilder = {

    this.copy(publisherOpt = Some(p))
  }

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

    require(i.toSeconds >= 1, "interval must be greater or equal than 1 second") // doesn't make sense to aggregate for less than that, you don't get a better resolution in the graphs
    this.copy(interval = i)
  }

  /** Send a sample of value 0 when no metrics have been collected in the interval, to avoid 'insufficient data' state. Default is 'true'. */
  def withZeroSample( i: Boolean
                  ): CloudWatchMetricDataAggregatorBuilder = {

    this.copy(sendZeroSample = i)
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

  /** namespace used when publishing. Exposed for testing */
  private[metric] def sanitizedNamespace: Option[String] = metricNamespace.map(_.limit())

  /** name used when publishing. Exposed for testing */
  private[metric] def sanitizedName: Option[String] = metricName.map(_.limit())

  /** Constructs CloudWatchMetricDataAggregator and starts submitting collected metrics. */
  def start(): CloudWatchMetricDataAggregator = new CloudWatchMetricDataAggregator {

    // We could require them but that makes it harder to use partially constructed
    // builder objects. E.g. you might want to fill in a few parameters to define
    // a request counter but then set different namespaces/dimensions on top of that before you
    // start.
    val namespace = sanitizedNamespace.getOrElse(throw new RuntimeException("Please call withMetricNamespace() to give metric a namespace!"))
    val name = sanitizedName.getOrElse(throw new RuntimeException("Please call withMetricName() to give metric a name!"))
    val publisher = publisherOpt.getOrElse(throw new RuntimeException("Please call withPublisher() to pass a publisher!"))

    def sanitizeDimensions(dims: Seq[Dimension]): Seq[Dimension] = dims.map { dim =>
      new Dimension().withName(dim.getName.limit(n = DimNameMaxStrLen)).withValue(dim.getValue.limit(allowedCharsRx = DimValueAllowedChars))
    }

    implicit
    val statsToCloudWatchMetricData = Stats.statsToCloudWatchMetricData(name, metricUnit, metricDimensions.map(sanitizeDimensions))

    val exeFuture = publisher.executor.scheduleAtFixedRate(interval, interval){ dump() }
    val currentValue = new AtomicReference[Stats](Zero)

    info(s"Started CloudWatch metric data aggregation for [${namespace}]-[${name}] with interval ${interval}")

    override
    def stop(): Unit = synchronized {
      exeFuture.cancel(false)
      dump()
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

      if (v != Zero) {
        publisher.enqueue(namespace, v)
      } else if (sendZeroSample) {
        publisher.enqueue(namespace, NoData) // this sends 1 sample of value 0 to avoid 'insufficient data' state
      }
    } catch {
      case NonFatal(e) => error(e.getMessage, e)
    }
  }
}


private[metric]
object CloudWatchMetricDataAggregatorBuilder
  extends Loggable {

  // Dimension names must be 250 characters or less,
  // everything else (dimension values, metric names and metric namespaces) must be 256 characters or less
  private val DimNameMaxStrLen = 250
  private val GenericMaxStrLen = 256

  private val DimValueAllowedChars = """[0-9A-Za-z.\-_/#:* ]"""
  private val GenericAllowedChars  = """[0-9A-Za-z.\-_/#: ]"""

  // Sanitizes string values so they comply with the AWS specifications (valid XML characters of a particular max length)
  private implicit class StringValidator(val s: String) extends AnyVal {
    def limit(n: Int = GenericMaxStrLen, allowedCharsRx: String = GenericAllowedChars): String = s.filter(c => c.toString.matches(allowedCharsRx)).take(n)
  }
}
