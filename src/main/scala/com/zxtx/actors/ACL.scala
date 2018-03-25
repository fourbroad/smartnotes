package com.zxtx.actors

import spray.json.JsObject
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import spray.json.JsArray
import spray.json.JsString
import com.zxtx.actors.DocumentActor._
import akka.cluster.sharding.ClusterSharding
import akka.actor.Actor
import akka.pattern.ask
import akka.util.Timeout
import akka.stream.ActorMaterializerSettings
import akka.stream.ActorMaterializer

trait ACL { this: Actor =>
  def aclValue(aclObj: JsObject, op: String, name: String): Vector[String] = aclObj.getFields(op) match {
    case Seq(opObj: JsObject) => opObj.getFields(name) match {
      case Seq(ja: JsArray) => ja.elements.map { jv => jv.asInstanceOf[JsString].value }
      case _                => Vector[String]()
    }
    case _ => Vector[String]()
  }

  def profileValue(profile: JsObject, name: String): Vector[String] = profile.getFields(name) match {
    case Seq(ja: JsArray) => ja.elements.map { jv => jv.asInstanceOf[JsString].value }
    case _                => Vector[String]()
  }

  def fetchProfile(domain: String, user: String): Future[JsObject] = {
    implicit val timeOut = Timeout(5.seconds)
    implicit val materializer = ActorMaterializer(ActorMaterializerSettings(context.system))
    val documentRegion = ClusterSharding(context.system).shardRegion(DocumentActor.shardName)
    Source.fromFuture(documentRegion ? GetDocument(s"${domain}~profiles~${user}", user)).map {
      case Document(_, _, _, _, _, _, raw) => raw
      case other                           => throw new RuntimeException(other.toString)
    }.runWith(Sink.head[JsObject])
  }
}

object ACL {
  trait Permission
  case object Granted extends Permission
  case object Denied extends Permission

  case class CheckPermission(pid: String, user: String, command: Command) extends Command
  case class CheckPermissionException(exception: Throwable) extends Exception
}
