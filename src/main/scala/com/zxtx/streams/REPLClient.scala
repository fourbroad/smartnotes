package com.zxtx.streams

import scala.io.StdIn
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Framing
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Tcp
import akka.util.ByteString

object REPLClient extends App {
  val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + 2556)
  implicit val system = ActorSystem("REPLClient", config)
  implicit val materializer = ActorMaterializer()

  val connection = Tcp().outgoingConnection("127.0.0.1", 8080)
  val replParser = Flow[String].takeWhile(_ != "q").concat(Source.single("BYE")).map(elem ⇒ ByteString(s"$elem\n"))
  val repl = Flow[ByteString].via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 256, allowTruncation = true))
    .map(_.utf8String)
    .map(text ⇒ println("Server: " + text))
    .map(_ ⇒ StdIn.readLine("> "))
    .via(replParser)
  val connected = connection.join(repl).run()
}