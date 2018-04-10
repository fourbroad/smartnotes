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

object CollectionWrapper extends V8SprayJson {
  import CallbackWrapper._

  var collectionRegion: ActorRef = _
  var rootDomain: String = _
  var callbackQueue: Queue[CallbackWrapper] = _
  implicit var ec: ExecutionContext = _

  implicit val duration = 5.seconds
  implicit val timeOut = Timeout(duration)

  import scala.sys.process._
  val processId = Seq("sh", "-c", "echo $PPID").!!.trim.toInt
  val sigal = Signal.SIGWINCH

  def init(system: ActorSystem, cq: Queue[CallbackWrapper]) = {
    collectionRegion = ClusterSharding(system).shardRegion(CollectionActor.shardName)
    rootDomain = system.settings.config.getString("domain.root-domain")
    callbackQueue = cq
    ec = system.dispatcher
  }

  def collectionId(domainName: String, collectionName: String) = s"${domainName}~.collections~${collectionName}"

  def bind(receiver: V8Object, domainName: String, token: String) = {
    val runtime = receiver.getRuntime
    val dw = runtime.getObject("__CollectionWrapper__")
    val prototype = runtime.executeObjectScript("__CollectionWrapper__.prototype")
    prototype.registerJavaMethod(this, "createCollection", "createCollection", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "getCollection", "getCollection", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "replaceCollection", "replaceCollection", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String],classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "patchCollection", "patchCollection", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String],classOf[V8Array], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "deleteCollection", "deleteCollection", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    dw.setPrototype(prototype)
    prototype.release
    dw.release
  }

  def createCollection(receiver: V8Object, domainName: String, collectionName: String, token: String, v8Raw: V8Object, callback: V8Function) = {
    val jsRaw = toJsObject(v8Raw)
    val cbw = CallbackWrapper(receiver, callback)
    (collectionRegion ? CreateCollection(collectionId(domainName, collectionName), token, jsRaw)).onComplete {
      case Success(cc: CollectionCreated) =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(cc.toJson.asJsObject, cbw.runtime)
            toBeReleased += v8Object
            params.pushNull()
            params.push(v8Object)
          }
        })
        enqueueCallback(cbw)
      case Success(e) =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = new V8Object(runtime)
            e match {
              case TokenInvalid =>
                v8Object.add("code", 401)
                v8Object.add("message", "Token is invalid!")
              case Denied =>
                v8Object.add("code", 401)
                v8Object.add("message", "User have no permission to create domain!")
              case CollectionAlreadyExists =>
                v8Object.add("code", 409)
                v8Object.add("message", "Domain is already exists!")
              case e =>
                v8Object.add("code", 500)
                v8Object.add("message", "System error!")
            }
            toBeReleased += v8Object
            params.push(v8Object)
            params.pushNull()
          }
        })
        enqueueCallback(cbw)
      case Failure(e) => failureCallback(cbw, e)
    }
  }

  def getCollection(receiver: V8Object, domainName: String, collectionName: String, token: String, path:String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (collectionRegion ? GetCollection(collectionId(domainName, collectionName), token, path)).onComplete {
      case Success(d: Collection) =>

      case Failure(e)             => failureCallback(cbw, e)
    }
  }

  def replaceCollection(receiver: V8Object, domainName: String, collectionName: String, token: String, content: V8Object, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (collectionRegion ? ReplaceCollection(collectionId(domainName, collectionName), token, toJsObject(content))).onComplete {
      case Success(d: Collection) =>
      case Failure(e)             => failureCallback(cbw, e)
    }
  }

  def patchCollection(receiver: V8Object, domainName: String, collectionName: String, token: String, v8Patch: V8Array, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (collectionRegion ? PatchCollection(collectionId(domainName, collectionName), token, JsonPatch(toJsArray(v8Patch)))).onComplete {
      case Success(d: Collection) =>
      case Failure(e)             => failureCallback(cbw, e)
    }
  }

  def deleteCollection(receiver: V8Object, domainName: String, collectionName: String, token: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (collectionRegion ? DeleteCollection(collectionId(domainName, collectionName), token)).onComplete {
      case Success(d: Collection) =>
      case Failure(e)             => failureCallback(cbw, e)
    }
  }

  def enqueueCallback(cbw: CallbackWrapper) = {
    callbackQueue.synchronized { callbackQueue.enqueue(cbw) }
    sigal.kill(processId)
  }

  def failureCallback(cbw: CallbackWrapper, e: Throwable) = {
    cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
      def prepare(params: V8Array) = {
        val v8Object = new V8Object(runtime)
        v8Object.add("code", 500)
        v8Object.add("message", e.toString())
        toBeReleased += v8Object
        params.push(v8Object)
        params.pushNull()
      }
    })
    enqueueCallback(cbw)
  }
}
