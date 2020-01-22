# gfc-aws-cloudwatch [![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.gfccollective/gfc-aws-cloudwatch_2.12/badge.svg?style=plastic)](https://maven-badges.herokuapp.com/maven-central/org.gfccollective/gfc-aws-cloudwatch_2.12) [![Join the chat at https://gitter.im/gilt/gfc](https://badges.gitter.im/gilt/gfc.svg)](https://gitter.im/gilt/gfc?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

A thin Scala wrapper around AWS CloudWatch Java client.
A fork and new home of the former Gilt Foundation Classes (`com.gilt.gfc`), now called the [GFC Collective](https://github.com/gfc-collective), maintained by some of the original authors.


## Getting gfc-aws-cloudwatch

The latest version of gfc-aws-cloudwatch using the AWS 1.x libraries is 1.4.1 (maintained on the `1.x` branch), 
The latest version of gfc-aws-cloudwatch using the AWS 2.x libraries is 2.0.0 (maintained on the `master` branch).
Both versions are cross-compiled against Scala 2.12.x and 2.13.x.

If you're using SBT, add the following line to your build file:

```scala
libraryDependencies += "org.gfccollective" %% "gfc-aws-cloudwatch" % "2.0.0"
```

For Maven and other build tools, you can visit [search.maven.org](http://search.maven.org/#search%7Cga%7C1%7Corg.gfccollective).
(This search will also list other available libraries from the GFC Collective.)

# Basic usage

Quick metric aggregator example
(less flexible than CW APIs but can save costs when you have high-frequency events):
```scala
  val cwPublisher = CloudWatchMetricsPublisher.start(1 minute)

  val SubServiceMetricBuilder = {
    CloudWatchMetricDataAggregator.builder(cwPublisher).withMetricNamespace("TopLevelNamespace")
  }

  val Count = SubServiceMetricBuilder.withUnit(StandardUnit.Count)

  val SuccessCount = Count.withMetricName("success")

  def kind = new Dimension().withName("kind")

  val SuccessfulCreate = SuccessCount.addDimensions(kind.withValue("create"))

  val SuccessfulUpdate = SuccessCount.addDimensions(kind.withValue("update"))

  val SuccessfulCreateFoo = SuccessfulCreate.enterMetricNamespace("foo").start()
  val SuccessfulUpdateFoo = SuccessfulUpdate.enterMetricNamespace("foo").start()

  val SuccessfulCreateBar = SuccessfulCreate.enterMetricNamespace("bar").start()
  val SuccessfulUpdateBar = SuccessfulUpdate.enterMetricNamespace("bar").start()

..........

  SuccessfulCreateFoo.increment()
  SuccessfulUpdateFoo.increment()
  .....

```

Quick example:
```scala
implicit
object FooMetricToCloudWatchMetricsData
  extends ToCloudWatchMetricsData[Foo] {

  override
  def toMetricData( t: Foo
                  ): Seq[MetricDatum] = {
    // convert to metric data
  }
}

// ..........

CloudWatchMetricsClient("TopLevelNamespace").
  enterNamespace("foo"). // optionally enter more specific namespace
  putMetricData(someFoo)


implicit
object FooMetricToCloudWatchLogsData
  extends ToCloudWatchLogsData[Foo] {

  override
  def toLogEvents( t: Foo
                  ): Seq[InputLogEvent] = {
    // convert to log events
  }
}

// ..........

CloudWatchLogsClient("TopLevelNamespace").
  enterNamespace("foo"). // optionally enter more specific namespace
  putLogData("streamName", someFoo)

```

## License

Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
