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
import com.zxtx.actors.DocumentActor
import com.zxtx.actors.DocumentActor._
import com.zxtx.actors.DocumentActor.JsonProtocol._

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import akka.util.Timeout
import posix.Signal
import spray.json._

import gnieh.diffson.sprayJson.JsonPatch

object DocumentWrapper extends V8SprayJson {
  import CallbackWrapper._

  var documentRegion: ActorRef = _
  var rootDomain: String = _
  var callbackQueue: Queue[CallbackWrapper] = _
  implicit var ec: ExecutionContext = _

  implicit val duration = 5.seconds
  implicit val timeOut = Timeout(duration)

  import scala.sys.process._
  val processId = Seq("sh", "-c", "echo $PPID").!!.trim.toInt
  val sigal = Signal.SIGWINCH

  def init(system: ActorSystem, cq: Queue[CallbackWrapper]) = {
    documentRegion = ClusterSharding(system).shardRegion(DocumentActor.shardName)
    rootDomain = system.settings.config.getString("domain.root-domain")
    callbackQueue = cq
    ec = system.dispatcher
  }

  def documentId(domainName: String, collectionName: String, docId: String) = s"${domainName}~${collectionName}~${docId}"

  def bind(receiver: V8Object, domainName: String, token: String) = {
    val runtime = receiver.getRuntime
    val dw = runtime.getObject("__DocumentWrapper__")
    val prototype = runtime.executeObjectScript("__DocumentWrapper__.prototype")
    prototype.registerJavaMethod(this, "createDocument", "createDocument", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "getDocument", "getDocument", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "replaceDocument", "replaceDocument", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String],classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "patchDocument", "patchDocument", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String],classOf[V8Array], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "deleteDocument", "deleteDocument", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    dw.setPrototype(prototype)
    prototype.release
    dw.release
  }

  def createDocument(receiver: V8Object, domainName: String, collectionName: String, docId: String, token: String, v8Raw: V8Object, callback: V8Function) = {
    val jsRaw = toJsObject(v8Raw)
    val cbw = CallbackWrapper(receiver, callback)
    (documentRegion ? CreateDocument(documentId(domainName, collectionName, docId), token, jsRaw)).onComplete {
      case Success(cc: DocumentCreated) =>
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
              case DocumentAlreadyExists =>
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

  def getDocument(receiver: V8Object, domainName: String, collectionName: String, docId: String, token: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (documentRegion ? GetDocument(documentId(domainName, collectionName, docId), token)).onComplete {
      case Success(d: Document) =>

      case Failure(e)           => failureCallback(cbw, e)
    }
  }

  def replaceDocument(receiver: V8Object, domainName: String, collectionName: String, docId: String, token: String, content: V8Object, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (documentRegion ? ReplaceDocument(documentId(domainName, collectionName, docId), token, toJsObject(content))).onComplete {
      case Success(d: Document) =>
      case Failure(e)           => failureCallback(cbw, e)
    }
  }

  def patchDocument(receiver: V8Object, domainName: String, collectionName: String, docId: String, token: String, v8Patch: V8Array, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (documentRegion ? PatchDocument(documentId(domainName, collectionName, docId), token, JsonPatch(toJsArray(v8Patch)))).onComplete {
      case Success(d: Document) =>
      case Failure(e)           => failureCallback(cbw, e)
    }
  }

  def deleteDocument(receiver: V8Object, domainName: String, collectionName: String, docId: String, token: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (documentRegion ? DeleteDocument(documentId(domainName, collectionName, docId), token)).onComplete {
      case Success(d: Document) =>
      case Failure(e)           => failureCallback(cbw, e)
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
