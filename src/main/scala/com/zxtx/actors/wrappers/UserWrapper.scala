package com.zxtx.actors.wrappers

import scala.collection.mutable.Queue
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Function
import com.eclipsesource.v8.V8Object
import com.zxtx.actors.DocumentActor
import com.zxtx.actors.DocumentActor._
import com.zxtx.actors.UserActor
import com.zxtx.actors.UserActor._
import com.zxtx.actors.ACL._
import com.zxtx.actors.UserActor.JsonProtocol._

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import gnieh.diffson.sprayJson.JsonPatch
import spray.json.enrichAny
import spray.json.JsString

import com.roundeights.hasher.Implicits.stringToHasher
import pdi.jwt._

class UserWrapper(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) extends Wrapper(system, callbackQueue) with V8SprayJson {
  import CallbackWrapper._
  import Wrapper._

  val documentRegion: ActorRef = ClusterSharding(system).shardRegion(DocumentActor.shardName)
  val userRegion: ActorRef = ClusterSharding(system).shardRegion(UserActor.shardName)

  def userPID(userId: String) = s"${rootDomain}~.users~${userId}"

  def bind(receiver: V8Object) = {
    val runtime = receiver.getRuntime
    val dw = runtime.getObject("__UserWrapper")
    val prototype = runtime.executeObjectScript("__UserWrapper.prototype")

    prototype.registerJavaMethod(this, "register", "register", Array[Class[_]](classOf[V8Object], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "login", "login", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "logout", "logout", Array[Class[_]](classOf[V8Object], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "isValidToken", "isValidToken", Array[Class[_]](classOf[V8Object], classOf[String], classOf[V8Function]), true)

    prototype.registerJavaMethod(this, "create", "create", Array[Class[_]](classOf[V8Object], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "get", "get", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "replace", "replace", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "patch", "patch", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Array], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "remove", "remove", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Function]), true)

    prototype.registerJavaMethod(this, "resetPassword", "resetPassword", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    
    prototype.registerJavaMethod(this, "getACL", "getACL", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "replaceACL", "replaceACL", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "patchACL", "patchACL", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Array], classOf[V8Function]), true)

    dw.setPrototype(prototype)
    prototype.release
    dw.release
  }

  def register(receiver: V8Object, v8UserInfo: V8Object, callback: V8Function) = {
    val jsUserInfo = toJsObject(v8UserInfo)
    val cbw = CallbackWrapper(receiver, callback)
    jsUserInfo.fields.get("id") match {
      case Some(JsString(userId)) => (userRegion ? CreateUser(userPID(userId), "anonymous", jsUserInfo)).recover { case e => e }.foreach {
        case u: User => userCallback(cbw, u)
        case other   => failureCallback(cbw, other)
      }
      case _ => failureCallback(cbw, UserIdNotExists)
    }
  }

  def create(receiver: V8Object, token: String, v8UserInfo: V8Object, callback: V8Function) = {
    val jsUserInfo = toJsObject(v8UserInfo)
    val cbw = CallbackWrapper(receiver, callback)
    jsUserInfo.fields.get("id") match {
      case Some(JsString(userId)) => validateToken(token).flatMap {
        case TokenValid(user) => userRegion ? CreateUser(userPID(userId), user, jsUserInfo)
        case other            => Future.successful(other)
      }.recover { case e => e }.foreach {
        case u: User => userCallback(cbw, u)
        case other   => failureCallback(cbw, other)
      }
      case _ => failureCallback(cbw, UserIdNotExists)
    }
  }

  def login(receiver: V8Object, userId: String, password: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (userRegion ? GetUser(userPID(userId), userId)).flatMap {
      case user: User =>
        val hexMd5 = user.raw.fields("password").asInstanceOf[JsString].value
        if (hexMd5 == password.md5.hex) {
          val secretKey = (hexMd5 + System.currentTimeMillis).md5.hex
          val token = Jwt.encode(JwtHeader(JwtAlgorithm.HS256), JwtClaim(s"""{"id":"${userId}"}"""), secretKey)
          updateSecretKey(userId, secretKey).map {
            case SecretKeyUpdated => token
            case other            => other
          }
        } else Future.successful(UserIdPasswordError)
      case UserNotFound => Future.failed(UserNotFound)
      case other        => Future.successful(other)
    }.recover { case e => e }.foreach {
      case token: String =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            params.pushNull()
            params.push(token)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def logout(receiver: V8Object, token: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => clearSecretKey(user)
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case SecretKeyCleared => successCallback(cbw)
      case other            => failureCallback(cbw, other)
    }
  }

  def isValidToken(receiver: V8Object, token: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).recover { case e => e }.foreach {
      case result: ValidateResult =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            params.pushNull()
            params.push(result match {
              case TokenValid(_) => true
              case TokenInvalid  => false
            })
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def get(receiver: V8Object, token: String, userId: String, callback: V8Function) =
    commandWithUser(receiver, token, callback) { user => userRegion ? GetUser(userPID(userId), user) }

  def replace(receiver: V8Object, token: String, userId: String, v8UserInfo: V8Object, callback: V8Function) = {
    val jsUserInfo = toJsObject(v8UserInfo)
    commandWithUser(receiver, token, callback) { user => userRegion ? ReplaceUser(userPID(userId), user, jsUserInfo) }
  }

  def patch(receiver: V8Object, token: String, userId: String, v8Patch: V8Array, callback: V8Function) = {
    val jsPatch = toJsArray(v8Patch)
    commandWithUser(receiver, token, callback) { user => userRegion ? PatchUser(userPID(userId), user, JsonPatch(jsPatch)) }
  }

  def remove(receiver: V8Object, token: String, userId: String, callback: V8Function) =
    commandWithSuccess(receiver, token, callback) { user => userRegion ? RemoveUser(userPID(userId), user) }

  def resetPassword(receiver: V8Object, token: String, userId: String, newPassword: String, callback: V8Function) =
    commandWithSuccess(receiver, token, callback) { user => userRegion ? ResetPassword(userPID(userId), user, newPassword) }

  def getACL(receiver: V8Object, token: String, userId: String, callback: V8Function) =
    commandWithACL(receiver, token, callback) { user => userRegion ? GetACL(userPID(userId), user) }

  def replaceACL(receiver: V8Object, token: String, userId: String, v8ACL: V8Object, callback: V8Function) = {
    val jsACL = toJsObject(v8ACL)
    commandWithSuccess(receiver, token, callback) { user => userRegion ? ReplaceACL(userPID(userId), user, jsACL) }
  }

  def patchACL(receiver: V8Object, token: String, userId: String, v8ACLPatch: V8Array, callback: V8Function) = {
    val jsACLPatch = toJsArray(v8ACLPatch)
    commandWithSuccess(receiver, token, callback) { user => userRegion ? PatchACL(userPID(userId), user, JsonPatch(jsACLPatch)) }
  }

  private def commandWithUser(receiver: V8Object, token: String, callback: V8Function)(cmd: (String) => Future[Any]) =
    command[User](receiver, token, callback)(cmd) { (cbw, u) => userCallback(cbw, u) }

  private def userCallback(cbw: CallbackWrapper, u: User) = {
    cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
      def prepare(params: V8Array) = {
        val v8Object = toV8Object(u.toJson.asJsObject, cbw.runtime)
        toBeReleased += v8Object
        params.pushNull()
        params.push(v8Object)
      }
    })
    enqueueCallback(cbw)
  }

}

object UserWrapper {
  def apply(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) = new UserWrapper(system, callbackQueue)
}
