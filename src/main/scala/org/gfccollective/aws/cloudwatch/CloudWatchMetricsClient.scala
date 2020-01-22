package org.gfccollective.aws.cloudwatch

import com.amazonaws.services.cloudwatch.{AmazonCloudWatch, AmazonCloudWatchClientBuilder}
import com.amazonaws.services.cloudwatch.model.{MetricDatum, PutMetricDataRequest}
import org.gfccollective.concurrent.JavaConverters._
import org.gfccollective.concurrent.{ExecutorService, SameThreadExecutionContext}
import org.gfccollective.logging.OpenLoggable

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.Success
import scala.util.control.NonFatal


/** A type class of things that can be converted to CloudWatch Metrics. */
trait ToCloudWatchMetricsData[A] {
  /** A function to convert type A to CW metrics. */
  def toMetricData(a: A): Seq[MetricDatum]
}

/**
 * Push selected metrics to AWS CloudWatch.
 * Some are meant to be 'system health' metrics, like our Future-based RPC call times.
 * Some are meant to be 'business' metrics, like number of logins.
 *
 * Trait exists mainly to make it possible to inject this as a dependency.
 */
trait CloudWatchMetricsClient {

  /** Event namespace in CloudWatch will be a concatenation of all of these.
    * Every time you enter a namespace it'll get appended to the current one, e.g. "a / b / c".
    */
  def enterNamespace( n: String
                    ): CloudWatchMetricsClient


  /** Run a closure with a namespaced CW metrics client, just a convenience function.
    *
    * @param n    namespace to enter
    * @param run  closure to run
    * @tparam R   closure result type
    * @return     closure result
    */
  def withNamespace[R]( n: String
                     )( run: (CloudWatchMetricsClient) => R
                      ): R = {
    run(this.enterNamespace(n))
  }


  /** Requests metric data to be sent asynchronously.
    * It will block only to schedule async tasks, shouldn't be noticeable under low contention.
    * HTTP service calls are asynchronous.
    *
    * http://docs.aws.amazon.com/AmazonCloudWatch/latest/APIReference/API_PutMetricData.html
    *
    * @param a                object to send metrics data for
    * @param tcwmdEv          evidence that ToCloudWatchMetricsData implementation exists for type A
    * @tparam A               type of the object we convert to metrics data
    */
  def putMetricData[A]( a: A
                     )( implicit tcwmdEv: ToCloudWatchMetricsData[A]
                      ): Unit


  /** Brackets a block of code and puts a given metric if an exception occurs.
    *
    * @param t2a       construct metric from throwable (e.g. you may want to report different exceptions differently)
    * @param run       block of code to run
    * @param tcwmdEv   evidence that ToCloudWatchMetricsData implementation exists for type A
    * @tparam A        type of metric
    * @tparam R        type of result
    * @return          result of the execution of the given closure
    */
  def putExceptionMetricData[A,R]( t2a: (Throwable) => A
                                )( run: => R
                                )( implicit tcwmdEv: ToCloudWatchMetricsData[A]
                                 ): R = {
    try {
      run
    } catch {
      case NonFatal(e) =>
        this.putMetricData(t2a(e)) // report exception and re-throw it
        throw e
    }
  }


  /** Brackets a block of asynchronous code (Future result) and puts a given metric if an exception occurs
    * either synchronously or wrapped in the result of the Future.
    *
    * @param t2a       construct metric from throwable (e.g. you may want to report different exceptions differently)
    * @param run       block of code to run
    * @param tcwmdEv   evidence that ToCloudWatchMetricsData implementation exists for type A
    * @tparam A        type of metric
    * @tparam R        type of result
    * @return          result of the execution of the given closure
    */
  def putAsynchronousExceptionMetricData[A,R]( t2a: (Throwable) => A
                                            )( run: => Future[R]
                                            )( implicit tcwmdEv: ToCloudWatchMetricsData[A]
                                            ): Future[R] = {

    val safeFuture: Future[R] = this.putExceptionMetricData(t2a)(run) // if run() explodes before returning a future we capture it here

    implicit val ec = SameThreadExecutionContext // putMetricData should be lightweight

    safeFuture.onComplete {
      case Success(NonFatal(e)) =>
        this.putMetricData(t2a(e))
      case _ =>
        ()
    }

    safeFuture
  }
}


object CloudWatchMetricsClient {

  /** Creates default implementation of the CloudWatchMetricsClient.
    *
    * http://docs.aws.amazon.com/AmazonCloudWatch/latest/APIReference/API_PutMetricData.html
    *
    * @param metricNamespace CloudWatch metric namespace
    * @return CloudWatchMetricsClient instance
    */
  def apply( metricNamespace: String
           , awsCloudWatch: AmazonCloudWatch = AmazonCloudWatchClientBuilder.defaultClient()
           ): CloudWatchMetricsClient = CloudWatchMetricsClientImpl(metricNamespace, awsCloudWatch)

}


/** Default implementation of CloudWatchMetricsClient, trait is for dependency injection. */
private[cloudwatch]
object CloudWatchMetricsClientImpl {

  private
  val Logger = new OpenLoggable {}

  private
  val executor: ExecutorService = {
    import java.util.concurrent._

    new ThreadPoolExecutor(
      1 // core pool size
    , 2 * Runtime.getRuntime.availableProcessors // max pool size
    , 60L, TimeUnit.SECONDS, // keep alive
      new LinkedBlockingQueue[Runnable]()
    )
  }.asScala
}


private[cloudwatch]
case class CloudWatchMetricsClientImpl (
    namespace: String
  , awsClient: AmazonCloudWatch
) extends CloudWatchMetricsClient {

  import CloudWatchMetricsClientImpl._

  override
  def enterNamespace( n: String
                    ): CloudWatchMetricsClient = {
    this.copy(namespace = s"${this.namespace} / ${n}")
  }

  override
  def putMetricData[A]( a: A
                      )( implicit tcwmdEv: ToCloudWatchMetricsData[A]
                      ): Unit = executor.execute {
    try {
      awsClient.putMetricData(
        new PutMetricDataRequest().
          withNamespace(namespace).
          withMetricData(tcwmdEv.toMetricData(a).asJava)
      )
    } catch {
      case NonFatal(e) =>
        Logger.error(e.getMessage, e)
    }

    ()
  }
}



