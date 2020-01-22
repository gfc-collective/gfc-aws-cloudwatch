package org.gfccollective.aws.cloudwatch.periodic.metric.aggregator

import java.util.Date

import com.amazonaws.services.cloudwatch.model._
import org.gfccollective.aws.cloudwatch.ToCloudWatchMetricsData

import scala.collection.JavaConverters._
import scala.language.postfixOps


private[metric]
case class Stats(
  sampleCount: Long
, sum: Double
, min: Double
, max: Double
) {

  def addSample( v: Double
               ): Stats = {
    Stats(
      sampleCount = this.sampleCount + 1L
    , sum = this.sum + v
    , min = this.min.min(v)
    , max = this.max.max(v)
    )
  }
}


private[metric]
object Stats {

  val Zero = Stats(0L, 0, 0, 0)

  val NoData = Zero.copy(sampleCount = 1L) // we send this to CW when there's no metric data to avoid 'insufficient data' state


  def statsToCloudWatchMetricData( metricName: String
                                 , metricUnit: StandardUnit
                                 , metricDimensions: Seq[Seq[Dimension]]
                                 ): ToCloudWatchMetricsData[Stats] = new ToCloudWatchMetricsData[Stats] {

    override
    def toMetricData( s: Stats
                    ): Seq[MetricDatum] = {

      val statsValues = new StatisticSet().
        withSampleCount(s.sampleCount.toDouble).
        withSum(s.sum).
        withMinimum(s.min).
        withMaximum(s.max)

      def md = new MetricDatum(). // def, not val, we mutate it
        withMetricName(metricName).
        withUnit(metricUnit).
        withStatisticValues(statsValues).
        withTimestamp(new Date())

      Seq(
        Seq(md) // publish dimensionless metric
        , metricDimensions.map(ds => md.withDimensions(ds.asJava)) // publish same metric with dimensions
      ) flatten
    }
  }
}
