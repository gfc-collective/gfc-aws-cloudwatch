package org.gfccollective.aws.cloudwatch.periodic.metric.aggregator

import com.amazonaws.services.cloudwatch.model.MetricDatum
import org.gfccollective.aws.cloudwatch.ToCloudWatchMetricsData
import org.specs2.mutable.Specification


class WorkQueueSpec
  extends Specification {

  implicit
  val StringToCloudWatchMetricsData = new ToCloudWatchMetricsData[String] {
    override
    def toMetricData( a: String
                    ): Seq[MetricDatum] = {
      Seq( new MetricDatum().withUnit(a).withValue(1.0) )
    }
  }

  "WorkQueue" should {

    "drain empty queue into an empty iterator" in {
      val q = new WorkQueue[String]()
      q.drain().hasNext should beFalse // should not explode
    }

    "drain non-empty queue into an iterator" in {
      val q = new WorkQueue[String]()
      q.enqueue("nsFoo", "foo1")
      q.enqueue("nsFoo", "foo2")
      q.enqueue("nsBar", "bar1")
      q.enqueue("nsBar", "bar2")

      val it = q.drain()

      it.hasNext should beTrue

      it.toList.groupBy(_._1).mapValues(_.map(_._2)).toSeq.map(_.toString).sorted.mkString("[", ", ", "]") shouldEqual(
        "[(nsBar,List({Dimensions: [],Value: 1.0,Values: [],Counts: [],Unit: bar1,}, {Dimensions: [],Value: 1.0,Values: [],Counts: [],Unit: bar2,})), (nsFoo,List({Dimensions: [],Value: 1.0,Values: [],Counts: [],Unit: foo1,}, {Dimensions: [],Value: 1.0,Values: [],Counts: [],Unit: foo2,}))]"
      )
    }
  }
}
