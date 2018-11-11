package com.zxtx.actors.wrappers

import scala.collection.mutable.Queue
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Function
import com.eclipsesource.v8.V8Object
import com.zxtx.actors.ProfileActor
import com.zxtx.actors.ProfileActor._
import com.zxtx.actors.ProfileActor.JsonProtocol._
import com.zxtx.actors.ACL._

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import gnieh.diffson.sprayJson.JsonPatch
import spray.json._

class ProfileWrapper(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) extends Wrapper(system, callbackQueue) with V8SprayJson {
  import CallbackWrapper._
  import Wrapper._

  val profileRegion: ActorRef = ClusterSharding(system).shardRegion(ProfileActor.shardName)

  def bind(receiver: V8Object) = {
    val runtime = receiver.getRuntime
    val dw = runtime.getObject("__ProfileWrapper")
    val prototype = runtime.executeObjectScript("__ProfileWrapper.prototype")

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

  def create(receiver: V8Object, token: String, domainId: String, profileId: String, v8Raw: V8Object, callback: V8Function) = {
    val jsRaw = toJsObject(v8Raw)
    commandWithProfile(receiver, token, callback) { user => profileRegion ? CreateProfile(persistenceId(domainId, profileId), user, jsRaw) }
  }

  def get(receiver: V8Object, token: String, domainId: String, profileId: String, callback: V8Function) =
    commandWithProfile(receiver, token, callback) { user => profileRegion ? GetProfile(persistenceId(domainId, profileId), user) }

  def replace(receiver: V8Object, token: String, domainId: String, profileId: String, content: V8Object, callback: V8Function) = {
    val jsContent = toJsObject(content)
    commandWithProfile(receiver, token, callback) { user => profileRegion ? ReplaceProfile(persistenceId(domainId, profileId), user, jsContent) }
  }

  def patch(receiver: V8Object, token: String, domainId: String, profileId: String, v8Patch: V8Array, callback: V8Function) = {
    val jsPatch = toJsArray(v8Patch)
    commandWithProfile(receiver, token, callback) { user => profileRegion ? PatchProfile(persistenceId(domainId, profileId), user, JsonPatch(jsPatch)) }
  }

  def remove(receiver: V8Object, token: String, domainId: String, profileId: String, callback: V8Function) =
    commandWithSuccess(receiver, token, callback) { user => profileRegion ? RemoveProfile(persistenceId(domainId, profileId), user) }

  def getACL(receiver: V8Object, token: String, domainId: String, profileId: String, callback: V8Function) =
    commandWithACL(receiver, token, callback) { user => profileRegion ? GetACL(persistenceId(domainId, profileId), user) }

  def replaceACL(receiver: V8Object, token: String, domainId: String, profileId: String, v8ACL: V8Object, callback: V8Function) = {
    val jsACL = toJsObject(v8ACL)
    commandWithSuccess(receiver, token, callback) { user => profileRegion ? ReplaceACL(persistenceId(domainId, profileId), user, jsACL) }
  }

  def patchACL(receiver: V8Object, token: String, domainId: String, profileId: String, v8ACLPatch: V8Array, callback: V8Function) = {
    val jsACLPatch = toJsArray(v8ACLPatch)
    commandWithSuccess(receiver, token, callback) { user => profileRegion ? PatchACL(persistenceId(domainId, profileId), user, JsonPatch(jsACLPatch)) }
  }
  
  private def commandWithProfile(receiver: V8Object, token: String, callback: V8Function)(cmd: (String) => Future[Any]) =
    command[Profile](receiver, token, callback)(cmd) { (cbw, t) => profileCallback(cbw, t) }

  private def profileCallback(cbw: CallbackWrapper, d: Profile) = {
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

object ProfileWrapper {
  def apply(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) = new ProfileWrapper(system, callbackQueue)
}