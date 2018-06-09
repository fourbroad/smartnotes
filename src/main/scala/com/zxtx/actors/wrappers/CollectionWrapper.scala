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

  def collectionPID(domainId: String, collectionId: String) = s"${domainId}~.collections~${collectionId}"

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

    prototype.registerJavaMethod(this, "getACL", "getACL", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "replaceACL", "replaceACL", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "patchACL", "patchACL", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Array], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "removePermissionSubject", "removePermissionSubject", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "removeEventPermissionSubject", "removeEventPermissionSubject", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)

    dw.setPrototype(prototype)
    prototype.release
    dw.release
  }

  def create(receiver: V8Object, token: String, domainId: String, collectionId: String, v8Raw: V8Object, callback: V8Function) = {
    val jsRaw = toJsObject(v8Raw)
    commandWithCollection(receiver, token, callback) { user => collectionRegion ? CreateCollection(collectionPID(domainId, collectionId), user, jsRaw) }
  }

  def get(receiver: V8Object, token: String, domainId: String, collectionId: String, callback: V8Function) =
    commandWithCollection(receiver, token, callback) { user => collectionRegion ? GetCollection(collectionPID(domainId, collectionId), user, "/") }

  def replace(receiver: V8Object, token: String, domainId: String, collectionId: String, content: V8Object, callback: V8Function) = {
    val jsContent = toJsObject(content)
    commandWithCollection(receiver, token, callback) { user => collectionRegion ? ReplaceCollection(collectionPID(domainId, collectionId), user, jsContent) }
  }

  def patch(receiver: V8Object, token: String, domainId: String, collectionId: String, v8Patch: V8Array, callback: V8Function) = {
    val jsArray = toJsArray(v8Patch)
    commandWithCollection(receiver, token, callback) { user => collectionRegion ? PatchCollection(collectionPID(domainId, collectionId), user, JsonPatch(jsArray)) }
  }

  def remove(receiver: V8Object, token: String, domainId: String, collectionId: String, callback: V8Function) =
    commandWithSuccess(receiver, token, callback) { user => collectionRegion ? RemoveCollection(collectionPID(domainId, collectionId), user) }

  def getACL(receiver: V8Object, token: String, domainId: String, collectionId: String, callback: V8Function) =
    commandWithACL(receiver, token, callback) { user => collectionRegion ? GetACL(collectionPID(domainId, collectionId), user) }

  def replaceACL(receiver: V8Object, token: String, domainId: String, collectionId: String, v8ACL: V8Object, callback: V8Function) = {
    val jsACL = toJsObject(v8ACL)
    commandWithSuccess(receiver, token, callback) { user => collectionRegion ? ReplaceACL(collectionPID(domainId, collectionId), user, jsACL) }
  }

  def patchACL(receiver: V8Object, token: String, domainId: String, collectionId: String, v8ACLPatch: V8Array, callback: V8Function) = {
    val jsACLPatch = toJsArray(v8ACLPatch)
    commandWithSuccess(receiver, token, callback) { user => collectionRegion ? PatchACL(collectionPID(domainId, collectionId), user, JsonPatch(jsACLPatch)) }
  }

  def removePermissionSubject(receiver: V8Object, token: String, domainId: String, collectionId: String, operation: String, kind: String, subject: String, callback: V8Function) =
    commandWithSuccess(receiver, token, callback) { user => collectionRegion ? RemovePermissionSubject(collectionPID(domainId, collectionId), user, operation, kind, subject) }

  def removeEventPermissionSubject(receiver: V8Object, token: String, domainId: String, collectionId: String, operation: String, kind: String, subject: String, callback: V8Function) =
    commandWithSuccess(receiver, token, callback) { user => collectionRegion ? RemoveEventPermissionSubject(collectionPID(domainId, collectionId), user, operation, kind, subject) }

  def findDocuments(receiver: V8Object, token: String, domainId: String, collectionId: String, query: V8Object, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    val jsQuery = toJsObject(query)
    validateToken(token).flatMap {
      case TokenValid(user) => collectionRegion ? FindDocuments(collectionPID(domainId, collectionId), user, jsQuery)
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

  private def commandWithCollection(receiver: V8Object, token: String, callback: V8Function)(cmd: (String) => Future[Any]) =
    command[Collection](receiver, token, callback)(cmd) { (cbw, c) => collectionCallback(cbw, c) }

  private def collectionCallback(cbw: CallbackWrapper, c: Collection) = {
    cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
      def prepare(params: V8Array) = {
        val v8Object = toV8Object(c.toJson.asJsObject, cbw.runtime)
        toBeReleased += v8Object
        params.pushNull()
        params.push(v8Object)
      }
    })
    enqueueCallback(cbw)
  }

}

object CollectionWrapper {
  def apply(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) = new CollectionWrapper(system, callbackQueue)
}
