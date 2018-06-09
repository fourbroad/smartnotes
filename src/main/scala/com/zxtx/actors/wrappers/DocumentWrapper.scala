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
import com.zxtx.actors.ACL._

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

  def documentPID(domainId: String, collectionId: String, docId: String) = s"${domainId}~${collectionId}~${docId}"

  def bind(receiver: V8Object) = {
    val runtime = receiver.getRuntime
    val dw = runtime.getObject("__DocumentWrapper")
    val prototype = runtime.executeObjectScript("__DocumentWrapper.prototype")

    prototype.registerJavaMethod(this, "create", "create", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "get", "get", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "replace", "replace", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "patch", "patch", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Array], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "remove", "remove", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)

    prototype.registerJavaMethod(this, "getACL", "getACL", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "replaceACL", "replaceACL", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "patchACL", "patchACL", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Array], classOf[V8Function]), true)

    dw.setPrototype(prototype)
    prototype.release
    dw.release
  }

  def create(receiver: V8Object, token: String, domainId: String, collectionId: String, docId: String, v8Raw: V8Object, callback: V8Function) = {
    val jsRaw = toJsObject(v8Raw)
    commandWithDocument(receiver, token, callback) { user => documentRegion ? CreateDocument(documentPID(domainId, collectionId, docId), user, jsRaw) }
  }

  def get(receiver: V8Object, token: String, domainId: String, collectionId: String, docId: String, callback: V8Function) =
    commandWithDocument(receiver, token, callback) { user => documentRegion ? GetDocument(documentPID(domainId, collectionId, docId), user) }

  def replace(receiver: V8Object, token: String, domainId: String, collectionId: String, docId: String, content: V8Object, callback: V8Function) = {
    val jsContent = toJsObject(content)
    commandWithDocument(receiver, token, callback) { user => documentRegion ? ReplaceDocument(documentPID(domainId, collectionId, docId), user, jsContent) }
  }

  def patch(receiver: V8Object, token: String, domainId: String, collectionId: String, docId: String, v8Patch: V8Array, callback: V8Function) = {
    val jsPatch = toJsArray(v8Patch)
    commandWithDocument(receiver, token, callback) { user => documentRegion ? PatchDocument(documentPID(domainId, collectionId, docId), user, JsonPatch(jsPatch)) }
  }

  def remove(receiver: V8Object, token: String, domainId: String, collectionId: String, docId: String, callback: V8Function) =
    commandWithSuccess(receiver, token, callback) { user => documentRegion ? RemoveDocument(documentPID(domainId, collectionId, docId), user) }

  def getACL(receiver: V8Object, token: String, domainId: String, collectionId: String, docId: String, callback: V8Function) =
    commandWithACL(receiver, token, callback) { user => documentRegion ? GetACL(documentPID(domainId, collectionId, docId), user) }

  def replaceACL(receiver: V8Object, token: String, domainId: String, collectionId: String, docId: String, v8ACL: V8Object, callback: V8Function) = {
    val jsACL = toJsObject(v8ACL)
    commandWithSuccess(receiver, token, callback) { user => documentRegion ? ReplaceACL(documentPID(domainId, collectionId, docId), user, jsACL) }
  }

  def patchACL(receiver: V8Object, token: String, domainId: String, collectionId: String, docId: String, v8ACLPatch: V8Array, callback: V8Function) = {
    val jsACLPatch = toJsArray(v8ACLPatch)
    commandWithSuccess(receiver, token, callback) { user => documentRegion ? PatchACL(documentPID(domainId, collectionId, docId), user, JsonPatch(jsACLPatch)) }
  }

  private def commandWithDocument(receiver: V8Object, token: String, callback: V8Function)(cmd: (String) => Future[Any]) =
    command[Document](receiver, token, callback)(cmd) { (cbw, t) => documentCallback(cbw, t) }

  private def documentCallback(cbw: CallbackWrapper, d: Document) = {
    cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
      def prepare(params: V8Array) = {
        val v8Object = toV8Object(d.toJson.asJsObject, cbw.runtime)
        toBeReleased += v8Object
        params.pushNull()
        params.push(v8Object)
      }
    })
    enqueueCallback(cbw)
  }
}

object DocumentWrapper {
  def apply(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) = new DocumentWrapper(system, callbackQueue)
}
