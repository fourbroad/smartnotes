package com.zxtx.actors.wrappers

import scala.collection.mutable.Queue
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success

import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Function
import com.eclipsesource.v8.V8Object
import com.zxtx.actors.ACL._
import com.zxtx.actors.ACL.JsonProtocol._
import com.zxtx.actors.CollectionActor
import com.zxtx.actors.CollectionActor._
import com.zxtx.actors.CollectionActor.JsonProtocol._

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import akka.util.Timeout
import posix.Signal
import spray.json._

import gnieh.diffson.sprayJson.JsonPatch
import scala.concurrent.Future

class CollectionWrapper(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) extends Wrapper(system, callbackQueue) with V8SprayJson {
  import Wrapper._
  import CallbackWrapper._

  val collectionRegion: ActorRef = ClusterSharding(system).shardRegion(CollectionActor.shardName)

  def collectionId(domainName: String, collectionName: String) = s"${domainName}~.collections~${collectionName}"

  def bind(receiver: V8Object) = {
    val runtime = receiver.getRuntime
    val dw = runtime.getObject("__CollectionWrapper")
    val prototype = runtime.executeObjectScript("__CollectionWrapper.prototype")
    prototype.registerJavaMethod(this, "create", "create", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "get", "get", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "replace", "replace", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "patch", "patch", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Array], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "remove", "remove", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "findDocuments", "findDocuments", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "setACL", "setACL", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Array], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "removePermissionSubject", "removePermissionSubject", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "removeEventPermissionSubject", "removeEventPermissionSubject", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    dw.setPrototype(prototype)
    prototype.release
    dw.release
  }

  def create(receiver: V8Object, token: String, domainName: String, collectionName: String, v8Raw: V8Object, callback: V8Function) = {
    val jsRaw = toJsObject(v8Raw)
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => collectionRegion ? CreateCollection(collectionId(domainName, collectionName), user, jsRaw)
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case cc: CollectionCreated =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(cc.toJson.asJsObject, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def get(receiver: V8Object, token: String, domainName: String, collectionName: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => collectionRegion ? GetCollection(collectionId(domainName, collectionName), user, "/")
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case c: JsValue =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(c.asJsObject, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def replace(receiver: V8Object, token: String, domainName: String, collectionName: String, content: V8Object, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    val jsContent = toJsObject(content)
    validateToken(token).flatMap {
      case TokenValid(user) => collectionRegion ? ReplaceCollection(collectionId(domainName, collectionName), user, toJsObject(content))
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case cr: CollectionReplaced =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(cr.toJson.asJsObject, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def patch(receiver: V8Object, token: String, domainName: String, collectionName: String, v8Patch: V8Array, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    val jsArray = toJsArray(v8Patch)
    validateToken(token).flatMap {
      case TokenValid(user) => collectionRegion ? PatchCollection(collectionId(domainName, collectionName), user, JsonPatch(jsArray))
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case cp: CollectionPatched =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(cp.toJson.asJsObject, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def remove(receiver: V8Object, token: String, domainName: String, collectionName: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => collectionRegion ? DeleteCollection(collectionId(domainName, collectionName), user)
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case c: Collection =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(c.toJson.asJsObject, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def setACL(receiver: V8Object, token: String, domainName: String, collectionName: String, v8ACL: V8Array, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    val jsArray = toJsArray(v8ACL)
    validateToken(token).flatMap {
      case TokenValid(user) => collectionRegion ? SetACL(collectionId(domainName, collectionName), user, JsonPatch(jsArray))
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case as: ACLSet =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(as.toJson.asJsObject, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def removePermissionSubject(receiver: V8Object, token: String, domainName: String, collectionName: String, operation: String, kind: String, subject: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => collectionRegion ? RemovePermissionSubject(collectionId(domainName, collectionName), user, operation, kind, subject)
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case psr: PermissionSubjectRemoved =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(psr.toJson.asJsObject, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def removeEventPermissionSubject(receiver: V8Object, token: String, domainName: String, collectionName: String, operation: String, kind: String, subject: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => collectionRegion ? RemoveEventPermissionSubject(collectionId(domainName, collectionName), user, operation, kind, subject)
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case epsr: EventPermissionSubjectRemoved =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(epsr.toJson.asJsObject, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

  def findDocuments(receiver: V8Object, token: String, domainName: String, collectionName: String, query: V8Object, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    val jsQuery = toJsObject(query)
    validateToken(token).flatMap {
      case TokenValid(user) => collectionRegion ? FindDocuments(collectionId(domainName, collectionName), user, jsQuery)
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case jo: JsObject =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(jo, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case other => failureCallback(cbw, other)
    }
  }

}

object CollectionWrapper {
  def apply(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) = new CollectionWrapper(system, callbackQueue)
}
