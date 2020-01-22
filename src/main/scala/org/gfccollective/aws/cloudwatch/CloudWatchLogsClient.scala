package org.gfccollective.aws.cloudwatch

import com.amazonaws.services.logs.{AWSLogs, AWSLogsClientBuilder}
import com.amazonaws.services.logs.model._
import org.gfccollective.concurrent.JavaConverters._
import org.gfccollective.concurrent.SameThreadExecutionContext
import org.gfccollective.logging.OpenLoggable

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.Success
import scala.util.control.NonFatal

/** A type class of things that can be converted to CloudWatch log events. */
trait ToCloudWatchLogsData[A] {
  /** A function to convert type A to CW log events. */
  def toLogEvents(a: A): Seq[InputLogEvent]
}

/** Implement this to store the nextSequenceToken in a manner that works for your app.
  * How you do this will be determined by whether or not you have race conditions, concurrency issues, multiple nodes,
  * etc. Using the default implementation below will likely not work well if you have any of the above.
  *
  * The nextSequenceToken is unique for each group/stream combination in CloudWatch Logs.
  * */
trait NextSequenceTokenPersistor {
  def getNextSequenceToken(groupName: String, streamName: String): String
  def setNextSequenceToken(groupName: String, streamName: String, token: String): Unit
}

/**
 * Push selected logs to AWS CloudWatch Logs.
 * Some are meant to be 'system health' logs, like our Future-based RPC call times.
 * Some are meant to be 'business' logs, like number of logins.
 *
 * Trait exists mainly to make it possible to inject this as a dependency.
 */
trait CloudWatchLogsClient {

  /** Log group in CloudWatch Logs will be a concatenation of all of these, except for the last one,
    * which will be the stream.
    * Every time you enter a namespace it'll get appended to the current one, e.g. "a/b/c".
    */
  def enterNamespace( n: String
                      ): CloudWatchLogsClient


  /** Run a closure with a namespaced CW logs client, just a convenience function.
    *
    * @param n    namespace to enter
    * @param run  closure to run
    * @tparam R   closure result type
    * @return     closure result
    */
  def withNamespace[R]( n: String
                        )( run: (CloudWatchLogsClient) => R
                        ): R = {
    run(this.enterNamespace(n))
  }


  /** Requests log data to be sent asynchronously.
    * It will block only to schedule async tasks, shouldn't be noticeable under low contention.
    * HTTP service calls are asynchronous.
    *
    * http://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_PutLogEvents.html
    *
    * @param logName           the name of the AWS CW Logs stream the log events will be written to
    * @param a                 object to send logs data for
    * @param tcwmdEv           evidence that ToCloudWatchLogsData implementation exists for type A
    * @tparam A                type of the object we convert to logs data
    */
  def putLogData[A]( logName: String,
                     a: A
                        )( implicit tcwmdEv: ToCloudWatchLogsData[A],
                           nstp: NextSequenceTokenPersistor
                        ): Unit


  /** Brackets a block of code and puts a given log if an exception occurs.
    *
    * @param logName   the name of the AWS CW Logs stream the log events will be written to
    * @param t2a       construct log events from throwable (e.g. you may want to report different exceptions differently)
    * @param run       block of code to run
    * @param tcwmdEv   evidence that ToCloudWatchLogsData implementation exists for type A
    * @tparam A        type of log
    * @tparam R        type of result
    * @return          result of the execution of the given closure
    */
  def putExceptionLogData[A,R]( logName: String,
                                t2a: (Throwable) => A
                                   )( run: => R
                                   )( implicit tcwmdEv: ToCloudWatchLogsData[A],
                                      nstp: NextSequenceTokenPersistor
                                   ): R = {
    try {
      run
    } catch {
      case NonFatal(e) =>
        this.putLogData(logName, t2a(e)) // report exception and re-throw it
        throw e
    }
  }


  /** Brackets a block of asynchronous code (Future result) and puts a given log if an exception occurs
    * either synchronously or wrapped in the result of the Future.
    *
    * @param logName   the name of the AWS CW Logs stream the log events will be written to
    * @param t2a       construct log from throwable (e.g. you may want to report different exceptions differently)
    * @param run       block of code to run
    * @param tcwmdEv   evidence that ToCloudWatchLogsData implementation exists for type A
    * @tparam A        type of log
    * @tparam R        type of result
    * @return          result of the execution of the given closure
    */
  def putAsynchronousExceptionLogData[A,R]( logName: String,
                                            t2a: (Throwable) => A
                                               )( run: => Future[R]
                                               )( implicit tcwmdEv: ToCloudWatchLogsData[A],
                                                  nstp: NextSequenceTokenPersistor
                                               ): Future[R] = {

    val safeFuture: Future[R] = this.putExceptionLogData(logName, t2a)(run) // if run() explodes before returning a future we capture it here

    implicit val ec = SameThreadExecutionContext // putLogData should be lightweight

    safeFuture.onComplete {
      case Success(NonFatal(e)) =>
        this.putLogData(logName, t2a(e))
      case _ =>
        ()
    }

    safeFuture
  }
}


object CloudWatchLogsClient {

  /** Creates default implementation of the CloudWatchLogsClient.
    *
    * http://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_PutLogEvents.html
    *
    * @param logNamespace CloudWatch log namespace
    * @return CloudWatchLogsClient instance
    */
  def apply( logNamespace: String
           , awsLogs: AWSLogs = AWSLogsClientBuilder.defaultClient()
           ): CloudWatchLogsClient = CloudWatchLogsClientImpl(logNamespace, awsLogs)


  /**
   * A convenience definition of NextSequenceTokenPersistor that can be used in some cases. Be warned that
   * this may not work for every use case, and you should consider implementing this for yourself.
   */
  implicit object DefaultNextSequenceTokenPersistor extends NextSequenceTokenPersistor {
    var nextSequenceTokens: Map[(String, String), String] = Map.empty

    override
    def getNextSequenceToken(groupName: String, streamName: String): String = nextSequenceTokens.get((groupName, streamName)).orNull

    override
    def setNextSequenceToken(groupName: String, streamName: String, token: String): Unit = {
      nextSequenceTokens = nextSequenceTokens ++ Map((groupName, streamName) -> token)
    }
  }

}


/** Default implementation of CloudWatchLogsClient, trait is for dependency injection. */
private[cloudwatch]
object CloudWatchLogsClientImpl {

  private
  val Logger = new OpenLoggable {}

  private
  val executor = {
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
case class CloudWatchLogsClientImpl (
    namespace: String
  , awsClient: AWSLogs
) extends CloudWatchLogsClient {

  import CloudWatchLogsClientImpl._

  override
  def enterNamespace( n: String
                    ): CloudWatchLogsClient = {
    this.copy(namespace = s"${this.namespace}/${n}") // Default CloudWatch Logs behavior makes this look like a path
  }

  override
  def putLogData[A]( logName: String,
                     a: A
                   )( implicit tcwmdEv: ToCloudWatchLogsData[A],
                      nstp: NextSequenceTokenPersistor
                   ): Unit = executor.execute {
    try {
      putLogEvents(
        new PutLogEventsRequest()
          .withLogGroupName(s"/$namespace")
          .withLogStreamName(logName)
          .withLogEvents(tcwmdEv.toLogEvents(a).asJavaCollection)
      )
      return // Here to force the compiler to recognize the return value as Unit instead of PutLogEventResult
    } catch {
      case NonFatal(e) =>
        Logger.error(e.getMessage, e)
    }
  }

  /**
   * Attempts to call the putLogEvents (http://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_PutLogEvents.html)
   * endpoint with the given request, automatically filling in the nextSequenceToken stored in the given persistor.
   *
   * This is called recursively because any/all of the given exceptions could be thrown. If all are thrown (once per
   * attempt), this method should be called until either a success or a non-caught exception is thrown.
   *
   * Watching for ResourceNotFoundException for both the group and stream is more efficient than using the
   * DescribeLogGroups (http://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_DescribeLogGroups.html)
   * or DescribeLogStreams (http://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_DescribeLogStreams.html)
   * endpoints because the exception will be thrown once, only at the time of creation - thus calling the API three times
   * once, but calling it only once per request thereafter. The alternative would be to call the "describe" endpoints on
   * each putLogEvents request - thus calling the API twice, for every put request.
   *
   * Watching for InvalidSequenceTokenException allows this call to recover nicely if the nextSequenceToken is wrong or
   * missing. This can be used to essentially ignore concurrency issues across multiple nodes (i.e. using this 'catch'
   * in conjunction with the naive DefaultNextSequenceTokenPersistor above), but is not recommended because it will
   * likely result in many double-calls to the API.
   *
   * @param request The log events to put to the API
   * @param nstp    Used to get the nextSequenceToken for the request; also used to store the nextSequenceToken from
   *                the response.
   * @return        The result of the put request, used mainly for getting the nextSequenceToken
   */
  private
  def putLogEvents(request: PutLogEventsRequest)(implicit nstp: NextSequenceTokenPersistor): PutLogEventsResult = {
    val response = try {
      awsClient.putLogEvents(request.withSequenceToken(nstp.getNextSequenceToken(request.getLogGroupName, request.getLogStreamName)))
    } catch {
      case e: ResourceNotFoundException if e.getMessage.toLowerCase.contains("log group") =>
        awsClient.createLogGroup(
          new CreateLogGroupRequest()
            .withLogGroupName(request.getLogGroupName)
        )
        putLogEvents(request)
      case e: ResourceNotFoundException if e.getMessage.toLowerCase.contains("log stream") =>
        awsClient.createLogStream(
          new CreateLogStreamRequest()
            .withLogGroupName(request.getLogGroupName)
            .withLogStreamName(request.getLogStreamName)
        )
        putLogEvents(request)
      case e: InvalidSequenceTokenException => awsClient.putLogEvents(request.withSequenceToken(e.getExpectedSequenceToken))
    }
    nstp.setNextSequenceToken(request.getLogGroupName, request.getLogStreamName, response.getNextSequenceToken)
    response
  }
}
