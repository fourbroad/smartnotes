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
import com.zxtx.actors.DomainActor
import com.zxtx.actors.DomainActor._
import com.zxtx.actors.DomainActor.JsonProtocol._

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import akka.util.Timeout
import posix.Signal
import spray.json._

import gnieh.diffson.sprayJson.JsonPatch

object DomainWrapper extends V8SprayJson {
  import CallbackWrapper._

  var domainRegion: ActorRef = _
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
    domainRegion = ClusterSharding(system).shardRegion(DomainActor.shardName)
    documentRegion = ClusterSharding(system).shardRegion(DocumentActor.shardName)
    rootDomain = system.settings.config.getString("domain.root-domain")
    callbackQueue = cq
    ec = system.dispatcher
  }

  def domainId(domainName: String) = s"${rootDomain}~.domains~${domainName}"

  def bind(receiver: V8Object, domainName: String, token: String) = {
    val runtime = receiver.getRuntime
    val dw = runtime.getObject("__DomainWrapper__")
    val prototype = runtime.executeObjectScript("__DomainWrapper__.prototype")

    prototype.registerJavaMethod(this, "registerUser", "registerUser", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "login", "login", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "isValidToken", "isValidToken", Array[Class[_]](classOf[V8Object], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "logout", "logout", Array[Class[_]](classOf[V8Object], classOf[String], classOf[V8Function]), true)

    prototype.registerJavaMethod(this, "joinDomain", "joinDomain", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "createDomain", "createDomain", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "getDomain", "getDomain", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "replaceDomain", "replaceDomain", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Object], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "patchDomain", "patchDomain", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Array], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "deleteDomain", "deleteDomain", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(this, "authorizeDomain", "authorizeDomain", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Array], classOf[V8Function]), true)

    dw.setPrototype(prototype)
    prototype.release
    dw.release
  }

  def registerUser(receiver: V8Object, token: String, userName: String, password: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (domainRegion ? RegisterUser(domainId(rootDomain), token, userName, password)).onComplete {
      case Success(uli: UserLoggedIn) =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = new V8Object(runtime)
            v8Object.add("token", uli.token)
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
              case Denied =>
                v8Object.add("code", 401)
                v8Object.add("message", "User is not allowed to login!")
              case DomainNotExists =>
                v8Object.add("code", 404)
                v8Object.add("message", "Domain is not exists!")
              case UserNamePasswordError =>
                v8Object.add("code", 401)
                v8Object.add("message", "Username or password error!")
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

  def joinDomain(receiver: V8Object, domainName: String, token: String, userName: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (domainRegion ? JoinDomain(domainId(domainName), token, userName)).onComplete {
      case Success(dj: DomainJoined) =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            params.pushNull()
            params.push(true)
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
                v8Object.add("message", "User have no permission to join domain!")
              case DomainNotExists =>
                v8Object.add("code", 404)
                v8Object.add("message", "Domain is not exists!")
              case UserAlreadyJoined =>
                v8Object.add("code", 401)
                v8Object.add("message", "User is already joined!")
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

  def login(receiver: V8Object, userName: String, password: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (domainRegion ? LoginDomain(domainId(rootDomain), userName, password)).onComplete {
      case Success(uli: UserLoggedIn) =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = new V8Object(runtime)
            v8Object.add("token", uli.token)
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
                v8Object.add("message", "User is not allowed to login!")
              case DomainNotExists =>
                v8Object.add("code", 404)
                v8Object.add("message", "Domain is not exists!")
              case UserNamePasswordError =>
                v8Object.add("code", 401)
                v8Object.add("message", "Username or password error!")
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

  def isValidToken(receiver: V8Object, token: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (domainRegion ? ValidateToken(domainId(rootDomain), token)).onComplete {
      case Success(r) =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            params.pushNull()
            params.push(r match {
              case TokenValid   => true
              case TokenInvalid => false
            })
          }
        })
        enqueueCallback(cbw)
      case Failure(e) => failureCallback(cbw, e)
    }
  }

  def logout(receiver: V8Object, token: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (domainRegion ? LogoutDomain(domainId(rootDomain), token)).onComplete {
      case Success(r) =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            params.pushNull()
            params.push(r match {
              case _: UserLoggedOut => true
              case _                => false
            })
          }
        })
        enqueueCallback(cbw)
      case Failure(e) => failureCallback(cbw, e)
    }
  }

  def createDomain(receiver: V8Object, domainName: String, token: String, v8Raw: V8Object, callback: V8Function) = {
    val jsRaw = toJsObject(v8Raw)
    val cbw = CallbackWrapper(receiver, callback)
    (domainRegion ? CreateDomain(domainId(domainName), token, jsRaw)).onComplete {
      case Success(dc: DomainCreated) =>
        cbw.setParametersGenerator(new ParametersGenerator(cbw.runtime) {
          def prepare(params: V8Array) = {
            val v8Object = toV8Object(dc.toJson.asJsObject, cbw.runtime)
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
                v8Object.add("message", "It's no permission to create domain!")
              case DomainAlreadyExists =>
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

  def getDomain(receiver: V8Object, domainName: String, token: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (domainRegion ? GetDomain(domainId(domainName), token)).onComplete {
      case Success(d: Domain) =>

      case Failure(e)         => failureCallback(cbw, e)
    }
  }

  def replaceDomain(receiver: V8Object, domainName: String, token: String, content: V8Object, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (domainRegion ? ReplaceDomain(domainId(domainName), token, toJsObject(content))).onComplete {
      case Success(d: Domain) =>
      case Failure(e)         => failureCallback(cbw, e)
    }
  }

  def patchDomain(receiver: V8Object, domainName: String, token: String, v8Patch: V8Array, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (domainRegion ? PatchDomain(domainId(domainName), token, JsonPatch(toJsArray(v8Patch)))).onComplete {
      case Success(d: Domain) =>
      case Failure(e)         => failureCallback(cbw, e)
    }
  }

  def deleteDomain(receiver: V8Object, domainName: String, token: String, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (domainRegion ? DeleteDomain(domainId(domainName), token)).onComplete {
      case Success(d: Domain) =>
      case Failure(e)         => failureCallback(cbw, e)
    }
  }

  def authorizeDomain(receiver: V8Object, domainName: String, token: String, auth: V8Array, callback: V8Function) = {
    val cbw = CallbackWrapper(receiver, callback)
    (domainRegion ? AuthorizeDomain(domainId(domainName), token, JsonPatch(toJsArray(auth)))).onComplete {
      case Success(d: Domain) =>
      case Failure(e)         => failureCallback(cbw, e)
    }
  }

  def documentSets(receiver: V8Object, domainName: String, token: String, callback: V8Function) = {

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
