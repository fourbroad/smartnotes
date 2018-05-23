package com.zxtx.actors.wrappers

import scala.collection.mutable.Queue
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.sys.process._

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout

import akka.cluster.Cluster
import akka.cluster.ddata.LWWMapKey
import akka.cluster.ddata.LWWMap
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.Replicator._

import pdi.jwt.JwtBase64
import pdi.jwt.Jwt
import pdi.jwt.JwtAlgorithm
import posix.Signal

import spray.json._

import com.eclipsesource.v8.V8Object
import com.eclipsesource.v8.V8Array

import com.zxtx.actors.DomainActor._
import com.zxtx.actors.DocumentActor._
import com.zxtx.actors.UserActor._
import com.zxtx.actors.CollectionActor._
import com.zxtx.actors.ACL._

class Wrapper(val system: ActorSystem, val callbackQueue: Queue[CallbackWrapper]) {
  import Wrapper._
  import CallbackWrapper._

  val rootDomain: String = system.settings.config.getString("domain.root-domain")

  implicit val ec: ExecutionContext = system.dispatcher
  implicit val duration = 5.seconds
  implicit val timeOut = Timeout(duration)
  implicit val cluster = Cluster(system)

  val secretCacheKey: String = "SecretCacheKey"
  val replicator = DistributedData(system).replicator

  val processId = Seq("sh", "-c", "echo $PPID").!!.trim.toInt
  val signal = Signal.SIGWINCH

  def validateToken(token: String): Future[ValidateResult] = JwtBase64.decodeString(token.split('.')(1)).parseJson match {
    case jo: JsObject => jo.getFields("id") match {
      case Seq(JsString(uid)) => getSecretKey(uid).map {
        case Some(secretKey) if (Jwt.isValid(token, secretKey, Seq(JwtAlgorithm.HS256))) => TokenValid(uid)
        case _ => TokenInvalid
      }
      case _ => Future.successful(TokenInvalid)
    }
    case _ => Future.successful(TokenInvalid)
  }

  def getSecretKey(user: String): Future[Option[String]] = (replicator ? Get(LWWMapKey[String, Any](secretCacheKey), ReadLocal)).map {
    case g @ GetSuccess(LWWMapKey(_), _) =>
      g.dataValue match {
        case data: LWWMap[_, _] => data.asInstanceOf[LWWMap[String, String]].get(user)
        case _                  => None
      }
    case NotFound(_, _) => None
  }

  def updateSecretKey(user: String, secretKey: String) = (replicator ? Update(LWWMapKey[String, Any](secretCacheKey), LWWMap(), WriteLocal)(_ + (user -> secretKey))).map {
    case UpdateSuccess(LWWMapKey(_), _)    => SecretKeyUpdated
    case _: UpdateFailure[LWWMapKey[_, _]] => UpdateSecretKeyError
  }

  def clearSecretKey(user: String) = (replicator ? Update(LWWMapKey[String, Any](secretCacheKey), LWWMap(), WriteLocal)(_ - user)).map {
    case UpdateSuccess(LWWMapKey(_), _)    => SecretKeyCleared
    case _: UpdateFailure[LWWMapKey[_, _]] => ClearSecretKeyError
  }

  def enqueueCallback(cbw: CallbackWrapper) = {
    callbackQueue.synchronized { callbackQueue.enqueue(cbw) }
    signal.kill(processId)
  }

  def failureCallback(cbw: CallbackWrapper, e: Any) = {
    cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
      def prepare(params: V8Array) = {
        val v8Object = new V8Object(runtime)
        e match {
          case TokenInvalid =>
            v8Object.add("code", 401)
            v8Object.add("message", "Token is invalid!")
          case Denied =>
            v8Object.add("code", 401)
            v8Object.add("message", "User have no permission to access!")
          case UserNotExists =>
            v8Object.add("code", 404)
            v8Object.add("message", "User not exists!")
          case DomainNotFound =>
            v8Object.add("code", 404)
            v8Object.add("message", "Domain is not found!")
          case DomainAlreadyExists =>
            v8Object.add("code", 409)
            v8Object.add("message", "Domain already exists!")
          case DomainIsCreating =>
            v8Object.add("code", 409)
            v8Object.add("message", "Domain is creating!")
          case DomainSoftDeleted =>
            v8Object.add("code", 410)
            v8Object.add("message", "Domain is soft deleted!")
          case CollectionAlreadyExists =>
            v8Object.add("code", 409)
            v8Object.add("message", "Collection already exists!")
          case CollectionNotFound =>
            v8Object.add("code", 404)
            v8Object.add("message", "Collection is not found!")
          case CollectionIsCreating =>
            v8Object.add("code", 409)
            v8Object.add("message", "Collection is creating!")
          case CollectionSoftDeleted =>
            v8Object.add("code", 410)
            v8Object.add("message", "Collection is soft deleted!")
          case DocumentNotFound =>
            v8Object.add("code", 404)
            v8Object.add("message", "Document is not found!")
          case DocumentAlreadyExists =>
            v8Object.add("code", 409)
            v8Object.add("message", "Document already exists!")
          case DocumentIsCreating =>
            v8Object.add("code", 409)
            v8Object.add("message", "Document is creating!")
          case DocumentSoftDeleted =>
            v8Object.add("code", 410)
            v8Object.add("message", "Document is soft deleted!")
          case UserNamePasswordError =>
            v8Object.add("code", 401)
            v8Object.add("message", "Username or password error!")
          case UserAlreadyRegistered =>
            v8Object.add("code", 409)
            v8Object.add("message", "User already registered!")
          case UserNameNotExists =>
            v8Object.add("code", 400)
            v8Object.add("message", "User name not exists!")
          case PasswordNotExists =>
            v8Object.add("code", 400)
            v8Object.add("message", "Password not exists!")
          case UserAlreadyJoined =>
            v8Object.add("code", 401)
            v8Object.add("message", "User already joined!")
          case UserNotJoined =>
            v8Object.add("code", 404)
            v8Object.add("message", "User not joined!")
          case UserAlreadyQuited =>
            v8Object.add("code", 410)
            v8Object.add("message", "User already quited!")
          case UserProfileIsSoftDeleted =>
            v8Object.add("code", 409)
            v8Object.add("message", "User profile is soft deleted, please run garbage collection!")
          case UpdateSecretKeyError =>
            v8Object.add("code", 500)
            v8Object.add("message", "Update secret key error!")
          case ClearSecretKeyError =>
            v8Object.add("code", 500)
            v8Object.add("message", "Clear secret key error!")
          case e =>
            v8Object.add("code", 500)
            v8Object.add("message", s"System error:${e.toString()}")
        }
        toBeReleased += v8Object
        params.push(v8Object)
        params.pushNull()
      }
    })
    enqueueCallback(cbw)
  }

}

object Wrapper {
  sealed trait ValidateResult
  case class TokenValid(user: String) extends ValidateResult
  object TokenInvalid extends ValidateResult

  sealed trait SecretKeyResult
  case object SecretKeyUpdated extends SecretKeyResult
  case object UpdateSecretKeyError extends SecretKeyResult
  case object SecretKeyCleared extends SecretKeyResult
  case object ClearSecretKeyError extends SecretKeyResult

  case object UserNotExists
}