package com.zxtx.actors.wrappers

import scala.collection.mutable.Queue
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Function
import com.eclipsesource.v8.V8Object
import com.zxtx.actors.FormActor
import com.zxtx.actors.FormActor._
import com.zxtx.actors.FormActor.JsonProtocol._
import com.zxtx.actors.ACL._

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import gnieh.diffson.sprayJson.JsonPatch
import spray.json._

class FormWrapper(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) extends Wrapper(system, callbackQueue) with V8SprayJson {
  import CallbackWrapper._
  import Wrapper._

  val formRegion: ActorRef = ClusterSharding(system).shardRegion(FormActor.shardName)

  def formPID(domainId: String, formId: String) = s"${domainId}~.forms~${formId}"

  def bind(receiver: V8Object) = {
    val runtime = receiver.getRuntime
    val dw = runtime.getObject("__FormWrapper")
    val prototype = runtime.executeObjectScript("__FormWrapper.prototype")

    prototype.registerJavaMethod(this, "create", "create", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "get", "get", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "replace", "replace", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "patch", "patch", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Array], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "remove", "remove", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)

    prototype.registerJavaMethod(this, "getACL", "getACL", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "replaceACL", "replaceACL", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "patchACL", "patchACL", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Array], classOf[V8Function]), true)

    dw.setPrototype(prototype)
    prototype.release
    dw.release
  }

  def create(receiver: V8Object, token: String, domainId: String, formId: String, v8Raw: V8Object, callback: V8Function) = {
    val jsRaw = toJsObject(v8Raw)
    commandWithForm(receiver, token, callback) { user => formRegion ? CreateForm(formPID(domainId, formId), user, jsRaw) }
  }

  def get(receiver: V8Object, token: String, domainId: String, formId: String, callback: V8Function) =
    commandWithForm(receiver, token, callback) { user => formRegion ? GetForm(formPID(domainId, formId), user) }

  def replace(receiver: V8Object, token: String, domainId: String, formId: String, content: V8Object, callback: V8Function) = {
    val jsContent = toJsObject(content)
    commandWithForm(receiver, token, callback) { user => formRegion ? ReplaceForm(formPID(domainId, formId), user, jsContent) }
  }

  def patch(receiver: V8Object, token: String, domainId: String, formId: String, v8Patch: V8Array, callback: V8Function) = {
    val jsPatch = toJsArray(v8Patch)
    commandWithForm(receiver, token, callback) { user => formRegion ? PatchForm(formPID(domainId, formId), user, JsonPatch(jsPatch)) }
  }

  def remove(receiver: V8Object, token: String, domainId: String, formId: String, callback: V8Function) =
    commandWithSuccess(receiver, token, callback) { user => formRegion ? RemoveForm(formPID(domainId, formId), user) }

  def getACL(receiver: V8Object, token: String, domainId: String, formId: String, callback: V8Function) =
    commandWithACL(receiver, token, callback) { user => formRegion ? GetACL(formPID(domainId, formId), user) }

  def replaceACL(receiver: V8Object, token: String, domainId: String, formId: String, v8ACL: V8Object, callback: V8Function) = {
    val jsACL = toJsObject(v8ACL)
    commandWithSuccess(receiver, token, callback) { user => formRegion ? ReplaceACL(formPID(domainId, formId), user, jsACL) }
  }

  def patchACL(receiver: V8Object, token: String, domainId: String, formId: String, v8ACLPatch: V8Array, callback: V8Function) = {
    val jsACLPatch = toJsArray(v8ACLPatch)
    commandWithSuccess(receiver, token, callback) { user => formRegion ? PatchACL(formPID(domainId, formId), user, JsonPatch(jsACLPatch)) }
  }
  
  private def commandWithForm(receiver: V8Object, token: String, callback: V8Function)(cmd: (String) => Future[Any]) =
    command[Form](receiver, token, callback)(cmd) { (cbw, t) => formCallback(cbw, t) }

  private def formCallback(cbw: CallbackWrapper, d: Form) = {
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

object FormWrapper {
  def apply(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) = new FormWrapper(system, callbackQueue)
}