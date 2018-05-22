package com.zxtx.httpservice

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import com.typesafe.config.ConfigFactory
import com.zxtx.actors.DocumentActor
import com.zxtx.actors.DocumentActor.Document
import com.zxtx.actors.DocumentActor.GetDocument
import com.zxtx.actors.CollectionActor
import com.zxtx.actors.DomainActor
import com.zxtx.actors.DomainActor.CreateDomain
import com.zxtx.actors.DomainActor.Domain
import com.zxtx.actors.DomainActor.DomainCreated
import com.zxtx.actors.DomainActor.GetDomain
import com.zxtx.actors.NameService
import com.zxtx.persistence.ElasticSearchStore

import akka.actor.ActorSystem
import akka.actor.PoisonPill
import akka.pattern.ask
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.LWWMap
import akka.cluster.ddata.LWWMapKey
import akka.cluster.ddata.Replicator.Get
import akka.cluster.ddata.Replicator.GetSuccess
import akka.cluster.ddata.Replicator.NotFound
import akka.cluster.ddata.Replicator.ReadLocal
import akka.cluster.ddata.Replicator.ReadMajority
import akka.cluster.ddata.Replicator.WriteMajority
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.singleton.ClusterSingletonManager
import akka.cluster.singleton.ClusterSingletonManagerSettings
import akka.cluster.singleton.ClusterSingletonProxy
import akka.cluster.singleton.ClusterSingletonProxySettings
import akka.event.LogSource
import akka.event.Logging
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.Timeout
import spray.json.JsObject
import spray.json.JsString

import com.roundeights.hasher.Implicits.stringToHasher

object HttpService2 extends App with APIStatusCodes {
  implicit val logSource: LogSource[AnyRef] = new LogSource[AnyRef] {
    def genString(o: AnyRef): String = o.getClass.getName
    override def getClazz(o: AnyRef): Class[_] = o.getClass
  }

  val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + 2551).withFallback(ConfigFactory.load())

  implicit val system = ActorSystem("SmartNotes", config)
  val log = Logging(system, this)

  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher
  implicit val timeout = Timeout(30 seconds)
  implicit val cluster = Cluster(system)

  val rootDomain = system.settings.config.getString("domain.root-domain")
  val cacheKey = system.settings.config.getString("domain.cache-key")
  val replicator = DistributedData(system).replicator
  val store = ElasticSearchStore(system)

  private val readMajority = ReadMajority(5.seconds)
  private val writeMajority = WriteMajority(5.seconds)

  val domainRegion = ClusterSharding(system).start(
    typeName = DomainActor.shardName,
    entityProps = DomainActor.props(),
    settings = ClusterShardingSettings(system),
    extractEntityId = DomainActor.idExtractor,
    extractShardId = DomainActor.shardResolver)

  val collectionRegion = ClusterSharding(system).start(
    typeName = CollectionActor.shardName,
    entityProps = CollectionActor.props(),
    settings = ClusterShardingSettings(system),
    extractEntityId = CollectionActor.idExtractor,
    extractShardId = CollectionActor.shardResolver)

  val documentRegion = ClusterSharding(system).start(
    typeName = DocumentActor.shardName,
    entityProps = DocumentActor.props(),
    settings = ClusterShardingSettings(system),
    extractEntityId = DocumentActor.idExtractor,
    extractShardId = DocumentActor.shardResolver)

  system.actorOf(
    ClusterSingletonManager.props(NameService.props, PoisonPill, ClusterSingletonManagerSettings(system).withRole("nameService")),
    "nameService")

  val nameServiceProxy = system.actorOf(
    ClusterSingletonProxy.props(
      settings = ClusterSingletonProxySettings(system).withRole("nameService"),
      singletonManagerPath = "/user/nameService"),
    name = "nameServiceProxy")

  /**
   * [[ActorSystem]] used to start this server. Stopping this system will interfere with the proper functioning condition of the server.
   */
  val systemReference = new AtomicReference[ActorSystem](system)

  def checkPassword(user: SessionUser) = {
    Source.fromFuture {
      documentRegion ? GetDocument(s"${rootDomain}~.users~${user.name}", user.name)
    }.map {
      case doc: Document => doc.raw.fields("password").asInstanceOf[JsString].value == user.password.md5.hex
      case _             => false
    }.runWith(Sink.head[Boolean])
  }

  def checkDomain(domainName: String): Future[Boolean] = {
    val queryCache = replicator ? Get(LWWMapKey[String, Any](cacheKey), ReadLocal)
    queryCache.map {
      case g @ GetSuccess(LWWMapKey(_), _) =>
        g.dataValue match {
          case data: LWWMap[_, _] => data.asInstanceOf[LWWMap[String, Any]].get(domainName) match {
            case Some(_) => true
            case None    => false
          }
        }
      case NotFound(_, _) => false
    }
  }

  def checkCollection(domainName: String, collectionName: String): Future[Boolean] = {
    val key = s"${domainName}~${collectionName}"
    val queryCache = replicator ? Get(LWWMapKey[String, Any](cacheKey), ReadLocal)
    queryCache.map {
      case g @ GetSuccess(LWWMapKey(_), _) =>
        g.dataValue match {
          case data: LWWMap[_, _] => data.asInstanceOf[LWWMap[String, Any]].get(key) match {
            case Some(_) => true
            case None    => false
          }
        }
      case NotFound(_, _) => false
    }
  }

  val adminName = system.settings.config.getString("domain.administrator.name")
  for {
    r1 <- Source.fromFuture {
      domainRegion ? GetDomain(s"${rootDomain}~.domains~${rootDomain}", adminName)
    }.runWith(Sink.head[Any])
    if !r1.isInstanceOf[Domain]
    r2 <- Source.fromFuture {
      domainRegion ? CreateDomain(s"${rootDomain}~.domains~${rootDomain}", adminName, JsObject())
    }.runWith(Sink.head[Any])
    if r2.isInstanceOf[DomainCreated]
  } System.out.println("System intialize successfully!")

}