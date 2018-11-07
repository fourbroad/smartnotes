package com.zxtx.actors.wrappers

import scala.collection.mutable.Queue
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Function
import com.eclipsesource.v8.V8Object
import com.zxtx.actors.RoleActor
import com.zxtx.actors.RoleActor._
import com.zxtx.actors.RoleActor.JsonProtocol._
import com.zxtx.actors.ACL._

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import gnieh.diffson.sprayJson.JsonPatch
import spray.json._

class RoleWrapper(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) extends Wrapper(system, callbackQueue) with V8SprayJson {
  import CallbackWrapper._
  import Wrapper._

  val roleRegion: ActorRef = ClusterSharding(system).shardRegion(RoleActor.shardName)

  def rolePID(domainId: String, roleId: String) = s"${domainId}~.roles~${roleId}"

  def bind(receiver: V8Object) = {
    val runtime = receiver.getRuntime
    val dw = runtime.getObject("__RoleWrapper")
    val prototype = runtime.executeObjectScript("__RoleWrapper.prototype")

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

  def create(receiver: V8Object, token: String, domainId: String, roleId: String, v8Raw: V8Object, callback: V8Function) = {
    val jsRaw = toJsObject(v8Raw)
    commandWithRole(receiver, token, callback) { user => roleRegion ? CreateRole(rolePID(domainId, roleId), user, jsRaw) }
  }

  def get(receiver: V8Object, token: String, domainId: String, roleId: String, callback: V8Function) =
    commandWithRole(receiver, token, callback) { user => roleRegion ? GetRole(rolePID(domainId, roleId), user) }

  def replace(receiver: V8Object, token: String, domainId: String, roleId: String, content: V8Object, callback: V8Function) = {
    val jsContent = toJsObject(content)
    commandWithRole(receiver, token, callback) { user => roleRegion ? ReplaceRole(rolePID(domainId, roleId), user, jsContent) }
  }

  def patch(receiver: V8Object, token: String, domainId: String, roleId: String, v8Patch: V8Array, callback: V8Function) = {
    val jsPatch = toJsArray(v8Patch)
    commandWithRole(receiver, token, callback) { user => roleRegion ? PatchRole(rolePID(domainId, roleId), user, JsonPatch(jsPatch)) }
  }

  def remove(receiver: V8Object, token: String, domainId: String, roleId: String, callback: V8Function) =
    commandWithSuccess(receiver, token, callback) { user => roleRegion ? RemoveRole(rolePID(domainId, roleId), user) }

  def getACL(receiver: V8Object, token: String, domainId: String, roleId: String, callback: V8Function) =
    commandWithACL(receiver, token, callback) { user => roleRegion ? GetACL(rolePID(domainId, roleId), user) }

  def replaceACL(receiver: V8Object, token: String, domainId: String, roleId: String, v8ACL: V8Object, callback: V8Function) = {
    val jsACL = toJsObject(v8ACL)
    commandWithSuccess(receiver, token, callback) { user => roleRegion ? ReplaceACL(rolePID(domainId, roleId), user, jsACL) }
  }

  def patchACL(receiver: V8Object, token: String, domainId: String, roleId: String, v8ACLPatch: V8Array, callback: V8Function) = {
    val jsACLPatch = toJsArray(v8ACLPatch)
    commandWithSuccess(receiver, token, callback) { user => roleRegion ? PatchACL(rolePID(domainId, roleId), user, JsonPatch(jsACLPatch)) }
  }
  
  private def commandWithRole(receiver: V8Object, token: String, callback: V8Function)(cmd: (String) => Future[Any]) =
    command[Role](receiver, token, callback)(cmd) { (cbw, t) => roleCallback(cbw, t) }

  private def roleCallback(cbw: CallbackWrapper, d: Role) = {
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

object RoleWrapper {
  def apply(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) = new RoleWrapper(system, callbackQueue)
}