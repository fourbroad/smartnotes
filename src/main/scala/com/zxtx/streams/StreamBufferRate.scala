package com.zxtx.streams

import scala.collection.immutable
import scala.concurrent.duration.DurationInt
import scala.math.pow
import scala.math.sqrt
import scala.util.Random
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.Attributes
import akka.stream.ClosedShape
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.GraphDSL
import akka.stream.scaladsl.RunnableGraph
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.ZipWith

object StreamBufferRate extends App {
  implicit val system = ActorSystem("StreamModularity")
  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system).withInputBuffer(initialSize = 64, maxSize = 64))
  implicit val ex = system.dispatcher

  Source(1 to 3)
    .map { i ⇒ println(s"A: $i"); i }.async
    .map { i ⇒ println(s"B: $i"); i }.async
    .map { i ⇒ println(s"C: $i"); i }.async
    .runWith(Sink.ignore)

  val section = Flow[Int].map(_ * 2).async.addAttributes(Attributes.inputBuffer(initial = 1, max = 1)) // the buffer size of this map is 1
  val flow = section.via(Flow[Int].map(_ / 2)).async // the buffer size of this map is the default

  case class Tick()
  RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
    import GraphDSL.Implicits._

    // this is the asynchronous stage in this graph
    val zipper = b.add(ZipWith[Tick, Int, Int]((tick, count) ⇒ count).async.addAttributes(Attributes.inputBuffer(initial = 1, max = 1)))

    Source.tick(initialDelay = 5.second, interval = 5.second, Tick()) ~> zipper.in0
    Source.tick(initialDelay = 1.second, interval = 1.second, "message!").conflateWithSeed((_) ⇒ 1)((count, _) ⇒ count + 1) ~> zipper.in1

    zipper.out ~> Sink.foreach(println)
    ClosedShape
  }).run()

  val statsFlow = Flow[Double].conflateWithSeed(immutable.Seq(_))(_ :+ _).map { s ⇒
    val μ = s.sum / s.size
    val se = s.map(x ⇒ pow(x - μ, 2))
    val σ = sqrt(se.sum / se.size)
    (σ, μ, s.size)
  }

  val p = 0.01
  val sampleFlow = Flow[Double].conflateWithSeed(immutable.Seq(_)) {
    case (acc, elem) if Random.nextDouble < p ⇒ acc :+ elem
    case (acc, _)                             ⇒ acc
  }.mapConcat(identity)

  //  val lastFlow = Flow[Double].extrapolate(Iterator.continually(_))
  val driftFlow = Flow[Double].expand(i ⇒ Iterator.from(0).map(i -> _))

  //  system.terminate()
}