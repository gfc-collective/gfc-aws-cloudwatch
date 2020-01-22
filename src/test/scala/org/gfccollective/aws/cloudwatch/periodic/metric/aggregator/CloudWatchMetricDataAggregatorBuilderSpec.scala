package org.gfccollective.aws.cloudwatch.periodic.metric.aggregator

import org.specs2.mutable.Specification

class CloudWatchMetricDataAggregatorBuilderSpec
  extends Specification {

  "Builder" should {
    "allow spaces in namespace" in {
      val space = CloudWatchMetricDataAggregatorBuilder(metricNamespace = Some("Test"))
        .enterMetricNamespace("spaces")

      val manyspaces = CloudWatchMetricDataAggregatorBuilder(metricNamespace = Some("Test"))
        .enterMetricNamespace("many spaces")

      space.sanitizedNamespace shouldNotEqual None
      space.sanitizedNamespace.get shouldEqual "Test / spaces"

      manyspaces.sanitizedNamespace shouldNotEqual None
      manyspaces.sanitizedNamespace.get shouldEqual "Test / many spaces"
    }
  }

  "not allow new lines" in {
    val newline = CloudWatchMetricDataAggregatorBuilder(metricNamespace = Some("Test \nnew line"))

    newline.sanitizedNamespace shouldNotEqual None
    newline.sanitizedNamespace.get shouldEqual "Test new line"
  }

}
