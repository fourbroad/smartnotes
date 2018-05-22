package com.zxtx.streams

import java.nio.file.Paths

import scala.concurrent.Future

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.IOResult
import akka.stream.scaladsl.FileIO
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Framing
import akka.stream.scaladsl.JsonFraming
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Tcp
import akka.stream.scaladsl.Tcp.IncomingConnection
import akka.stream.scaladsl.Tcp.ServerBinding
import akka.util.ByteString

object StreamIO extends App {
  val host = "127.0.0.1"
  val port = 8080

  implicit val system = ActorSystem("StreamGraphs")
  implicit val materializer = ActorMaterializer()
  implicit val ex = system.dispatcher

  val connections: Source[IncomingConnection, Future[ServerBinding]] = Tcp().bind(host, port)

  //  connections runForeach { connection ⇒
  //    println(s"New connection from: ${connection.remoteAddress}")
  //    val echo = Flow[ByteString].via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 256, allowTruncation = true))
  //      .map(_.utf8String)
  //      .map(_ + "!!!\n")
  //      .map(ByteString(_))
  //    connection.handleWith(echo)
  //  }

  connections.to(Sink.foreach { connection ⇒
    // server logic, parses incoming commands
    val commandParser = Flow[String].takeWhile(_ != "BYE").map(_ + "!")

    import connection._
    val welcomeMsg = s"Welcome to: $localAddress, you are: $remoteAddress!"
    val welcome = Source.single(welcomeMsg)

    val serverLogic = Flow[ByteString].via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 256, allowTruncation = true))
      .map(_.utf8String)
      .via(commandParser)
      // merge in the initial banner after parser
      .merge(welcome)
      .map(_ + "\n")
      .map(ByteString(_))

    connection.handleWith(serverLogic)
  }).run()

  val input =
    """
    |[
    | { "name" : "john" },
    | { "name" : "Ég get etið gler án þess að meiða mig" },
    | { "name" : "jack" },
    |]
    |""".stripMargin // also should complete once notices end of array

  Source.single(ByteString(input))
    .via(JsonFraming.objectScanner(Int.MaxValue))
    .runFold(Seq.empty[String]) {
      case (acc, entry) ⇒ println(entry.utf8String); acc ++ Seq(entry.utf8String)
    }.foreach(println)

  val file = Paths.get("example.csv")
  val foreach: Future[IOResult] = FileIO.fromPath(file).to(Sink.ignore).run()
}