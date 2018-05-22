package com.zxtx.streams

import scala.collection.immutable.Iterable
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration

import akka.NotUsed
import akka.actor.ActorSystem
import akka.dispatch.forkjoin.ThreadLocalRandom
import akka.stream.ActorMaterializer
import akka.stream.Attributes
import akka.stream.FlowShape
import akka.stream.Graph
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.SinkShape
import akka.stream.SourceShape
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.GraphStageWithMaterializedValue
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler
import akka.stream.stage.StageLogging
import akka.stream.stage.TimerGraphStageLogic

class NumbersSource extends GraphStage[SourceShape[Int]] {
  val out: Outlet[Int] = Outlet("NumbersSource")
  override val shape: SourceShape[Int] = SourceShape(out)
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    // All state MUST be inside the GraphStageLogic, never inside the enclosing GraphStage.
    // This state is safe to access and modify from all the callbacks that are provided by GraphStageLogic and the registered handlers.
    private var counter = 1
    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        push(out, counter)
        counter += 1
      }
    })
  }
}

class StdoutSink extends GraphStage[SinkShape[Int]] {
  val in: Inlet[Int] = Inlet("StdoutSink")
  override val shape: SinkShape[Int] = SinkShape(in)
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    // This requests one element at the Sink startup.
    override def preStart(): Unit = pull(in)
    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        println(grab(in))
        pull(in)
      }
    })
  }
}

class Map[A, B](f: A ⇒ B) extends GraphStage[FlowShape[A, B]] {
  val in = Inlet[A]("Map.in")
  val out = Outlet[B]("Map.out")
  override val shape = FlowShape.of(in, out)
  override def createLogic(attr: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        push(out, f(grab(in)))
      }
    })
    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        pull(in)
      }
    })
  }
}

class Filter[A](p: A ⇒ Boolean) extends GraphStage[FlowShape[A, A]] {
  val in = Inlet[A]("Filter.in")
  val out = Outlet[A]("Filter.out")
  val shape = FlowShape.of(in, out)
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        val elem = grab(in)
        if (p(elem)) push(out, elem)
        else pull(in)
      }
    })
    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        pull(in)
      }
    })
  }
}

class Duplicator[A] extends GraphStage[FlowShape[A, A]] {
  val in = Inlet[A]("Duplicator.in")
  val out = Outlet[A]("Duplicator.out")
  val shape = FlowShape.of(in, out)
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    // Again: note that all mutable state MUST be inside the GraphStageLogic
    var lastElem: Option[A] = None
    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        val elem = grab(in)
        lastElem = Some(elem)
        push(out, elem)
      }
      override def onUpstreamFinish(): Unit = {
        if (lastElem.isDefined) emit(out, lastElem.get)
        complete(out)
      }
    })
    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if (lastElem.isDefined) {
          push(out, lastElem.get)
          lastElem = None
        } else {
          pull(in)
        }
      }
    })
  }
}

class Duplicator2[A] extends GraphStage[FlowShape[A, A]] {
  val in = Inlet[A]("Duplicator.in")
  val out = Outlet[A]("Duplicator.out")
  val shape = FlowShape.of(in, out)
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        val elem = grab(in)
        // this will temporarily suspend this handler until the two elems are emitted and then reinstates it
        emitMultiple(out, Iterable(elem, elem))
      }
    })
    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        pull(in)
      }
    })
  }
}

final class RandomLettersSource extends GraphStage[SourceShape[String]] {
  val out = Outlet[String]("RandomLettersSource.out")
  override val shape: SourceShape[String] = SourceShape(out)
  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with StageLogging {
    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        val c = nextChar() // ASCII lower case letters
        // `log` is obtained from materializer automatically (via StageLogging)
        log.debug("Randomly generated: [{}]", c)
        push(out, c.toString)
      }
    })
  }
  def nextChar(): Char = ThreadLocalRandom.current().nextInt('a', 'z'.toInt + 1).toChar
}

// each time an event is pushed through it will trigger a period of silence
class TimedGate[A](silencePeriod: FiniteDuration) extends GraphStage[FlowShape[A, A]] {
  val in = Inlet[A]("TimedGate.in")
  val out = Outlet[A]("TimedGate.out")
  val shape = FlowShape.of(in, out)
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
    var open = false
    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        val elem = grab(in)
        if (open) pull(in)
        else {
          push(out, elem)
          open = true
          scheduleOnce(None, silencePeriod)
        }
      }
    })
    setHandler(out, new OutHandler {
      override def onPull(): Unit = { pull(in) }
    })
    override protected def onTimer(timerKey: Any): Unit = {
      open = false
    }
  }
}

class FirstValue[A] extends GraphStageWithMaterializedValue[FlowShape[A, A], Future[A]] {
  val in = Inlet[A]("FirstValue.in")
  val out = Outlet[A]("FirstValue.out")
  val shape = FlowShape.of(in, out)
  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[A]) = {
    val promise = Promise[A]()
    val logic = new GraphStageLogic(shape) {
      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val elem = grab(in)
          promise.success(elem)
          push(out, elem)

          // replace handler with one just forwarding
          setHandler(in, new InHandler {
            override def onPush(): Unit = {
              push(out, grab(in))
            }
          })
        }
      })
      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pull(in)
        }
      })
    }
    (logic, promise.future)
  }
}

class TwoBuffer[A] extends GraphStage[FlowShape[A, A]] {
  val in = Inlet[A]("TwoBuffer.in")
  val out = Outlet[A]("TwoBuffer.out")
  val shape = FlowShape.of(in, out)
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    val buffer = mutable.Queue[A]()
    def bufferFull = buffer.size == 2
    var downstreamWaiting = false
    override def preStart(): Unit = {
      // a detached stage needs to start upstream demand itself as it is not triggered by downstream demand
      pull(in)
    }
    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        val elem = grab(in)
        buffer.enqueue(elem)
        if (downstreamWaiting) {
          downstreamWaiting = false
          val bufferedElem = buffer.dequeue()
          push(out, bufferedElem)
        }
        if (!bufferFull) {
          pull(in)
        }
      }
      override def onUpstreamFinish(): Unit = {
        if (buffer.nonEmpty) {
          // emit the rest if possible
          emitMultiple(out, buffer.toIterator)
        }
        completeStage()
      }
    })
    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if (buffer.isEmpty) {
          downstreamWaiting = true
        } else {
          val elem = buffer.dequeue
          push(out, elem)
        }
        if (!bufferFull && !hasBeenPulled(in)) {
          pull(in)
        }
      }
    })
  }

}

object StreamCustom extends App {
  implicit val system = ActorSystem("StreamGraphs")
  implicit val materializer = ActorMaterializer()
  implicit val ex = system.dispatcher

  // A GraphStage is a proper Graph, just like what GraphDSL.create would return
  val sourceGraph: Graph[SourceShape[Int], NotUsed] = new NumbersSource

  // Create a Source from the Graph to access the DSL
  val mySource: Source[Int, NotUsed] = Source.fromGraph(sourceGraph)

  // Returns 55
  val result1: Future[Int] = mySource.take(10).runFold(0)(_ + _)

  // The source is reusable. This returns 5050
  val result2: Future[Int] = mySource.take(100).runFold(0)(_ + _)

  val resultFuture = Source(1 to 5).via(new Filter(_ % 2 == 0)).via(new Duplicator()).via(new Map(_ / 2)).runWith(new StdoutSink)

  // will close upstream in all materializations of the graph stage instance when the future completes
  class KillSwitch[A](switch: Future[Unit]) extends GraphStage[FlowShape[A, A]] {
    val in = Inlet[A]("KillSwitch.in")
    val out = Outlet[A]("KillSwitch.out")
    val shape = FlowShape.of(in, out)
    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      override def preStart(): Unit = {
        val callback = getAsyncCallback[Unit] { (_) ⇒
          completeStage()
        }
        switch.foreach(callback.invoke)
      }
      setHandler(in, new InHandler {
        override def onPush(): Unit = { push(out, grab(in)) }
      })
      setHandler(out, new OutHandler {
        override def onPull(): Unit = { pull(in) }
      })
    }
  }

  implicit class SourceDuplicator[Out, Mat](s: Source[Out, Mat]) {
    def duplicateElements: Source[Out, Mat] = s.via(new Duplicator)
  }
  Source(1 to 3).duplicateElements.runWith(Sink.seq).foreach(println)

  implicit class FlowDuplicator[In, Out, Mat](s: Flow[In, Out, Mat]) {
    def duplicateElements: Flow[In, Out, Mat] = s.via(new Duplicator)
  }
  val f = Flow[Int].duplicateElements
  Source(1 to 3).via(f).runWith(Sink.seq).foreach(println)

}