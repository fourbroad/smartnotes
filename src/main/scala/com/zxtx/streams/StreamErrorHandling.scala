package com.zxtx.streams

import scala.concurrent.duration.DurationInt

import akka.actor.ActorSystem
import akka.stream.ActorAttributes
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.KillSwitches
import akka.stream.Supervision
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.RestartSource
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source

object StreamErrorHandling extends App {
  implicit val system = ActorSystem("StreamGraphs")
  implicit val ex = system.dispatcher

  val decider: Supervision.Decider = {
    case _: ArithmeticException ⇒ Supervision.Resume
    case _                      ⇒ Supervision.Stop
  }
  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy(decider))

  Source(-5 to 5)
    .map(1 / _) //throwing ArithmeticException: / by zero
    .log("error logging")
    .runWith(Sink.ignore)

  Source(0 to 6).map(n ⇒
    if (n < 5) n.toString
    else throw new RuntimeException("Boom!")).recover {
    case _: RuntimeException ⇒ "stream truncated"
  }.runForeach(println)

  val planB = Source(List("five", "six", "seven", "eight"))
  Source(0 to 10).map(n ⇒
    if (n < 5) n.toString
    else throw new RuntimeException("Boom!")).recoverWithRetries(attempts = 1, {
    case _: RuntimeException ⇒ planB
  }).runForeach(println)

  val restartSource = RestartSource.withBackoff(
    minBackoff = 3.seconds,
    maxBackoff = 30.seconds,
    randomFactor = 0.2 // adds 20% "noise" to vary the intervals slightly
  ) { () ⇒ Source(1 to 100) }

  val killSwitch = restartSource
    .viaMat(KillSwitches.single)(Keep.right)
    .toMat(Sink.foreach(event ⇒ println(s"Got event: $event")))(Keep.left)
    .run()
  killSwitch.shutdown()

  // the element causing division by zero will be dropped
  // result here will be a Future completed with Success(228)
  val source = Source(0 to 5).map(100 / _)
  val result = source.runWith(Sink.fold(0)(_ + _))

  // the elements causing division by zero will be dropped
  // result here will be a Future completed with Success(150)
  val flow = Flow[Int].filter(100 / _ < 50).map(elem ⇒ 100 / (5 - elem)).withAttributes(ActorAttributes.supervisionStrategy(decider))
  val source2 = Source(0 to 5).via(flow)
  val result2 = source2.runWith(Sink.fold(0)(_ + _))

  // the negative element cause the scan stage to be restarted, i.e. start from 0 again
  // result here will be a Future completed with Success(Vector(0, 1, 4, 0, 5, 12))
  val decider2: Supervision.Decider = {
    case _: IllegalArgumentException ⇒ Supervision.Restart
    case _                           ⇒ Supervision.Stop
  }
  val flow2 = Flow[Int].scan(0) { (acc, elem) ⇒
    if (elem < 0) throw new IllegalArgumentException("negative not allowed")
    else acc + elem
  }.withAttributes(ActorAttributes.supervisionStrategy(decider2))
  Source(List(1, 3, -1, 5, 7)).via(flow2).limit(1000).runWith(Sink.seq).foreach(println)

}