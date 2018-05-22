package com.zxtx.streams

import java.nio.ByteOrder

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.Attributes
import akka.stream.BidiShape
import akka.stream.ClosedShape
import akka.stream.FanInShape
import akka.stream.FanInShape.Init
import akka.stream.FanInShape.Name
import akka.stream.FlowShape
import akka.stream.Graph
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.OverflowStrategy
import akka.stream.Shape
import akka.stream.SourceShape
import akka.stream.UniformFanInShape
import akka.stream.scaladsl.Balance
import akka.stream.scaladsl.BidiFlow
import akka.stream.scaladsl.Broadcast
import akka.stream.scaladsl.Concat
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.GraphDSL
import akka.stream.scaladsl.Merge
import akka.stream.scaladsl.MergePreferred
import akka.stream.scaladsl.RunnableGraph
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Zip
import akka.stream.scaladsl.ZipWith
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler
import akka.util.ByteString

object StreamGraphs extends App {
  implicit val system = ActorSystem("StreamGraphs")
  implicit val materializer = ActorMaterializer()
  implicit val ex = system.dispatcher

  RunnableGraph.fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
    import GraphDSL.Implicits._

    val in = Source(1 to 10)
    val out = Sink.last[Int]

    val bcast = builder.add(Broadcast[Int](2))
    val merge = builder.add(Merge[Int](2))

    val f1, f2, f3, f4 = Flow[Int].map(_ + 10)

    in ~> f1 ~> bcast ~> f2 ~> merge ~> f3 ~> out
    bcast ~> f4 ~> merge
    ClosedShape
  }).run()

  val topHeadSink = Sink.head[Int]
  val bottomHeadSink = Sink.head[Int]
  val sharedDoubler = Flow[Int].map(_ * 2)
  val (top, bottom) = RunnableGraph.fromGraph(GraphDSL.create(topHeadSink, bottomHeadSink)((_, _)) { implicit builder => (topHS, bottomHS) =>
    import GraphDSL.Implicits._

    val broadcast = builder.add(Broadcast[Int](2))

    Source.single(1) ~> broadcast.in
    broadcast ~> sharedDoubler ~> topHS.in
    broadcast ~> sharedDoubler ~> bottomHS.in
    ClosedShape
  }).run()
  top.foreach(println)
  bottom.foreach(println)

  val pickMaxOfThree = GraphDSL.create() { implicit b ⇒
    import GraphDSL.Implicits._

    val zip1 = b.add(ZipWith[Int, Int, Int](math.max _))
    val zip2 = b.add(ZipWith[Int, Int, Int](math.max _))
    zip1.out ~> zip2.in0

    UniformFanInShape(zip2.out, zip1.in0, zip1.in1, zip2.in1)
  }
  val resultSink = Sink.head[Int]
  val g = RunnableGraph.fromGraph(GraphDSL.create(resultSink) { implicit b ⇒ sink ⇒
    import GraphDSL.Implicits._

    // importing the partial graph will return its shape (inlets & outlets)
    val pm3 = b.add(pickMaxOfThree)

    Source.single(1) ~> pm3.in(0)
    Source.single(2) ~> pm3.in(1)
    Source.single(3) ~> pm3.in(2)
    pm3.out ~> sink.in
    ClosedShape
  })
  val max: Future[Int] = g.run()
  Await.result(max, 300.millis) == 3

  val pairs = Source.fromGraph(GraphDSL.create() { implicit b ⇒
    import GraphDSL.Implicits._

    // prepare graph elements
    val zip = b.add(Zip[Int, Int]())
    def ints = Source.fromIterator(() ⇒ Iterator.range(1, 100))

    // connect the graph
    ints.filter(_ % 2 != 0) ~> zip.in0
    ints.filter(_ % 2 == 0) ~> zip.in1

    // expose port
    SourceShape(zip.out)
  })
  pairs.runWith(Sink.foreach(println))

  val pairUpWithToString = Flow.fromGraph(GraphDSL.create() { implicit b ⇒
    import GraphDSL.Implicits._

    // prepare graph elements
    val broadcast = b.add(Broadcast[Int](2))
    val zip = b.add(Zip[Int, String]())

    // connect the graph
    broadcast.out(0).map(identity) ~> zip.in0
    broadcast.out(1).map(_.toString) ~> zip.in1

    // expose ports
    FlowShape(broadcast.in, zip.out)
  })
  pairUpWithToString.runWith(Source(List(1)), Sink.foreach(println))

  val sourceOne = Source(List(1))
  val sourceTwo = Source(List(2))
  val merged = Source.combine(sourceOne, sourceTwo)(Merge(_))
  val mergedResult: Future[Int] = merged.runWith(Sink.fold(0)(_ + _))

  //  val sendRmotely = Sink.actorRef(actorRef, "Done")
  //  val localProcessing = Sink.foreach[Int](_ ⇒ /* do something usefull */ ())
  //  val sink = Sink.combine(sendRmotely, localProcessing)(Broadcast[Int](_))
  //  Source(List(0, 1, 2)).runWith(sink)

  val worker1 = Flow[String].map("step 1 " + _)
  val worker2 = Flow[String].map("step 2 " + _)

  RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
    import GraphDSL.Implicits._

    val priorityPool1 = b.add(PriorityWorkerPool(worker1, 4))
    val priorityPool2 = b.add(PriorityWorkerPool(worker2, 2))

    Source(1 to 100).map("job: " + _) ~> priorityPool1.jobsIn
    Source(1 to 100).map("priority job: " + _) ~> priorityPool1.priorityJobsIn

    priorityPool1.resultsOut ~> priorityPool2.jobsIn
    Source(1 to 100).map("one-step, priority " + _) ~> priorityPool2.priorityJobsIn

    priorityPool2.resultsOut ~> Sink.foreach(println)
    ClosedShape
  }).run()

  trait Message
  case class Ping(id: Int) extends Message
  case class Pong(id: Int) extends Message
  def toBytes(msg: Message): ByteString = {
    implicit val order = ByteOrder.LITTLE_ENDIAN
    msg match {
      case Ping(id) ⇒ ByteString.newBuilder.putByte(1).putInt(id).result()
      case Pong(id) ⇒ ByteString.newBuilder.putByte(2).putInt(id).result()
    }
  }
  def fromBytes(bytes: ByteString): Message = {
    implicit val order = ByteOrder.LITTLE_ENDIAN
    val it = bytes.iterator
    it.getByte match {
      case 1     ⇒ Ping(it.getInt)
      case 2     ⇒ Pong(it.getInt)
      case other ⇒ throw new RuntimeException(s"parse error: expected 1|2 got $other")
    }
  }
  val codecVerbose = BidiFlow.fromGraph(GraphDSL.create() { b ⇒
    // construct and add the top flow, going outbound
    val outbound = b.add(Flow[Message].map(toBytes))
    // construct and add the bottom flow, going inbound
    val inbound = b.add(Flow[ByteString].map(fromBytes))
    // fuse them together into a BidiShape
    BidiShape.fromFlows(outbound, inbound)
  })
  // this is the same as the above
  val codec = BidiFlow.fromFunctions(toBytes _, fromBytes _)

  val framing = BidiFlow.fromGraph(GraphDSL.create() { b ⇒
    implicit val order = ByteOrder.LITTLE_ENDIAN

    def addLengthHeader(bytes: ByteString) = ByteString.newBuilder.putInt(bytes.length).append(bytes).result()

    class FrameParser extends GraphStage[FlowShape[ByteString, ByteString]] {
      val in = Inlet[ByteString]("FrameParser.in")
      val out = Outlet[ByteString]("FrameParser.out")
      override val shape = FlowShape.of(in, out)
      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
        // this holds the received but not yet parsed bytes
        var stash = ByteString.empty
        // this holds the current message length or -1 if at a boundary
        var needed = -1

        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            if (isClosed(in)) run()
            else pull(in)
          }
        })
        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            val bytes = grab(in)
            stash = stash ++ bytes
            run()
          }
          override def onUpstreamFinish(): Unit = {
            // either we are done
            if (stash.isEmpty) completeStage()
            // or we still have bytes to emit wait with completion and let run() complete
            // when the rest of the stash has been sent downstream
            else if (isAvailable(out)) run()
          }
        })

        private def run(): Unit = {
          if (needed == -1) {
            // Are we at a boundary? then figure out next length
            if (stash.length < 4) {
              if (isClosed(in)) completeStage()
              else pull(in)
            } else {
              needed = stash.iterator.getInt
              stash = stash.drop(4)
              run() // Cycle back to possibly already emit the next chunk
            }
          } else if (stash.length < needed) {
            // We are in the middle of a message, need more bytes, or have to stop if input closed
            if (isClosed(in)) completeStage()
            else pull(in)
          } else {
            // we have enough to emit at least one message, so do it
            val emit = stash.take(needed)
            stash = stash.drop(needed)
            needed = -1
            push(out, emit)
          }
        }
      }
    }

    val outbound = b.add(Flow[ByteString].map(addLengthHeader))
    val inbound = b.add(Flow[ByteString].via(new FrameParser))
    BidiShape.fromFlows(outbound, inbound)
  })

  /* construct protocol stack
 *         +------------------------------------+
 *         | stack                              |
 *         |                                    |
 *         |  +-------+            +---------+  |
 *    ~>   O~~o       |     ~>     |         o~~O    ~>
 * Message |  | codec | ByteString | framing |  | ByteString
 *    <~   O~~o       |     <~     |         o~~O    <~
 *         |  +-------+            +---------+  |
 *         +------------------------------------+
 */
  val stack = codec.atop(framing)

  // test it by plugging it into its own inverse and closing the right end
  val pingpong = Flow[Message].collect { case Ping(id) ⇒ Pong(id) }
  val flow = stack.atop(stack.reversed).join(pingpong)
  val result = Source((0 to 9).map(Ping)).via(flow).limit(20).runWith(Sink.seq)
  Await.result(result, 1.second) == ((0 to 9).map(Pong))

  import akka.stream.scaladsl.GraphDSL.Implicits._
  val foldFlow: Flow[Int, Int, Future[Int]] = Flow.fromGraph(GraphDSL.create(Sink.fold[Int, Int](0)(_ + _)) { implicit builder ⇒ fold ⇒
    FlowShape(fold.in, builder.materializedValue.mapAsync(4)(identity).outlet)
  })

  // This cannot produce any value:
  val cyclicFold: Source[Int, Future[Int]] = Source.fromGraph(GraphDSL.create(Sink.fold[Int, Int](0)(_ + _)) { implicit builder ⇒ fold ⇒
    // - Fold cannot complete until its upstream mapAsync completes
    // - mapAsync cannot complete until the materialized Future produced by fold completes
    // As a result this Source will never emit anything, and its materialited
    // Future will never complete
    builder.materializedValue.mapAsync(4)(identity) ~> fold
    SourceShape(builder.materializedValue.mapAsync(4)(identity).outlet)
  })
  //  val test = cyclicFold.toMat(Sink.ignore)(Keep.left).run()

  // WARNING! The graph below deadlocks!
  RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._

    val merge = b.add(Merge[Int](2))
    val bcast = b.add(Broadcast[Int](2))

    Source(1 to 100) ~> merge ~> Flow[Int].map { s => println(s); s } ~> bcast ~> Sink.ignore
    merge <~ bcast
    ClosedShape
  })

  // WARNING! The graph below stops consuming from "source" after a few steps
  RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._

    val merge = b.add(MergePreferred[Int](1))
    val bcast = b.add(Broadcast[Int](2))

    Source(1 to 100) ~> merge ~> Flow[Int].map { s => println(s); s } ~> bcast ~> Sink.ignore
    merge.preferred <~ bcast
    ClosedShape
  })

  RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._

    val merge = b.add(Merge[Int](2))
    val bcast = b.add(Broadcast[Int](2))

    Source(1 to 100) ~> merge ~> Flow[Int].map { s => println(s); s } ~> bcast ~> Sink.ignore
    merge <~ Flow[Int].buffer(10, OverflowStrategy.dropHead) <~ bcast
    ClosedShape
  }).run()

  // WARNING! The graph below never processes any elements
  RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._

    val zip = b.add(ZipWith[Int, Int, Int]((left, right) => right))
    val bcast = b.add(Broadcast[Int](2))

    Source(1 to 100) ~> zip.in0
    zip.out.map { s => println(s); s } ~> bcast ~> Sink.ignore
    zip.in1 <~ bcast
    ClosedShape
  })

  RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._

    val zip = b.add(ZipWith((left: Int, right: Int) => left))
    val bcast = b.add(Broadcast[Int](2))
    val concat = b.add(Concat[Int]())
    val start = Source.single(0)

    Source(1 to 100) ~> zip.in0
    zip.out.map { s => println(s); s } ~> bcast ~> Sink.ignore
    zip.in1 <~ concat <~ start
    concat <~ bcast
    ClosedShape
  }).run()

  system.terminate()
}

object PriorityWorkerPool {

  def apply[In, Out](worker: Flow[In, Out, Any], workerCount: Int): Graph[PriorityWorkerPoolShape[In, Out], NotUsed] = GraphDSL.create() { implicit b ⇒
    import GraphDSL.Implicits._

    val priorityMerge = b.add(MergePreferred[In](1))
    val balance = b.add(Balance[In](workerCount))
    val resultsMerge = b.add(Merge[Out](workerCount))

    // After merging priority and ordinary jobs, we feed them to the balancer
    priorityMerge ~> balance

    // Wire up each of the outputs of the balancer to a worker flow then merge them back
    for (i ← 0 until workerCount)
      balance.out(i) ~> worker ~> resultsMerge.in(i)

    // We now expose the input ports of the priorityMerge and the output of the resultsMerge as our PriorityWorkerPool ports
    // -- all neatly wrapped in our domain specific Shape
    PriorityWorkerPoolShape(jobsIn = priorityMerge.in(0), priorityJobsIn = priorityMerge.preferred, resultsOut = resultsMerge.out)
  }

}

case class PriorityWorkerPoolShape[In, Out](jobsIn: Inlet[In], priorityJobsIn: Inlet[In], resultsOut: Outlet[Out]) extends Shape {
  // It is important to provide the list of all input and output ports with a stable order. Duplicates are not allowed.
  override val inlets: immutable.Seq[Inlet[_]] = jobsIn :: priorityJobsIn :: Nil
  override val outlets: immutable.Seq[Outlet[_]] = resultsOut :: Nil

  // A Shape must be able to create a copy of itself. Basically it means a new instance with copies of the ports
  override def deepCopy() = PriorityWorkerPoolShape(jobsIn.carbonCopy(), priorityJobsIn.carbonCopy(), resultsOut.carbonCopy())
}

class PriorityWorkerPoolShape2[In, Out](_init: Init[Out] = Name("PriorityWorkerPool")) extends FanInShape[Out](_init) {
  protected override def construct(i: Init[Out]) = new PriorityWorkerPoolShape2(i)

  val jobsIn = newInlet[In]("jobsIn")
  val priorityJobsIn = newInlet[In]("priorityJobsIn")
  // Outlet[Out] with name "out" is automatically created
}
