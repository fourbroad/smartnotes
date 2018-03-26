package com.zxtx.actors

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import com.zxtx.actors.DocumentActor.GetDocument

import akka.actor.Actor
import akka.cluster.sharding.ClusterSharding
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.pattern.ask
import akka.util.Timeout
import spray.json.JsArray
import spray.json.JsObject
import spray.json.JsString

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

  def fetchProfile(domain: String, user: String): Future[Any] = {
    implicit val timeOut = Timeout(5.seconds)
    implicit val materializer = ActorMaterializer(ActorMaterializerSettings(context.system))
    val documentRegion = ClusterSharding(context.system).shardRegion(DocumentActor.shardName)
    Source.fromFuture(documentRegion ? GetDocument(s"${domain}~profiles~${user}", user)).runWith(Sink.head[Any])
  }
}

object ACL {
  trait Permission
  case object Granted extends Permission
  case object Denied extends Permission

  case class CheckPermission(pid: String, user: String, command: Command) extends Command
  case class CheckPermissionException(exception: Throwable) extends Exception
}
