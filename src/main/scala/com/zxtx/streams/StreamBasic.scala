package com.zxtx.streams

import java.nio.file.Paths

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success

import akka.NotUsed
import akka.actor.Actor
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.ClosedShape
import akka.stream.IOResult
import akka.stream.OverflowStrategy
import akka.stream.ThrottleMode
import akka.stream.scaladsl.Broadcast
import akka.stream.scaladsl.FileIO
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.GraphDSL
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.RunnableGraph
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.stream.Materializer

final case class Author(handle: String)
final case class Hashtag(name: String)

final case class Tweet(author: Author, timestamp: Long, body: String) {
  def hashtags: Set[Hashtag] = body.split(" ").collect {
    case t if t.startsWith("#") ⇒ Hashtag(t.replaceAll("[^#\\w]", ""))
  }.toSet
}

object StreamBasic extends App {
  val akkaTag = Hashtag("#akka")
  val tweets: Source[Tweet, NotUsed] = Source(
    Tweet(Author("rolandkuhn"), System.currentTimeMillis, "#akka rocks!") ::
      Tweet(Author("patriknw"), System.currentTimeMillis, "#akka !") ::
      Tweet(Author("bantonsson"), System.currentTimeMillis, "#akka !") ::
      Tweet(Author("drewhk"), System.currentTimeMillis, "#akka !") ::
      Tweet(Author("ktosopl"), System.currentTimeMillis, "#akka on the rocks!") ::
      Tweet(Author("mmartynas"), System.currentTimeMillis, "wow #akka !") ::
      Tweet(Author("akkateam"), System.currentTimeMillis, "#akka rocks!") ::
      Tweet(Author("bananaman"), System.currentTimeMillis, "#bananas rock!") ::
      Tweet(Author("appleman"), System.currentTimeMillis, "#apples rock!") ::
      Tweet(Author("drama"), System.currentTimeMillis, "we compared #apples to #oranges!") ::
      Nil)

  implicit val system = ActorSystem("reactive-tweets")
  implicit val ex = system.dispatcher
  implicit val materializer = ActorMaterializer()

  val source: Source[Int, NotUsed] = Source(1 to 100)
  val factorials = source.scan(BigInt(1))((acc, next) ⇒ acc * next)
  def lineSink(filename: String): Sink[String, Future[IOResult]] = Flow[String].map(s ⇒ ByteString(s + "\n")).toMat(FileIO.toPath(Paths.get(filename)))(Keep.right)
  factorials.map(_.toString).runWith(lineSink("factorial.txt"))

  val writeAuthors = Sink.foreach(println)
  val writeHashtags = Sink.foreach(println)
  val g = RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._
    val bcast = b.add(Broadcast[Tweet](2))
    tweets ~> bcast.in
    bcast.out(0) ~> Flow[Tweet].map(_.author) ~> writeAuthors
    bcast.out(1) ~> Flow[Tweet].mapConcat(_.hashtags.toList) ~> writeHashtags
    ClosedShape
  })
  g.run()

  tweets
    .buffer(10, OverflowStrategy.dropHead)
    .map(_.hashtags) // Get all sets of hashtags ...
    .reduce(_ ++ _) // ... and reduce them to a single set, removing duplicates across all tweets
    .mapConcat(identity) // Flatten the stream of tweets to a stream of hashtags
    .map(_.name.toUpperCase) // Convert all hashtags to upper case
    .runWith(Sink.foreach(println)) // Attach the Flow to a Sink that will finally print the hashtags

  val count: Flow[Tweet, Int, NotUsed] = Flow[Tweet].map(_ ⇒ 1)
  val sumSink: Sink[Int, Future[Int]] = Sink.fold[Int, Int](0)(_ + _)
  val counterGraph: RunnableGraph[Future[Int]] = tweets.via(count).toMat(sumSink)(Keep.right)
  val sum: Future[Int] = counterGraph.run()
  sum.foreach(c ⇒ println(s"Total tweets processed: $c"))

  val counterRunnableGraph: RunnableGraph[Future[Int]] = tweets.filter(_.hashtags contains akkaTag).map(t ⇒ 1).toMat(sumSink)(Keep.right)
  // materialize the stream once in the morning
  val morningTweetsCount: Future[Int] = counterRunnableGraph.run()
  // and once in the evening, reusing the flow
  val eveningTweetsCount: Future[Int] = counterRunnableGraph.run()

  val sum2: Future[Int] = tweets.map(t ⇒ 1).runWith(sumSink)

  Source.unfold(0 → 1) {
    case (a, _) if a > 10000000 ⇒ None
    case (a, b)                 ⇒ Some((b → (a + b)) → a)
  }.runForeach(println)

  // Create a source from an Iterable
  Source(List(1, 2, 3))
  // Create a source from a Future
  Source.fromFuture(Future.successful("Hello Streams!"))
  // Create a source from a single element
  Source.single("only one element")
  // an empty source
  Source.empty
  // Sink that folds over the stream and returns a Future
  // of the final result as its materialized value
  Sink.fold[Int, Int](0)(_ + _)
  // Sink that returns a Future as its materialized value,
  // containing the first element of the stream
  Sink.head
  // A Sink that consumes a stream without doing anything with the elements
  Sink.ignore
  // A Sink that executes a side-effecting call for every element of the stream
  Sink.foreach[String](println(_))

  // Explicitly creating and wiring up a Source, Sink and Flow
  Source(1 to 6).via(Flow[Int].map(_ * 2)).to(Sink.foreach(println(_)))
  // Starting from a Source
  val source2 = Source(1 to 6).map(_ * 2)
  source2.to(Sink.foreach(println(_)))
  // Starting from a Sink
  val sink: Sink[Int, NotUsed] = Flow[Int].map(_ * 2).to(Sink.foreach(println(_)))
  Source(1 to 6).to(sink)
  // Broadcast to a sink inline
  val otherSink: Sink[Int, NotUsed] = Flow[Int].alsoTo(Sink.foreach(println(_))).to(Sink.ignore)
  Source(1 to 6).to(otherSink)

  Source(List(1, 2, 3)).map(_ + 1).async.map(_ * 2).to(Sink.ignore)

  // An source that can be signalled explicitly from the outside
  val source3: Source[Int, Promise[Option[Int]]] = Source.maybe[Int]
  // A flow that internally throttles elements to 1/second, and returns a Cancellable
  // which can be used to shut down the stream
  //  val flow: Flow[Int, Int, Cancellable] = throttler
  val flow = Flow[Int].throttle(1, 1.second, 1, ThrottleMode.Shaping)

  // A sink that returns the first element of a stream in the returned Future
  val sink2: Sink[Int, Future[Int]] = Sink.head[Int]

  // By default, the materialized value of the leftmost stage is preserved
  val r1: RunnableGraph[Promise[Option[Int]]] = source3.via(flow).to(sink)

  // Simple selection of materialized values by using Keep.right
  //  val r2: RunnableGraph[Cancellable] = source3.viaMat(flow)(Keep.right).to(sink)
  //  val r3: RunnableGraph[Future[Int]] = source3.via(flow).toMat(sink)(Keep.right)

  // Using runWith will always give the materialized values of the stages added
  // by runWith() itself
  val r4: Future[Int] = source.via(flow).runWith(sink2)
  val r5: Promise[Option[Int]] = flow.to(sink).runWith(source3)
  val r6: (Promise[Option[Int]], Future[Int]) = flow.runWith(source3, sink2)

  // Using more complex combinations
  //  val r7: RunnableGraph[(Promise[Option[Int]], Cancellable)] = source3.viaMat(flow)(Keep.both).to(sink)

  //  val r8: RunnableGraph[(Promise[Option[Int]], Future[Int])] = source.via(flow).toMat(sink)(Keep.both)

  //  val r9: RunnableGraph[((Promise[Option[Int]], Cancellable), Future[Int])] = source.viaMat(flow)(Keep.both).toMat(sink)(Keep.both)

  //  val r10: RunnableGraph[(Cancellable, Future[Int])] = source.viaMat(flow)(Keep.right).toMat(sink)(Keep.both)

  // It is also possible to map over the materialized values. In r9 we had a
  // doubly nested pair, but we want to flatten it out
  //  val r11: RunnableGraph[(Promise[Option[Int]], Cancellable, Future[Int])] =  r9.mapMaterializedValue {
  //      case ((promise, cancellable), future) ⇒
  //        (promise, cancellable, future)
  //    }

  // Now we can use pattern matching to get the resulting materialized values
  //  val (promise, cancellable, future) = r11.run()

  // Type inference works as expected
  //  promise.success(None)
  //  cancellable.cancel()
  //  future.map(_ + 3)

  // The result of r11 can be also achieved by using the Graph API
  //  val r12: RunnableGraph[(Promise[Option[Int]], Cancellable, Future[Int])] =
  //    RunnableGraph.fromGraph(GraphDSL.create(source, flow, sink)((_, _, _)) { implicit builder ⇒ (src, f, dst) ⇒
  //      import GraphDSL.Implicits._
  //      src ~> f ~> dst
  //      ClosedShape
  //    })

  val matValuePoweredSource = Source.actorRef[String](bufferSize = 100, overflowStrategy = OverflowStrategy.fail)

  //  val (actorRef, source) = matValuePoweredSource.preMaterialize()

  // pass source around for materialization
  val (actorRef, future) = matValuePoweredSource.map { a => println(a); a }.toMat(Sink.last)(Keep.both).named("foreachSink").run()
  future.map(h => println(h + "2"))
  actorRef ! "Hello!"

  factorials
    .zipWith(Source(0 to 100))((num, idx) ⇒ s"$idx! = $num")
    .throttle(1, 1.second, 1, ThrottleMode.Shaping)
    .runForeach(println)

  // $FiddleDependency org.akka-js %%% akkajsactorstream % 1.2.5.1

}

final class RunWithMyself extends Actor {
  implicit val mat = ActorMaterializer()

  val t = Source.maybe.alsoTo(Sink.onComplete {
    case Success(done) ⇒ println(s"Completed: $done")
    case Failure(ex)   ⇒ println(s"Failed: ${ex.getMessage}")
  }).to(Sink.ignore).run()

  def receive = {
    case "boom" ⇒ context.stop(self) // will also terminate the stream
  }
}

final class RunForever(implicit val mat: Materializer) extends Actor {
  Source.maybe.runWith(Sink.onComplete {
    case Success(done) ⇒ println(s"Completed: $done")
    case Failure(ex)   ⇒ println(s"Failed: ${ex.getMessage}")
  })

  def receive = {
    case "boom" ⇒ context.stop(self) // will NOT terminate the stream (it's bound to the system!)
  }
}