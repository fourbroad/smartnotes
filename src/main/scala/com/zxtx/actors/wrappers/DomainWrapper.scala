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
import com.zxtx.actors.DocumentActor._
import com.zxtx.actors.CollectionActor._
import com.zxtx.actors.DomainActor
import com.zxtx.actors.DomainActor._
import com.zxtx.actors.DomainActor.JsonProtocol._

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask

import gnieh.diffson.sprayJson.JsonPatch
import spray.json._

class DomainWrapper(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) extends Wrapper(system, callbackQueue) with V8SprayJson {
  import Wrapper._
  import CallbackWrapper._

  val domainRegion: ActorRef = ClusterSharding(system).shardRegion(DomainActor.shardName)
  val documentRegion: ActorRef = ClusterSharding(system).shardRegion(DocumentActor.shardName)

  def domainId(domainName: String) = s"${rootDomain}~.domains~${domainName}"

  def bind(receiver: V8Object) = {
    val runtime = receiver.getRuntime
    val dw = runtime.getObject("__DomainWrapper")
    val prototype = runtime.executeObjectScript("__DomainWrapper.prototype")

    prototype.registerJavaMethod(this, "join", "join", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "quit", "quit", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "create", "create", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "get", "get", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "replace", "replace", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "patch", "patch", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Array], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "remove", "remove", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "setACL", "setACL", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Array], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "garbageCollection", "garbageCollection", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "listCollections", "listCollections", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Function]), true)

    dw.setPrototype(prototype)
    prototype.release
    dw.release
  }

  def join(receiver: V8Object, token: String, domainName: String, userName: String, permission: V8Object, callback: V8Function) = {
    val jsObj = toJsObject(permission)
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => domainRegion ? JoinDomain(domainId(domainName), user, userName, Some(jsObj))
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case dj: DomainJoined =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(dj.raw, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def quit(receiver: V8Object, token: String, domainName: String, userName: String, callback: V8Function) = {
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

  def create(receiver: V8Object, token: String, domainName: String, v8Raw: V8Object, callback: V8Function) = {
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

  def get(receiver: V8Object, token: String, domainName: String, callback: V8Function) = {
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

  def replace(receiver: V8Object, token: String, domainName: String, content: V8Object, callback: V8Function) = {
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

  def patch(receiver: V8Object, token: String, domainName: String, v8Patch: V8Array, callback: V8Function) = {
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

  def remove(receiver: V8Object, token: String, domainName: String, callback: V8Function) = {
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

  def setACL(receiver: V8Object, token: String, domainName: String, auth: V8Array, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    val jsArray = toJsArray(auth)
    validateToken(token).flatMap {
      case TokenValid(user) => domainRegion ? SetACL(domainId(domainName), user, JsonPatch(jsArray))
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

  def garbageCollection(receiver: V8Object, token: String, domainName: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => domainRegion ? GarbageCollection(domainId(domainName), user)
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case GarbageCollectionCompleted =>
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

  def listCollections(receiver: V8Object, token: String, domainName: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => domainRegion ? ListCollections(domainId(domainName), user)
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case ja: JsObject =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Array = toV8Object(ja, cbw.runtime)
            toBeReleased += v8Array
            params.pushNull()
            params.push(v8Array)
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
