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
import com.zxtx.actors.CollectionActor
import com.zxtx.actors.CollectionActor._
import com.zxtx.actors.CollectionActor.JsonProtocol._
import com.zxtx.actors.DomainActor
import com.zxtx.actors.DomainActor._
import com.zxtx.actors.DomainActor.JsonProtocol._
import com.zxtx.actors.FormActor
import com.zxtx.actors.FormActor._
import com.zxtx.actors.FormActor.JsonProtocol._
import com.zxtx.actors.ViewActor
import com.zxtx.actors.ViewActor._
import com.zxtx.actors.ViewActor.JsonProtocol._
import com.zxtx.actors.ProfileActor
import com.zxtx.actors.ProfileActor._
import com.zxtx.actors.ProfileActor.JsonProtocol._
import com.zxtx.actors.RoleActor
import com.zxtx.actors.RoleActor._
import com.zxtx.actors.RoleActor.JsonProtocol._
import com.zxtx.actors.UserActor
import com.zxtx.actors.UserActor._
import com.zxtx.actors.UserActor.JsonProtocol._
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

  def domainPID(domainId: String) = DomainActor.persistenceId(rootDomain, domainId)
  def userPID(userId: String) = UserActor.persistenceId(rootDomain, userId)

  val documentRegion: ActorRef = ClusterSharding(system).shardRegion(DocumentActor.shardName)
  val collectionRegion: ActorRef = ClusterSharding(system).shardRegion(CollectionActor.shardName)
  val domainRegion: ActorRef = ClusterSharding(system).shardRegion(DomainActor.shardName)
  val formRegion: ActorRef = ClusterSharding(system).shardRegion(FormActor.shardName)
  val viewRegion: ActorRef = ClusterSharding(system).shardRegion(ViewActor.shardName)
  val profileRegion: ActorRef = ClusterSharding(system).shardRegion(ProfileActor.shardName)
  val roleRegion: ActorRef = ClusterSharding(system).shardRegion(RoleActor.shardName)
  val userRegion: ActorRef = ClusterSharding(system).shardRegion(UserActor.shardName)

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
    commandWithDocument(collectionId, receiver, token, callback) { user => collectionId match {
      case ".domains" => domainRegion ? CreateDomain(domainPID(docId), user, jsRaw)
      case ".collections" => collectionRegion ? CreateCollection(CollectionActor.persistenceId(domainId, docId), user, jsRaw)
      case ".forms" => formRegion ? CreateForm(FormActor.persistenceId(domainId, docId), user, jsRaw)
      case ".views" => viewRegion ? CreateView(ViewActor.persistenceId(domainId, docId), user, jsRaw)
      case ".profiles" => profileRegion ? CreateProfile(ProfileActor.persistenceId(domainId, docId), user, jsRaw)
      case ".roles" => roleRegion ? CreateRole(RoleActor.persistenceId(domainId, docId), user, jsRaw)
      case ".users" => userRegion ? CreateUser(UserActor.persistenceId(domainId, docId), user, jsRaw)
      case _ => documentRegion ? CreateDocument(documentPID(domainId, collectionId, docId), user, jsRaw) 
    }}
  }

  def get(receiver: V8Object, token: String, domainId: String, collectionId: String, docId: String, callback: V8Function) =
    commandWithDocument(collectionId, receiver, token, callback) { user => collectionId match{
      case ".domains" => domainRegion ? GetDomain(domainPID(docId), user)
      case ".collections" => collectionRegion ? GetCollection(CollectionActor.persistenceId(domainId, docId), user, "/")
      case ".forms" => formRegion ? GetForm(FormActor.persistenceId(domainId, docId), user)
      case ".views" => viewRegion ? GetView(ViewActor.persistenceId(domainId, docId), user)
      case ".profiles" => profileRegion ? GetProfile(ProfileActor.persistenceId(domainId, docId), user)
      case ".roles" => roleRegion ? GetRole(RoleActor.persistenceId(domainId, docId), user)
      case ".users" => userRegion ? GetUser(UserActor.persistenceId(domainId, docId), user)
      case _ => documentRegion ? GetDocument(documentPID(domainId, collectionId, docId), user) 
    }}

  def replace(receiver: V8Object, token: String, domainId: String, collectionId: String, docId: String, content: V8Object, callback: V8Function) = {
    val jsContent = toJsObject(content)
    commandWithDocument(collectionId, receiver, token, callback) { user => collectionId match{
      case ".domains" => domainRegion ? ReplaceDomain(domainPID(docId), user, jsContent)
      case ".collections" => collectionRegion ? ReplaceCollection(CollectionActor.persistenceId(domainId, docId), user, jsContent)
      case ".forms" => formRegion ? ReplaceForm(FormActor.persistenceId(domainId, docId), user, jsContent)
      case ".views" => viewRegion ? ReplaceView(ViewActor.persistenceId(domainId, docId), user, jsContent)
      case ".profiles" => profileRegion ? ReplaceProfile(ProfileActor.persistenceId(domainId, docId), user, jsContent)
      case ".roles" => roleRegion ? ReplaceRole(RoleActor.persistenceId(domainId, docId), user, jsContent)
      case ".users" => userRegion ? ReplaceUser(UserActor.persistenceId(domainId, docId), user, jsContent)
      case _ => documentRegion ? ReplaceDocument(documentPID(domainId, collectionId, docId), user, jsContent)       
    }}
  }

  def patch(receiver: V8Object, token: String, domainId: String, collectionId: String, docId: String, v8Patch: V8Array, callback: V8Function) = {
    val jsPatch = toJsArray(v8Patch)
    commandWithDocument(collectionId, receiver, token, callback) { user => collectionId match{
      case ".domains" => domainRegion ? PatchDomain(domainPID(docId), user, JsonPatch(jsPatch))
      case ".collections" => collectionRegion ? PatchCollection(CollectionActor.persistenceId(domainId, docId), user, JsonPatch(jsPatch))
      case ".forms" => formRegion ? PatchForm(FormActor.persistenceId(domainId, docId), user, JsonPatch(jsPatch))
      case ".views" => viewRegion ? PatchView(ViewActor.persistenceId(domainId, docId), user, JsonPatch(jsPatch))
      case ".profiles" => profileRegion ? PatchProfile(ProfileActor.persistenceId(domainId, docId), user, JsonPatch(jsPatch))
      case ".roles" => roleRegion ? PatchRole(RoleActor.persistenceId(domainId, docId), user, JsonPatch(jsPatch))
      case ".users" => userRegion ? PatchUser(UserActor.persistenceId(domainId, docId), user, JsonPatch(jsPatch))
      case _ => documentRegion ? PatchDocument(documentPID(domainId, collectionId, docId), user, JsonPatch(jsPatch))       
    }}
  }

  def remove(receiver: V8Object, token: String, domainId: String, collectionId: String, docId: String, callback: V8Function) =
    commandWithSuccess(receiver, token, callback) { user => collectionId match{
      case ".domains" => domainRegion ? RemoveDomain(domainPID(docId), user)
      case ".collections" => collectionRegion ? RemoveCollection(CollectionActor.persistenceId(domainId, docId), user)
      case ".forms" => formRegion ? RemoveForm(FormActor.persistenceId(domainId, docId), user)
      case ".views" => viewRegion ? RemoveView(ViewActor.persistenceId(domainId, docId), user)
      case ".profiles" => profileRegion ? RemoveProfile(ProfileActor.persistenceId(domainId, docId), user)
      case ".roles" => roleRegion ? RemoveRole(RoleActor.persistenceId(domainId, docId), user)
      case ".users" => userRegion ? RemoveUser(UserActor.persistenceId(domainId, docId), user)
      case _ => documentRegion ? RemoveDocument(documentPID(domainId, collectionId, docId), user)      
    }}

  def getACL(receiver: V8Object, token: String, domainId: String, collectionId: String, docId: String, callback: V8Function) =
    commandWithACL(receiver, token, callback) { user => collectionId match{
      case ".domains" => domainRegion ? GetACL(domainPID(docId), user)
      case ".collections" => collectionRegion ? GetACL(CollectionActor.persistenceId(domainId, docId), user)
      case ".forms" => formRegion ? GetACL(FormActor.persistenceId(domainId, docId), user)
      case ".views" => viewRegion ? GetACL(ViewActor.persistenceId(domainId, docId), user)
      case ".profiles" => profileRegion ? GetACL(ProfileActor.persistenceId(domainId, docId), user)
      case ".roles" => roleRegion ? GetACL(RoleActor.persistenceId(domainId, docId), user)
      case ".users" => userRegion ? GetACL(UserActor.persistenceId(domainId, docId), user)
      case _ => documentRegion ? GetACL(documentPID(domainId, collectionId, docId), user)      
    }}

  def replaceACL(receiver: V8Object, token: String, domainId: String, collectionId: String, docId: String, v8ACL: V8Object, callback: V8Function) = {
    val jsACL = toJsObject(v8ACL)
    commandWithSuccess(receiver, token, callback) { user => collectionId match{
      case ".domains" => domainRegion ? ReplaceACL(domainPID(docId), user, jsACL)
      case ".collections" => collectionRegion ? ReplaceACL(CollectionActor.persistenceId(domainId, docId), user, jsACL)
      case ".forms" => formRegion ? ReplaceACL(FormActor.persistenceId(domainId, docId), user, jsACL)
      case ".views" => viewRegion ? ReplaceACL(ViewActor.persistenceId(domainId, docId), user, jsACL)
      case ".profiles" => profileRegion ? ReplaceACL(ProfileActor.persistenceId(domainId, docId), user, jsACL)
      case ".roles" => roleRegion ? ReplaceACL(RoleActor.persistenceId(domainId, docId), user, jsACL)
      case ".users" => userRegion ? ReplaceACL(UserActor.persistenceId(domainId, docId), user, jsACL)
      case _ => documentRegion ? ReplaceACL(documentPID(domainId, collectionId, docId), user, jsACL)      
    }}
  }

  def patchACL(receiver: V8Object, token: String, domainId: String, collectionId: String, docId: String, v8ACLPatch: V8Array, callback: V8Function) = {
    val jsACLPatch = toJsArray(v8ACLPatch)
    commandWithSuccess(receiver, token, callback) { user => collectionId match{
      case ".domains" => domainRegion ? PatchACL(domainPID(docId), user, JsonPatch(jsACLPatch))
      case ".collections" => collectionRegion ? PatchACL(CollectionActor.persistenceId(domainId, docId), user, JsonPatch(jsACLPatch))
      case ".forms" => formRegion ? PatchACL(FormActor.persistenceId(domainId, docId), user, JsonPatch(jsACLPatch))
      case ".views" => viewRegion ? PatchACL(ViewActor.persistenceId(domainId, docId), user, JsonPatch(jsACLPatch))
      case ".profiles" => profileRegion ? PatchACL(ProfileActor.persistenceId(domainId, docId), user, JsonPatch(jsACLPatch))
      case ".roles" => roleRegion ? PatchACL(RoleActor.persistenceId(domainId, docId), user, JsonPatch(jsACLPatch))
      case ".users" => userRegion ? PatchACL(UserActor.persistenceId(domainId, docId), user, JsonPatch(jsACLPatch))
      case _ => documentRegion ? PatchACL(documentPID(domainId, collectionId, docId), user, JsonPatch(jsACLPatch))      
    }}
  }

  private def commandWithDocument(collectionId:String, receiver: V8Object, token: String, callback: V8Function)(cmd: (String) => Future[Any]) = collectionId match {
      case ".domains" => command[Domain](receiver, token, callback)(cmd) { (cbw, t) => domainCallback(cbw, t)}
      case ".collections" => command[Collection](receiver, token, callback)(cmd) { (cbw, t) => collectionCallback(cbw, t)}
      case ".forms" => command[Form](receiver, token, callback)(cmd) { (cbw, t) => formCallback(cbw, t)}
      case ".views" => command[View](receiver, token, callback)(cmd) { (cbw, t) => viewCallback(cbw, t)}
      case ".profiles" => command[Profile](receiver, token, callback)(cmd) { (cbw, t) => profileCallback(cbw, t)}
      case ".roles" => command[Role](receiver, token, callback)(cmd) { (cbw, t) => roleCallback(cbw, t)}
      case ".users" => command[User](receiver, token, callback)(cmd) { (cbw, t) => userCallback(cbw, t)}
      case _ => command[Document](receiver, token, callback)(cmd) { (cbw, t) => documentCallback(cbw, t) }
  }

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
  
  private def userCallback(cbw: CallbackWrapper, u: User) = {
    cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
      def prepare(params: V8Array) = {
        val v8Object = toV8Object(u.toJson.asJsObject, cbw.runtime)
        toBeReleased += v8Object
        params.pushNull()
        params.push(v8Object)
      }
    })
    enqueueCallback(cbw)
  }
  
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

object DocumentWrapper {
  def apply(system: ActorSystem, callbackQueue: Queue[CallbackWrapper]) = new DocumentWrapper(system, callbackQueue)
}
