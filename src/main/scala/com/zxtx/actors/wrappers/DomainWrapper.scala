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
import com.zxtx.actors.Event

class DomainWrapper(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) extends Wrapper(system, callbackQueue) with V8SprayJson {
  import Wrapper._
  import CallbackWrapper._

  val domainRegion: ActorRef = ClusterSharding(system).shardRegion(DomainActor.shardName)

  def domainPID(domainId: String) = DomainActor.persistenceId(rootDomain, domainId)

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
    prototype.registerJavaMethod(this, "refresh", "refresh", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Function]), true)

    prototype.registerJavaMethod(this, "getACL", "getACL", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "replaceACL", "replaceACL", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "patchACL", "patchACL", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Array], classOf[V8Function]), true)

    prototype.registerJavaMethod(this, "findDomains", "findDomains", Array[Class[_]](classOf[V8Object], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "findCollections", "findCollections", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "findViews", "findViews", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "garbageCollection", "garbageCollection", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Function]), true)

    dw.setPrototype(prototype)
    prototype.release
    dw.release
  }

  def join(receiver: V8Object, token: String, domainId: String, userId: String, permission: V8Object, callback: V8Function) = {
    val jsObj = toJsObject(permission)
    commandWithSuccess(receiver, token, callback) { user => domainRegion ? JoinDomain(domainPID(domainId), user, userId, Some(jsObj)) }
  }

  def quit(receiver: V8Object, token: String, domainId: String, userId: String, callback: V8Function) =
    commandWithSuccess(receiver, token, callback) { user => domainRegion ? QuitDomain(domainPID(domainId), user, userId) }

  def create(receiver: V8Object, token: String, domainId: String, v8Raw: V8Object, callback: V8Function) = {
    val jsRaw = toJsObject(v8Raw)
    commandWithDomain(receiver, token, callback) { user => domainRegion ? CreateDomain(domainPID(domainId), user, jsRaw) }
  }

  def get(receiver: V8Object, token: String, domainId: String, callback: V8Function) =
    commandWithDomain(receiver, token, callback) { user => domainRegion ? GetDomain(domainPID(domainId), user) }
  
  def replace(receiver: V8Object, token: String, domainId: String, v8Raw: V8Object, callback: V8Function) = {
    val jsRaw = toJsObject(v8Raw)
    commandWithDomain(receiver, token, callback) { user => domainRegion ? ReplaceDomain(domainPID(domainId), user, jsRaw) }
  }

  def patch(receiver: V8Object, token: String, domainId: String, v8Patch: V8Array, callback: V8Function) = {
    val jsArray = toJsArray(v8Patch)
    commandWithDomain(receiver, token, callback) { user => domainRegion ? PatchDomain(domainPID(domainId), user, JsonPatch(jsArray)) }
  }

  def remove(receiver: V8Object, token: String, domainId: String, callback: V8Function) =
    commandWithSuccess(receiver, token, callback) { user => domainRegion ? RemoveDomain(domainPID(domainId), user) }

  def getACL(receiver: V8Object, token: String, domainId: String, callback: V8Function) =
    commandWithACL(receiver, token, callback) { user => domainRegion ? GetACL(domainPID(domainId), user) }

  def replaceACL(receiver: V8Object, token: String, domainId: String, v8ACL: V8Object, callback: V8Function) = {
    val jsACL = toJsObject(v8ACL)
    commandWithSuccess(receiver, token, callback) { user => domainRegion ? ReplaceACL(domainPID(domainId), user, jsACL) }
  }

  def patchACL(receiver: V8Object, token: String, domainId: String, v8ACLPatch: V8Array, callback: V8Function) = {
    val jsACLPatch = toJsArray(v8ACLPatch)
    commandWithSuccess(receiver, token, callback) { user => domainRegion ? PatchACL(domainPID(domainId), user, JsonPatch(jsACLPatch)) }
  }

  def refresh(receiver: V8Object, token: String, domainId: String, callback: V8Function) =
    commandWithSuccess(receiver, token, callback) { user => domainRegion ? RefreshDomain(domainPID(domainId), user) }
  
  def findDomains(receiver: V8Object, token: String, query: V8Object, callback: V8Function) ={
    val jsQuery = toJsObject(query)
    findDocuments(receiver, token, rootDomain, callback){(user, domainId) => domainRegion ? FindDomains(domainPID(domainId), user, jsQuery)}
  }

  def findCollections(receiver: V8Object, token: String, domainId: String, query: V8Object, callback: V8Function) ={
    val jsQuery = toJsObject(query)
    findDocuments(receiver, token, domainId, callback){(user, domainId) => domainRegion ? FindCollections(domainPID(domainId), user, jsQuery)}
  }

  def findViews(receiver: V8Object, token: String, domainId: String, query: V8Object, callback: V8Function) = {
    val jsQuery = toJsObject(query)
    findDocuments(receiver, token, domainId, callback){(user, domainId) => domainRegion ? FindViews(domainPID(domainId), user, jsQuery)}
  }

  def garbageCollection(receiver: V8Object, token: String, domainId: String, callback: V8Function) =
    commandWithSuccess(receiver, token, callback) { user => domainRegion ? GarbageCollection(domainPID(domainId), user) }

  private def findDocuments(receiver: V8Object, token: String, domainId: String, callback: V8Function)(cmd:(String, String)=>Future[Any])={
    val cbw = CallbackWrapper(receiver, callback)
    validateToken(token).map {
      case TokenValid(user) => user
      case other            => "anonymous"
    }.flatMap {user => cmd(user, domainId)}.recover{ case e => e }.foreach {
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

  private def commandWithDomain(receiver: V8Object, token: String, callback: V8Function)(cmd: (String) => Future[Any]) =
    command[Domain](receiver, token, callback)(cmd) { (cbw, d) => domainCallback(cbw, d) }

  private def domainCallback(cbw: CallbackWrapper, d: Domain) = {
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

object DomainWrapper {
  def apply(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) = new DomainWrapper(system, callbackQueue)
}
