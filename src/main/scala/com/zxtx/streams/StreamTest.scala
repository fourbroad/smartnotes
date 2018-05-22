package com.zxtx.streams

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
//import akka.stream.testkit.scaladsl.TestSink
//import akka.stream.testkit.scaladsl.TestSource
import akka.pattern.pipe
import akka.testkit.TestProbe
import scala.util.Failure
import scala.concurrent.Future

object StreamTest extends App {
  implicit val system = ActorSystem("StreamGraphs")
  implicit val materializer = ActorMaterializer()
  implicit val ex = system.dispatcher

  val sinkUnderTest = Flow[Int].map(_ * 2).toMat(Sink.fold(0)(_ + _))(Keep.right)
  val future = Source(1 to 4).runWith(sinkUnderTest)
  val result = Await.result(future, 3.seconds)
  assert(result == 20)

  val future2 = Source.repeat(1).map(_ * 2).take(10).runWith(Sink.seq)
  val result2 = Await.result(future2, 3.seconds)
  assert(result2 == Seq.fill(10)(2))

  val future3 = Source(1 to 10).via(Flow[Int].takeWhile(_ < 5)).runWith(Sink.fold(Seq.empty[Int])(_ :+ _))
  val result3 = Await.result(future3, 3.seconds)
  assert(result3 == (1 to 4))

  val probe = TestProbe()
  Source(1 to 4).grouped(2).runWith(Sink.seq).pipeTo(probe.ref)
  probe.expectMsg(3.seconds, Seq(Seq(1, 2), Seq(3, 4)))

  case object Tick
  val probe2 = TestProbe()
  val cancellable = Source.tick(0.seconds, 200.millis, Tick).to(Sink.actorRef(probe2.ref, "completed")).run()
  probe2.expectMsg(1.second, Tick)
  probe2.expectNoMsg(100.millis)
  probe2.expectMsg(3.seconds, Tick)
  cancellable.cancel()
  probe.expectMsg(3.seconds, "completed")

  val sinkUnderTest2 = Flow[Int].map(_.toString).toMat(Sink.fold("")(_ + _))(Keep.right)
  val (ref, future4) = Source.actorRef(8, OverflowStrategy.fail).toMat(sinkUnderTest)(Keep.both).run()
  ref ! 1
  ref ! 2
  ref ! 3
  ref ! akka.actor.Status.Success("done")
  val result4 = Await.result(future4, 3.seconds)
  assert(result4 == "123")

  //  Source(1 to 4).filter(_ % 2 == 0).map(_ * 2).runWith(TestSink.probe[Int]).request(2).expectNext(4, 8).expectComplete()
  //  TestSource.probe[Int].toMat(Sink.cancelled)(Keep.left).run().expectCancellation()

  //  val (probe3, future5) = TestSource.probe[Int].toMat(Sink.head[Int])(Keep.both).run()
  //  probe3.sendError(new Exception("boom"))
  //  Await.ready(future5, 3.seconds)
  //  val Failure(exception) = future5.value.get
  //  assert(exception.getMessage == "boom")

  //  val flowUnderTest = Flow[Int].mapAsyncUnordered(2) { sleep ⇒ Future.successful(sleep) }
  //  val (pub, sub) = TestSource.probe[Int].via(flowUnderTest).toMat(TestSink.probe[Int])(Keep.both).run()
  //  sub.request(n = 3)
  //  pub.sendNext(3)
  //  pub.sendNext(2)
  //  pub.sendNext(1)
  //  sub.expectNextUnordered(1, 2, 3)
  //  pub.sendError(new Exception("Power surge in the linear subroutine C-47!"))
  //  val ee = sub.expectError()
  //  assert(ee.getMessage.contains("C-47"))

}