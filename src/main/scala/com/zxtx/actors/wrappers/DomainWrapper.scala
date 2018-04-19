package com.zxtx.actors.wrappers

import scala.collection.mutable.Queue
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Function
import com.eclipsesource.v8.V8Object
import com.zxtx.actors.ACL._
import com.zxtx.actors.DocumentActor
import com.zxtx.actors.DomainActor
import com.zxtx.actors.DomainActor._
import com.zxtx.actors.DomainActor.JsonProtocol._

import com.roundeights.hasher.Implicits.stringToHasher

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask

import gnieh.diffson.sprayJson.JsonPatch
import spray.json._
import pdi.jwt._

class DomainWrapper(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) extends Wrapper(system, callbackQueue) with V8SprayJson {
  import Wrapper._
  import CallbackWrapper._
  import DocumentActor._

  val domainRegion: ActorRef = ClusterSharding(system).shardRegion(DomainActor.shardName)
  val documentRegion: ActorRef = ClusterSharding(system).shardRegion(DocumentActor.shardName)

  def domainId(domainName: String) = s"${rootDomain}~.domains~${domainName}"

  def bind(receiver: V8Object) = {
    val runtime = receiver.getRuntime
    val dw = runtime.getObject("__DomainWrapper")
    val prototype = runtime.executeObjectScript("__DomainWrapper.prototype")

    prototype.registerJavaMethod(this, "registerUser", "registerUser", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "login", "login", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "isValidToken", "isValidToken", Array[Class[_]](classOf[V8Object], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "logout", "logout", Array[Class[_]](classOf[V8Object], classOf[String], classOf[V8Function]), true)

    prototype.registerJavaMethod(this, "joinDomain", "joinDomain", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "quitDomain", "quitDomain", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "createDomain", "createDomain", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "getDomain", "getDomain", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "replaceDomain", "replaceDomain", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "patchDomain", "patchDomain", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Array], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "deleteDomain", "deleteDomain", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "authorizeDomain", "authorizeDomain", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Array], classOf[V8Function]), true)

    dw.setPrototype(prototype)
    prototype.release
    dw.release
  }

  def registerUser(receiver: V8Object, token: String, userName: String, password: String, userInfo: V8Object, callback: V8Function) = {
    val jsObj = toJsObject(userInfo)
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => domainRegion ? RegisterUser(domainId(rootDomain), user, userName, password, Some(jsObj))
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case ur: UserRegistered =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(ur.raw, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def joinDomain(receiver: V8Object, domainName: String, token: String, userName: String, permission: V8Object, callback: V8Function) = {
    val jsObj = toJsObject(permission)
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => domainRegion ? JoinDomain(domainId(domainName), user, userName, Some(jsObj))
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case _: DomainJoined =>
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

  def quitDomain(receiver: V8Object, domainName: String, token: String, userName: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => domainRegion ? QuitDomain(domainId(domainName), user, userName)
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case _: DomainQuited =>
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

  def login(receiver: V8Object, userName: String, password: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (documentRegion ? GetDocument(DocumentActor.persistenceId(rootDomain, "users", userName), userName)).flatMap {
      case doc: Document =>
        val hexMd5 = doc.raw.fields("password").asInstanceOf[JsString].value
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

  def createDomain(receiver: V8Object, domainName: String, token: String, v8Raw: V8Object, callback: V8Function) = {
    val jsRaw = toJsObject(v8Raw)
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => domainRegion ? CreateDomain(domainId(domainName), user, jsRaw)
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case dc: DomainCreated =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(dc.toJson.asJsObject, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def getDomain(receiver: V8Object, domainName: String, token: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => domainRegion ? GetDomain(domainId(domainName), user)
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case d: Domain =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(d.toJson.asJsObject, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def replaceDomain(receiver: V8Object, domainName: String, token: String, content: V8Object, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    val jsContent = toJsObject(content)
    validateToken(token).flatMap {
      case TokenValid(user) => domainRegion ? ReplaceDomain(domainId(domainName), user, jsContent)
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case dr: DomainReplaced =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(dr.toJson.asJsObject, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def patchDomain(receiver: V8Object, domainName: String, token: String, v8Patch: V8Array, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    val jsArray = toJsArray(v8Patch)
    validateToken(token).flatMap {
      case TokenValid(user) => domainRegion ? PatchDomain(domainId(domainName), user, JsonPatch(jsArray))
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case dp: DomainPatched =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(dp.toJson.asJsObject, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def deleteDomain(receiver: V8Object, domainName: String, token: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => domainRegion ? DeleteDomain(domainId(domainName), user)
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case d: Domain =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(d.toJson.asJsObject, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def authorizeDomain(receiver: V8Object, domainName: String, token: String, auth: V8Array, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    val jsArray = toJsArray(auth)
    validateToken(token).flatMap {
      case TokenValid(user) => domainRegion ? AuthorizeDomain(domainId(domainName), user, JsonPatch(jsArray))
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case d: Domain =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(d.toJson.asJsObject, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def collections(receiver: V8Object, domainName: String, token: String, callback: V8Function) = {

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
}

object DomainWrapper {
  def apply(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) = new DomainWrapper(system, callbackQueue)
}
