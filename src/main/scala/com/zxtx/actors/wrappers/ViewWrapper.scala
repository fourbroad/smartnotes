package com.zxtx.actors.wrappers

import scala.collection.mutable.Queue
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Function
import com.eclipsesource.v8.V8Object
import com.zxtx.actors.ViewActor
import com.zxtx.actors.ViewActor._
import com.zxtx.actors.ViewActor.JsonProtocol._
import com.zxtx.actors.ACL._

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import gnieh.diffson.sprayJson.JsonPatch
import spray.json._

class ViewWrapper(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) extends Wrapper(system, callbackQueue) with V8SprayJson {
  import CallbackWrapper._
  import Wrapper._

  val viewRegion: ActorRef = ClusterSharding(system).shardRegion(ViewActor.shardName)

  def bind(receiver: V8Object) = {
    val runtime = receiver.getRuntime
    val dw = runtime.getObject("__ViewWrapper")
    val prototype = runtime.executeObjectScript("__ViewWrapper.prototype")

    prototype.registerJavaMethod(this, "create", "create", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "get", "get", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "replace", "replace", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "patch", "patch", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Array], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "remove", "remove", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "findDocuments", "findDocuments", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "refresh", "refresh", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)

    prototype.registerJavaMethod(this, "getACL", "getACL", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "replaceACL", "replaceACL", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "patchACL", "patchACL", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Array], classOf[V8Function]), true)

    dw.setPrototype(prototype)
    prototype.release
    dw.release
  }

  def create(receiver: V8Object, token: String, domainId: String, viewId: String, v8Raw: V8Object, callback: V8Function) = {
    val jsRaw = toJsObject(v8Raw)
    commandWithView(receiver, token, callback) { user => viewRegion ? CreateView(persistenceId(domainId, viewId), user, jsRaw) }
  }

  def get(receiver: V8Object, token: String, domainId: String, viewId: String, callback: V8Function) =
    commandWithView(receiver, token, callback) { user => viewRegion ? GetView(persistenceId(domainId, viewId), user) }

  def replace(receiver: V8Object, token: String, domainId: String, viewId: String, content: V8Object, callback: V8Function) = {
    val jsContent = toJsObject(content)
    commandWithView(receiver, token, callback) { user => viewRegion ? ReplaceView(persistenceId(domainId, viewId), user, jsContent) }
  }

  def patch(receiver: V8Object, token: String, domainId: String, viewId: String, v8Patch: V8Array, callback: V8Function) = {
    val jsPatch = toJsArray(v8Patch)
    commandWithView(receiver, token, callback) { user => viewRegion ? PatchView(persistenceId(domainId, viewId), user, JsonPatch(jsPatch)) }
  }

  def remove(receiver: V8Object, token: String, domainId: String, viewId: String, callback: V8Function) =
    commandWithSuccess(receiver, token, callback) { user => viewRegion ? RemoveView(persistenceId(domainId, viewId), user) }

  def getACL(receiver: V8Object, token: String, domainId: String, viewId: String, callback: V8Function) =
    commandWithACL(receiver, token, callback) { user => viewRegion ? GetACL(persistenceId(domainId, viewId), user) }

  def replaceACL(receiver: V8Object, token: String, domainId: String, viewId: String, v8ACL: V8Object, callback: V8Function) = {
    val jsACL = toJsObject(v8ACL)
    commandWithSuccess(receiver, token, callback) { user => viewRegion ? ReplaceACL(persistenceId(domainId, viewId), user, jsACL) }
  }

  def patchACL(receiver: V8Object, token: String, domainId: String, viewId: String, v8ACLPatch: V8Array, callback: V8Function) = {
    val jsACLPatch = toJsArray(v8ACLPatch)
    commandWithSuccess(receiver, token, callback) { user => viewRegion ? PatchACL(persistenceId(domainId, viewId), user, JsonPatch(jsACLPatch)) }
  }
  
  def findDocuments(receiver: V8Object, token: String, domainId: String, viewId: String, query: V8Object, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    val jsQuery = toJsObject(query)
    validateToken(token).map {
      case TokenValid(user) => user
      case other            => "anonymous"
    }.flatMap { user => viewRegion ? FindDocuments(persistenceId(domainId, viewId), user, jsQuery) }.recover { case e => e }.foreach {
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
  
  def refresh(receiver: V8Object, token: String, domainId: String, viewId: String, callback: V8Function) =
    commandWithSuccess(receiver, token, callback) { user => viewRegion ? Refresh(persistenceId(domainId, viewId), user) }

  private def commandWithView(receiver: V8Object, token: String, callback: V8Function)(cmd: (String) => Future[Any]) =
    command[View](receiver, token, callback)(cmd) { (cbw, t) => viewCallback(cbw, t) }

  private def viewCallback(cbw: CallbackWrapper, d: View) = {
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

object ViewWrapper {
  def apply(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) = new ViewWrapper(system, callbackQueue)
}