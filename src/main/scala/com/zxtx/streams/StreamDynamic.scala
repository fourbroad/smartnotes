package com.zxtx.streams

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.DelayOverflowStrategy
import akka.stream.KillSwitches
import akka.stream.UniqueKillSwitch
import akka.stream.scaladsl.BroadcastHub
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.MergeHub
import akka.stream.scaladsl.PartitionHub
import akka.stream.scaladsl.RunnableGraph
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.ThrottleMode

object StreamDynamic extends App {
  implicit val system = ActorSystem("StreamDynamic")
  implicit val materializer = ActorMaterializer()
  implicit val ex = system.dispatcher

  val countingSrc = Source(Stream.from(1)).delay(1.second, DelayOverflowStrategy.backpressure)
  val lastSnk = Sink.head[Int]
  val (killSwitch, last) = countingSrc.viaMat(KillSwitches.single)(Keep.right).toMat(lastSnk)(Keep.both).run()
  last.foreach(l => println(s"last = ${l}"))

  val countingSrc2 = Source(Stream.from(1)).delay(1.second, DelayOverflowStrategy.backpressure)
  val lastSnk2 = Sink.last[Int]
  val sharedKillSwitch = KillSwitches.shared("my-kill-switch")
  val last2 = countingSrc2.via(sharedKillSwitch.flow).runWith(lastSnk2)
  val delayedLast = countingSrc2.delay(1.second, DelayOverflowStrategy.backpressure).via(sharedKillSwitch.flow).runWith(lastSnk2)
  last2.foreach(l => println(s"last2 = ${l}"))
  last2.foreach(dl => println(s"delayedLast = ${dl}"))
  //Await.result(last, 1.second) shouldBe 2
  //Await.result(delayedLast, 1.second) shouldBe 1

  // A simple consumer that will print to the console for now
  val consumer = Sink.foreach(println)

  // Attach a MergeHub Source to the consumer. This will materialize to a corresponding Sink.
  val runnableGraph: RunnableGraph[Sink[String, NotUsed]] = MergeHub.source[String](perProducerBufferSize = 16).to(consumer)

  // By running/materializing the consumer we get back a Sink, and hence now have access to feed elements into it.
  // This Sink can be materialized any number of times, and every element that enters the Sink will be consumed by our consumer.
  val toConsumer: Sink[String, NotUsed] = runnableGraph.run()

  // Feeding two independent sources into the hub.
  Source.single("Hello!").runWith(toConsumer)
  Source.single("Hub!").runWith(toConsumer)

  // A simple producer that publishes a new "message" every second
  val producer = Source.tick(1.second, 1.second, "New message")

  // Attach a BroadcastHub Sink to the producer. This will materialize to a corresponding Source.
  // (We need to use toMat and Keep.right since by default the materialized value to the left is used)
  val runnableGraph2: RunnableGraph[Source[String, NotUsed]] = producer.toMat(BroadcastHub.sink(bufferSize = 256))(Keep.right)

  // By running/materializing the producer, we get back a Source, which
  // gives us access to the elements published by the producer.
  val fromProducer: Source[String, NotUsed] = runnableGraph2.run()

  // Print out messages from the producer in two independent consumers
  //  fromProducer.runForeach(msg ⇒ println("consumer1: " + msg))
  //  fromProducer.runForeach(msg ⇒ println("consumer2: " + msg))
  //  fromProducer.runWith(toConsumer)

  // Obtain a Sink and Source which will publish and receive from the "bus" respectively.
  val (sink, source) = MergeHub.source[String](perProducerBufferSize = 16).toMat(BroadcastHub.sink(bufferSize = 256))(Keep.both).run()

  // Ensure that the Broadcast output is dropped if there are no listening parties.
  // If this dropping Sink is not attached, then the broadcast hub will not drop any
  // elements itself when there are no subscribers, backpressuring the producer instead.
  source.runWith(Sink.ignore)

  // We create now a Flow that represents a publish-subscribe channel using the above
  // started stream as its "topic". We add two more features, external cancellation of
  // the registration and automatic cleanup for very slow subscribers.
  val busFlow: Flow[String, String, UniqueKillSwitch] =
    Flow.fromSinkAndSource(sink, source).joinMat(KillSwitches.singleBidi[String, String])(Keep.right).backpressureTimeout(3.seconds)

  val switch: UniqueKillSwitch = Source.tick(1.second, 1.second, "Hello world!").viaMat(busFlow)(Keep.right).to(Sink.foreach(println)).run()

  // A simple producer that publishes a new "message-" every second
  val producer2 = Source.tick(1.second, 1.second, "message").zipWith(Source(1 to 100))((a, b) ⇒ s"$a-$b")

  // Attach a PartitionHub Sink to the producer. This will materialize to a corresponding Source.
  // (We need to use toMat and Keep.right since by default the materialized value to the left is used)
  val runnableGraph3: RunnableGraph[Source[String, NotUsed]] =
    producer.toMat(PartitionHub.sink(
      (size, elem) ⇒ math.abs(elem.hashCode) % size,
      startAfterNrOfConsumers = 2, bufferSize = 256))(Keep.right)

  // By running/materializing the producer, we get back a Source, which
  // gives us access to the elements published by the producer.
  val fromProducer2: Source[String, NotUsed] = runnableGraph3.run()

  // Print out messages from the producer in two independent consumers
  fromProducer2.runForeach(msg ⇒ println("consumer1: " + msg))
  fromProducer2.runForeach(msg ⇒ println("consumer2: " + msg))

  // A simple producer that publishes a new "message-" every second
  val producer3 = Source.tick(1.second, 1.second, "message").zipWith(Source(1 to 100))((a, b) ⇒ s"$a-$b")

  // New instance of the partitioner function and its state is created for each materialization of the PartitionHub.
  def roundRobin(): (PartitionHub.ConsumerInfo, String) ⇒ Long = {
    var i = -1L
    (info, elem) ⇒ {
      i += 1
      info.consumerIdByIdx((i % info.size).toInt)
    }
  }

  // Attach a PartitionHub Sink to the producer. This will materialize to a corresponding Source.
  // (We need to use toMat and Keep.right since by default the materialized value to the left is used)
  val runnableGraph4: RunnableGraph[Source[String, NotUsed]] =
    producer3.toMat(PartitionHub.statefulSink(
      () ⇒ roundRobin(),
      startAfterNrOfConsumers = 2, bufferSize = 256))(Keep.right)

  // By running/materializing the producer, we get back a Source, which
  // gives us access to the elements published by the producer.
  val fromProducer3: Source[String, NotUsed] = runnableGraph4.run()

  // Print out messages from the producer in two independent consumers
  fromProducer3.runForeach(msg ⇒ println("consumer1: " + msg))
  fromProducer3.runForeach(msg ⇒ println("consumer2: " + msg))

  val producer4 = Source(0 until 100)

  // ConsumerInfo.queueSize is the approximate number of buffered elements for a consumer.
  // Note that this is a moving target since the elements are consumed concurrently.
  val runnableGraph5: RunnableGraph[Source[Int, NotUsed]] =
    producer4.toMat(PartitionHub.statefulSink(
      () ⇒ (info, elem) ⇒ info.consumerIds.minBy(id ⇒ info.queueSize(id)),
      startAfterNrOfConsumers = 2, bufferSize = 16))(Keep.right)

  val fromProducer4: Source[Int, NotUsed] = runnableGraph5.run()

  fromProducer4.runForeach(msg ⇒ println("consumer1: " + msg))
  fromProducer4.throttle(10, 100.millis, 1, ThrottleMode.Shaping).runForeach(msg ⇒ println("consumer2: " + msg))

  // Shut down externally
  Future {
    Thread.sleep(20000)
    killSwitch.shutdown()
    sharedKillSwitch.shutdown()
    switch.shutdown()
    system.terminate()
  }

}