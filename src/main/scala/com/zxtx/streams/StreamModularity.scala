package com.zxtx.streams

import scala.concurrent.Future
import scala.concurrent.Promise

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.ClosedShape
import akka.stream.FlowShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.SourceShape
import akka.stream.UniformFanInShape
import akka.stream.UniformFanOutShape
import akka.stream.scaladsl.Balance
import akka.stream.scaladsl.Broadcast
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.GraphDSL
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Merge
import akka.stream.scaladsl.RunnableGraph
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Tcp
import akka.stream.scaladsl.Tcp.OutgoingConnection
import akka.util.ByteString
import akka.stream.Attributes

object StreamModularity extends App {
  implicit val system = ActorSystem("StreamModularity")
  implicit val materializer = ActorMaterializer()
  implicit val ex = system.dispatcher

  Source.single(0).map(_ + 1).filter(_ != 0).map(_ - 2).to(Sink.fold(0)(_ + _))

  val nestedSource =
    Source.single(0) // An atomic source
      .map(_ + 1) // an atomic processing stage
      .named("nestedSource") // wraps up the current Source and gives it a name

  val nestedFlow =
    Flow[Int].filter(_ != 0) // an atomic processing stage
      .map(_ - 2) // another atomic processing stage
      .named("nestedFlow") // wraps up the Flow, and gives it a name

  val nestedSink =
    nestedFlow.to(Sink.fold(0)(_ + _)) // wire an atomic sink to the nestedFlow
      .named("nestedSink") // wrap it up

  // Create a RunnableGraph
  val runnableGraph = nestedSource.to(nestedSink)

  RunnableGraph.fromGraph(GraphDSL.create() { implicit builder =>
    import akka.stream.scaladsl.GraphDSL.Implicits._
    val A: Outlet[Int] = builder.add(Source.single(0)).out
    val B: UniformFanOutShape[Int, Int] = builder.add(Broadcast[Int](2))
    val C: UniformFanInShape[Int, Int] = builder.add(Merge[Int](2))
    val D: FlowShape[Int, Int] = builder.add(Flow[Int].map(_ + 1))
    val E: UniformFanOutShape[Int, Int] = builder.add(Balance[Int](2))
    val F: UniformFanInShape[Int, Int] = builder.add(Merge[Int](2))
    val G: Inlet[Any] = builder.add(Sink.foreach(println)).in

    C <~ F
    A ~> B ~> C ~> F
    B ~> D ~> E ~> F
    E ~> G

    ClosedShape
  })

  RunnableGraph.fromGraph(GraphDSL.create() { implicit builder =>
    import GraphDSL.Implicits._
    val B = builder.add(Broadcast[Int](2))
    val C = builder.add(Merge[Int](2))
    val E = builder.add(Balance[Int](2))
    val F = builder.add(Merge[Int](2))

    Source.single(0) ~> B.in; B.out(0) ~> C.in(1); C.out ~> F.in(0)
    C.in(0) <~ F.out

    B.out(1).map(_ + 1) ~> E.in; E.out(0) ~> F.in(1)
    E.out(1) ~> Sink.foreach(println)
    ClosedShape
  })

  val partial = GraphDSL.create() { implicit builder =>
    import GraphDSL.Implicits._
    val B = builder.add(Broadcast[Int](2))
    val C = builder.add(Merge[Int](2))
    val E = builder.add(Balance[Int](2))
    val F = builder.add(Merge[Int](2))

    C <~ F
    B ~> C ~> F
    B ~> Flow[Int].map(_ + 1) ~> E ~> F
    FlowShape(B.in, E.out(1))
  }.named("partial")
  Source.single(0).via(partial).to(Sink.ignore)

  // Convert the partial graph of FlowShape to a Flow to get
  // access to the fluid DSL (for example to be able to call .filter())
  val flow = Flow.fromGraph(partial)

  // Simple way to create a graph backed Source
  val source = Source.fromGraph(GraphDSL.create() { implicit builder =>
    import GraphDSL.Implicits._

    val merge = builder.add(Merge[Int](2))
    Source.single(0) ~> merge
    Source(List(2, 3, 4)) ~> merge

    // Exposing exactly one output port
    SourceShape(merge.out)
  })

  // Building a Sink with a nested Flow, using the fluid DSL
  val sink = {
    val nestedFlow = Flow[Int].map(_ * 2).drop(10).named("nestedFlow")
    nestedFlow.to(Sink.head)
  }

  // Putting all together
  val closed = source.via(flow.filter(_ > 1)).to(sink)

  val closed1 = Source.single(0).to(Sink.foreach(println))
  val closed2 = RunnableGraph.fromGraph(GraphDSL.create() { implicit builder ⇒
    val embeddedClosed: ClosedShape = builder.add(closed1)
    // …
    embeddedClosed
  })

  // Materializes to Promise[Option[Int]]                                   (red)
  val source2: Source[Int, Promise[Option[Int]]] = Source.maybe[Int]
  // Materializes to NotUsed                                               (black)
  val flow1: Flow[Int, Int, NotUsed] = Flow[Int].take(100)
  // Materializes to Promise[Int]                                          (red)
  val nestedSource2: Source[Int, Promise[Option[Int]]] =
    source2.viaMat(flow1)(Keep.left).named("nestedSource")

  // Materializes to NotUsed                                                (orange)
  val flow2: Flow[Int, ByteString, NotUsed] = Flow[Int].map { i ⇒ ByteString(i.toString) }
  // Materializes to Future[OutgoingConnection]                             (yellow)
  val flow3: Flow[ByteString, ByteString, Future[OutgoingConnection]] = Tcp().outgoingConnection("localhost", 8080)
  // Materializes to Future[OutgoingConnection]                             (yellow)
  val nestedFlow2: Flow[Int, ByteString, Future[OutgoingConnection]] = flow2.viaMat(flow3)(Keep.right).named("nestedFlow")

  // Materializes to Future[String]                                         (green)
  val sink2: Sink[ByteString, Future[String]] = Sink.fold("")(_ + _.utf8String)

  // Materializes to (Future[OutgoingConnection], Future[String])           (blue)
  val nestedSink2: Sink[Int, (Future[OutgoingConnection], Future[String])] = nestedFlow2.toMat(sink2)(Keep.both)

  case class MyClass(private val p: Promise[Option[Int]], conn: OutgoingConnection) {
    def close() = p.trySuccess(None)
  }

  def f(p: Promise[Option[Int]], rest: (Future[OutgoingConnection], Future[String])): Future[MyClass] = {
    val connFuture = rest._1
    connFuture.map(MyClass(p, _))
  }

  // Materializes to Future[MyClass]                                        (purple)
  val runnableGraph2: RunnableGraph[Future[MyClass]] = nestedSource2.toMat(nestedSink2)(f)

  import Attributes._
  val nestedSource3 = Source.single(0).map(_ + 1).named("nestedSource3") // Wrap, no inputBuffer set

  val nestedFlow3 = Flow[Int].filter(_ != 0).via(Flow[Int].map(_ - 2).withAttributes(inputBuffer(4, 4))) // override
    .named("nestedFlow3") // Wrap, no inputBuffer set

  val nestedSink3 = nestedFlow3.to(Sink.fold(0)(_ + _)) // wire an atomic sink to the nestedFlow
    .withAttributes(name("nestedSink3") and inputBuffer(3, 3)) // override
}