package com.zxtx.streams

import java.util.concurrent.atomic.AtomicInteger

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorSystem
import akka.actor.Props
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.Timeout

object AckingReceiver {
  case object Ack

  case object StreamInitialized
  case object StreamCompleted
  final case class StreamFailure(ex: Throwable)
}

class AckingReceiver(ackWith: Any) extends Actor with ActorLogging {
  import AckingReceiver._

  def receive: Receive = {
    case StreamInitialized ⇒
      log.info("Stream initialized!")
      sender() ! ackWith
    case el: String ⇒
      log.info("Received element: {}", el)
      sender() ! ackWith // ack to allow the stream to proceed sending more elements
    case StreamCompleted ⇒
      log.info("Stream completed!")
    case StreamFailure(ex) ⇒
      log.error(ex, "Stream failed!")
  }
}

class SometimesSlowService(implicit ec: ExecutionContext) {
  private val runningCount = new AtomicInteger
  def convert(s: String): Future[String] = {
    println(s"running: $s (${runningCount.incrementAndGet()})")
    Future {
      if (s.nonEmpty && s.head.isLower)
        Thread.sleep(500)
      else
        Thread.sleep(20)
      println(s"completed: $s (${runningCount.decrementAndGet()})")
      s.toUpperCase
    }
  }
}

object JobManager {
  def props: Props = Props[JobManager]

  final case class Job(payload: String)
  case object JobAccepted
  case object JobDenied
}

class JobManager extends ActorPublisher[JobManager.Job] {
  import JobManager._
  import akka.stream.actor.ActorPublisherMessage._

  val MaxBufferSize = 100
  var buf = Vector.empty[Job]

  def receive = {
    case job: Job if buf.size == MaxBufferSize ⇒
      sender() ! JobDenied
    case job: Job ⇒
      sender() ! JobAccepted
      if (buf.isEmpty && totalDemand > 0)
        onNext(job)
      else {
        buf :+= job
        deliverBuf()
      }
    case Request(_) ⇒
      deliverBuf()
    case Cancel ⇒
      context.stop(self)
  }

  @tailrec final def deliverBuf(): Unit =
    if (totalDemand > 0) {
      /*
       * totalDemand is a Long and could be larger than
       * what buf.splitAt can accept
       */
      if (totalDemand <= Int.MaxValue) {
        val (use, keep) = buf.splitAt(totalDemand.toInt)
        buf = keep
        use foreach onNext
      } else {
        val (use, keep) = buf.splitAt(Int.MaxValue)
        buf = keep
        use foreach onNext
        deliverBuf()
      }
    }
}

object StreamIntegration extends App {
  implicit val system = ActorSystem("StreamIntegration")
  //  implicit val materializer = ActorMaterializer()
  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system).withInputBuffer(initialSize = 4, maxSize = 4))
  implicit val ex = system.dispatcher

  implicit val askTimeout = Timeout(5.seconds)

  // sent from actor to stream to "ack" processing of given element
  val AckMessage = AckingReceiver.Ack

  // sent from stream to actor to indicate start, end or failure of stream:
  val InitMessage = AckingReceiver.StreamInitialized
  val OnCompleteMessage = AckingReceiver.StreamCompleted
  val onErrorMessage = (ex: Throwable) ⇒ AckingReceiver.StreamFailure(ex)

  val receiver = system.actorOf(Props(new AckingReceiver(ackWith = AckMessage)))
  val sink = Sink.actorRefWithAck(
    receiver,
    onInitMessage = InitMessage,
    ackMessage = AckMessage,
    onCompleteMessage = OnCompleteMessage,
    onFailureMessage = onErrorMessage)

  Source(List("hello", "hi")).map(_.toLowerCase).runWith(sink)

  implicit val blockingExecutionContext = system.dispatchers.lookup("blocking-dispatcher")

  val service = new SometimesSlowService
  Source(List("a", "B", "C", "D", "e", "F", "g", "H", "i", "J"))
    .map(elem ⇒ { println(s"before: $elem"); elem })
    .mapAsync(4)(service.convert)
    .runForeach(elem ⇒ println(s"after: $elem"))

  Source(List("a", "B", "C", "D", "e", "F", "g", "H", "i", "J"))
    .map(elem ⇒ { println(s"before: $elem"); elem })
    .mapAsyncUnordered(4)(service.convert)
    .runForeach(elem ⇒ println(s"after: $elem"))
}