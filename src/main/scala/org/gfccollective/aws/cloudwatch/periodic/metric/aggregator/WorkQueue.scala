package org.gfccollective.aws.cloudwatch.periodic.metric.aggregator

import java.util.concurrent.ConcurrentLinkedQueue

import com.amazonaws.services.cloudwatch.model._
import org.gfccollective.aws.cloudwatch.ToCloudWatchMetricsData

import scala.language.postfixOps


private[metric]
case class WorkQueueItem (
  metricNamespace: String
, data: Seq[MetricDatum]
)


private[metric]
class WorkQueue[D] {

  def enqueue( metricNamespace: String
             , datum: D
            )( implicit tcwmd:  ToCloudWatchMetricsData[D]
             ): Unit = {
    workQueue.add(WorkQueueItem(metricNamespace, tcwmd.toMetricData(datum)))
  }


  def drain(): Iterator[NamespacedMetricDatum] = {

    val qSeqIt = new Iterator[WorkQueueItem] {

      var maybeItem = consumeOne()

      override
      def hasNext: Boolean = maybeItem.isDefined

      override
      def next(): WorkQueueItem = {
        val i = maybeItem.getOrElse(throw new NoSuchElementException("next on empty iterator"))
        maybeItem = consumeOne()
        i
      }

      private[this]
      def consumeOne(): Option[WorkQueueItem] = {
        Option(workQueue.poll())
      }
    }

    for {
      WorkQueueItem(metricNamespace, data) <- qSeqIt
      datum <- data.iterator
    } yield {
      metricNamespace -> datum
    }
  }


  private[this]
  val workQueue = new ConcurrentLinkedQueue[WorkQueueItem]()

}
