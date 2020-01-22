package org.gfccollective.aws.cloudwatch.periodic.metric

import com.amazonaws.services.cloudwatch.model.MetricDatum
import org.gfccollective.aws.cloudwatch.ToCloudWatchMetricsData


package object aggregator {

  type NamespacedMetricDatum = (String, MetricDatum)


  // Un-wraps values produced by groupBy()
  implicit object SeqNamespacedMetricDatumToCWMetricData
    extends ToCloudWatchMetricsData[Seq[NamespacedMetricDatum]] {

    override
    def toMetricData( data: Seq[(String, MetricDatum)]
                    ): Seq[MetricDatum] = {
      data.map(_._2)
    }
  }

}
