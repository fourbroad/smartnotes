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
import com.zxtx.actors.DocumentActor.JsonProtocol._

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import gnieh.diffson.sprayJson.JsonPatch
import spray.json.enrichAny

class DocumentWrapper(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) extends Wrapper(system, callbackQueue) with V8SprayJson {
  import CallbackWrapper._
  import Wrapper._

  val documentRegion: ActorRef = ClusterSharding(system).shardRegion(DocumentActor.shardName)

  def documentId(domainName: String, collectionName: String, docId: String) = s"${domainName}~${collectionName}~${docId}"

  def bind(receiver: V8Object) = {
    val runtime = receiver.getRuntime
    val dw = runtime.getObject("__DocumentWrapper")
    val prototype = runtime.executeObjectScript("__DocumentWrapper.prototype")
    prototype.registerJavaMethod(this, "createDocument", "createDocument", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "getDocument", "getDocument", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "replaceDocument", "replaceDocument", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "patchDocument", "patchDocument", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Array], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "deleteDocument", "deleteDocument", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    dw.setPrototype(prototype)
    prototype.release
    dw.release
  }

  def createDocument(receiver: V8Object, token: String, domainName: String, collectionName: String, docId: String, v8Raw: V8Object, callback: V8Function) = {
    val jsRaw = toJsObject(v8Raw)
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => documentRegion ? CreateDocument(documentId(domainName, collectionName, docId), user, jsRaw)
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case dc: DocumentCreated =>
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

  def getDocument(receiver: V8Object, token: String, domainName: String, collectionName: String, docId: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => documentRegion ? GetDocument(documentId(domainName, collectionName, docId), user)
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case d: Document =>
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

  def replaceDocument(receiver: V8Object, token: String, domainName: String, collectionName: String, docId: String, content: V8Object, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    val jsContent = toJsObject(content)
    validateToken(token).flatMap {
      case TokenValid(user) => documentRegion ? ReplaceDocument(documentId(domainName, collectionName, docId), user, toJsObject(content))
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case dr: DocumentReplaced =>
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

  def patchDocument(receiver: V8Object, token: String, domainName: String, collectionName: String, docId: String, v8Patch: V8Array, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    val jsArray = toJsArray(v8Patch)
    validateToken(token).flatMap {
      case TokenValid(user) => documentRegion ? PatchDocument(documentId(domainName, collectionName, docId), user, JsonPatch(toJsArray(v8Patch)))
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case dp: DocumentPatched =>
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

  def deleteDocument(receiver: V8Object, token: String, domainName: String, collectionName: String, docId: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).flatMap {
      case TokenValid(user) => documentRegion ? DeleteDocument(documentId(domainName, collectionName, docId), user)
      case other            => Future.successful(other)
    }.recover { case e => e }.foreach {
      case d: Document =>
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
}

object DocumentWrapper {
  def apply(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) = new DocumentWrapper(system, callbackQueue)
}
