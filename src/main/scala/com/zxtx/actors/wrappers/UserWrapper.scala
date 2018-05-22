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

  def userId(id: String) = s"${rootDomain}~.users~${id}"

  def bind(receiver: V8Object) = {
    val runtime = receiver.getRuntime
    val dw = runtime.getObject("__UserWrapper")
    val prototype = runtime.executeObjectScript("__UserWrapper.prototype")

    prototype.registerJavaMethod(this, "registerUser", "registerUser", Array[Class[_]](classOf[V8Object], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "login", "login", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "logout", "logout", Array[Class[_]](classOf[V8Object], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "isValidToken", "isValidToken", Array[Class[_]](classOf[V8Object], classOf[String], classOf[V8Function]), true)

    prototype.registerJavaMethod(this, "createUser", "createUser", Array[Class[_]](classOf[V8Object], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "getUser", "getUser", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "replaceUser", "replaceUser", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "patchUser", "patchUser", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Array], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "deleteUser", "deleteUser", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Function]), true)

    dw.setPrototype(prototype)
    prototype.release
    dw.release
  }

  def registerUser(receiver: V8Object, userInfo: V8Object, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    val jsObj = toJsObject(userInfo)
    jsObj.fields.get("userName") match {
      case Some(JsString(userName)) =>
        (userRegion ? CreateUser(userId(userName), "anonymous", jsObj)).recover { case e => e }.foreach {
          case uc: UserCreated =>
            cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
              def prepare(params: V8Array) = {
                val v8Object = toV8Object(uc.raw, cbw.runtime)
                toBeReleased += v8Object
                params.pushNull()
                params.push(v8Object)
              }
            })
            enqueueCallback(cbw)
          case other => failureCallback(cbw, other)
        }
      case _ => failureCallback(cbw, UserNameNotExists)
    }
  }

  def createUser(receiver: V8Object, token: String, userInfo: V8Object, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    val jsObj = toJsObject(userInfo)
    jsObj.fields.get("userName") match {
      case Some(JsString(userName)) => validateToken(token).flatMap {
        case TokenValid(user) => userRegion ? CreateUser(userId(userName), user, jsObj)
        case other            => Future.successful(other)
      }.recover { case e => e }.foreach {
        case uc: UserCreated =>
          cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
            def prepare(params: V8Array) = {
              val v8Object = toV8Object(uc.toJson.asJsObject, cbw.runtime)
              toBeReleased += v8Object
              params.pushNull()
              params.push(v8Object)
            }
          })
          enqueueCallback(cbw)
        case other => failureCallback(cbw, other)
      }
      case _ => failureCallback(cbw, UserNameNotExists)
    }
  }

  def login(receiver: V8Object, userName: String, password: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (userRegion ? GetUser(userId(userName), userName)).flatMap {
      case user: User =>
        val hexMd5 = user.raw.fields("password").asInstanceOf[JsString].value
        if (hexMd5 == password.md5.hex) {
          val secretKey = (hexMd5 + System.currentTimeMillis).md5.hex
          val token = Jwt.encode(JwtHeader(JwtAlgorithm.HS256), JwtClaim(s"""{"id":"${userName}"}"""), secretKey)
          updateSecretKey(userName, secretKey).map {
            case SecretKeyUpdated => token
            case other            => other
          }
        } else Future.successful(UserNamePasswordError)
      case DocumentNotFound => Future.successful(UserNotExists)
      case other            => Future.successful(other)
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
      case SecretKeyCleared =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            params.pushNull()
            params.push(true)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
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

  def getUser(receiver: V8Object, token: String, id: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => userRegion ? GetUser(userId(id), user)
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case u: User =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(u.toJson.asJsObject, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def replaceUser(receiver: V8Object, token: String, id: String, content: V8Object, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    val jsContent = toJsObject(content)
    validateToken(token).flatMap {
      case TokenValid(user) => userRegion ? ReplaceUser(userId(id), user, toJsObject(content))
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case ur: UserReplaced =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(ur.toJson.asJsObject, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def patchUser(receiver: V8Object, token: String, id: String, v8Patch: V8Array, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    val jsArray = toJsArray(v8Patch)
    validateToken(token).flatMap {
      case TokenValid(user) => userRegion ? PatchUser(userId(id), user, JsonPatch(toJsArray(v8Patch)))
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case up: UserPatched =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(up.toJson.asJsObject, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def deleteUser(receiver: V8Object, token: String, id: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => userRegion ? DeleteUser(userId(id), user)
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case u: User =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(u.toJson.asJsObject, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def resetPassword(receiver: V8Object, token: String, id: String, newPassword: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => userRegion ? ResetPassword(userId(id), user, newPassword)
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case _: PasswordReseted =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            params.pushNull()
            params.push(true)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }
}

object UserWrapper {
  def apply(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) = new UserWrapper(system, callbackQueue)
}
