package com.zxtx.streams

import akka.pattern.pipe
import akka.stream.ActorMaterializer
import akka.actor.Actor
import akka.stream.scaladsl.Source
import scala.concurrent.Future
import akka.NotUsed

//case class RequestLogs(streamId: Int)
//case class LogsOffer(streamId: Int, sourceRef: SourceRef[String])
//
//class DataSource extends Actor {
//  import context.dispatcher
//  implicit val mat = ActorMaterializer()(context)
//
//  def receive = {
//    case RequestLogs(streamId) ⇒
//      // obtain the source you want to offer:
//      val source: Source[String, NotUsed] = streamLogs(streamId)
//
//      // materialize the SourceRef:
//      val ref: Future[SourceRef[String]] = source.runWith(StreamRefs.sourceRef())
//
//      // wrap the SourceRef in some domain message, such that the sender knows what source it is
//      val reply: Future[LogsOffer] = ref.map(LogsOffer(streamId, _))
//
//      // reply to sender
//      reply pipeTo sender()
//  }
//
//  def streamLogs(streamId: Long): Source[String, NotUsed] = ???
//}

object StreamRefs extends App {

}