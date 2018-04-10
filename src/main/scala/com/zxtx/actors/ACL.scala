package com.zxtx.actors

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import com.zxtx.actors.DocumentActor.GetDocument

import akka.actor.Actor
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.LWWMap
import akka.cluster.ddata.LWWMapKey
import akka.cluster.ddata.Replicator.Get
import akka.cluster.ddata.Replicator.GetSuccess
import akka.cluster.ddata.Replicator.NotFound
import akka.cluster.ddata.Replicator.ReadLocal
import akka.cluster.ddata.Replicator.ReadMajority
import akka.cluster.ddata.Replicator.Update
import akka.cluster.ddata.Replicator.UpdateSuccess
import akka.cluster.ddata.Replicator.UpdateFailure
import akka.cluster.ddata.Replicator.WriteLocal
import akka.cluster.ddata.Replicator.WriteMajority
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.Timeout

import pdi.jwt.Jwt
import pdi.jwt.JwtAlgorithm
import pdi.jwt.JwtBase64
import spray.json._
import akka.cluster.Cluster

trait ACL { this: Actor =>
  import ACL._
  
  val replicator = DistributedData(context.system).replicator
  val secretCacheKey: String = "SecretCacheKey"

  val system = context.system
  implicit val executionContext = context.dispatcher
  implicit val cluster = Cluster(system)

  implicit val duration = 5.seconds
  implicit val timeOut = Timeout(duration)

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
    val documentRegion = ClusterSharding(context.system).shardRegion(DocumentActor.shardName)
    documentRegion ? GetDocument(s"${domain}~profiles~${user}", user)
  }

  def check(token: String, command: Any)(callback: (String) => Future[Any]) = validateToken(token).flatMap {
    case Some(user) => checkPermission(user, command).flatMap {
      case Granted => callback(user)
      case Denied  => Future.successful(Denied)
    }
    case None => Future.successful(TokenInvalid)
  }.recover { case e => e }

  def validateToken(token: String): Future[Option[String]] = {
    val parts = token.split('.')
    JwtBase64.decodeString(parts(1)).parseJson match {
      case jo: JsObject => jo.getFields("id") match {
        case Seq(JsString(uid)) => getSecretKey(uid).map {
          case Some(secretKey) if (Jwt.isValid(token, secretKey, Seq(JwtAlgorithm.HS256))) => Some(uid)
          case _ => None
        }
        case _ => Future.successful(None)
      }
      case _ => Future.successful(None)
    }
  }

  def getSecretKey(user: String) = (replicator ? Get(LWWMapKey[String, Any](secretCacheKey), ReadLocal)).map {
    case g @ GetSuccess(LWWMapKey(_), _) =>
      g.dataValue match {
        case data: LWWMap[_, _] => data.asInstanceOf[LWWMap[String, String]].get(user)
        case _                  => None
      }
    case NotFound(_, _) => None
  }

  def updateSecretKey(user: String, secretKey: String) =
    (replicator ? Update(LWWMapKey[String, Any](secretCacheKey), LWWMap(), WriteLocal)(_ + (user -> secretKey))).map {
      case UpdateSuccess(LWWMapKey(_), _)    => true
      case _: UpdateFailure[LWWMapKey[_, _]] => false
    }

  def clearSecretKey(user: String): Future[Boolean] =
    (replicator ? Update(LWWMapKey[String, Any](secretCacheKey), LWWMap(), WriteLocal)(_ - user)).map {
      case UpdateSuccess(LWWMapKey(_), _)    => true
      case _: UpdateFailure[LWWMapKey[_, _]] => false
    }

  def checkPermission(user: String, command: Any): Future[Permission]
}

object ACL {
  trait Permission
  case object Granted extends Permission
  case object Denied extends Permission

  sealed trait ValidateResult
  object TokenValid extends ValidateResult
  object TokenInvalid extends ValidateResult

  case class CheckPermission(pid: String, user: String, command: Any) extends Command
  case class CheckPermissionException(exception: Throwable) extends Exception
}
