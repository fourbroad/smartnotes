package com.zxtx.actors

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Timers
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.LWWMap
import akka.cluster.ddata.LWWMapKey
import akka.cluster.sharding.ClusterSharding
import scala.concurrent.duration._

object NameService {
  def props: Props = Props[NameService]

  private final case class Request(cmd: Any, replyTo: ActorRef, deadline: Deadline)

  sealed trait NameServiceCommand
  final case class PutInCache(key: String, value: Any) extends NameServiceCommand
  final case class GetFromCache(key: String) extends NameServiceCommand
  final case class Evict(key: String) extends NameServiceCommand

  sealed trait NameServiceResponse
  final case class Cached(key: String, value: Option[Any]) extends NameServiceResponse

  private case object CleanupTick
}

class NameService extends Actor with Timers with ActorLogging {
  import DocumentSetActor._
  import DomainActor._
  import NameService._
  import akka.cluster.ddata.Replicator._

  private var requesters = Map[String, Request]()

  val system = context.system

  implicit val cluster = Cluster(system)
  val replicator = DistributedData(system).replicator

  val documentSetRegion = ClusterSharding(system).shardRegion(DocumentSetActor.shardName)
  val domainRegion = ClusterSharding(system).shardRegion(DomainActor.shardName)

  val timeout = system.settings.config.getDuration("name_service.timeout").getSeconds.seconds
  def newStaleWorkerDeadline(): Deadline = timeout.fromNow

  timers.startPeriodicTimer("cleanup", CleanupTick, timeout / 2)

  def receive = {
    case UpdateSuccess(LWWMapKey(key), Some(dsc)) =>
      val request = requesters.get(key).get
      if (request.cmd.isInstanceOf[CreateDocumentSet]) {
        request.replyTo ! dsc
      }

    case Evict(key) =>
      replicator ! Update(LWWMapKey[String, Any](key), LWWMap(), WriteLocal)(_ - key)
    case CleanupTick =>
      requesters.foreach {
        case (name, Request(cmd, replyTo, deadLine)) if deadLine.isOverdue() =>
          requesters -= name
      }

    case GetFromCache(key) =>
      replicator ! Get(LWWMapKey[String, Any](key), ReadLocal, Some(sender))
    case g @ GetSuccess(LWWMapKey(key), Some(replyTo)) =>
      g.dataValue match {
        case data: LWWMap[_, _] => data.asInstanceOf[LWWMap[String, Any]].get(key) match {
          case Some(value) => replyTo.asInstanceOf[ActorRef] ! Cached(key, Some(value))
          case None        => replyTo.asInstanceOf[ActorRef] ! Cached(key, None)
        }
      }
    case NotFound(LWWMapKey(key), Some(replyTo)) =>
      replyTo.asInstanceOf[ActorRef] ! Cached(key, None)
    case _: UpdateResponse[_] => // ok
  }

}
