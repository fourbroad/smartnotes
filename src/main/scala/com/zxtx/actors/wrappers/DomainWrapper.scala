package com.zxtx.actors.wrappers

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Function
import com.eclipsesource.v8.V8Object

import com.zxtx.actors.DocumentActor
import com.zxtx.actors.DomainActor
import com.zxtx.actors.DomainActor._

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterSharding
import akka.util.Timeout
import akka.pattern.ask
import akka.pattern.pipe

object DomainWrapper {
  var nodeJsActor: ActorRef = _
  var domainRegion: ActorRef = _
  var documentRegion: ActorRef = _
  var rootDomain: String = _
  implicit var ec: ExecutionContext = _

  implicit val duration = 5.seconds
  implicit val timeOut = Timeout(duration)

  def init(system: ActorSystem, nja: ActorRef) = {
    nodeJsActor = nja
    domainRegion = ClusterSharding(system).shardRegion(DomainActor.shardName)
    documentRegion = ClusterSharding(system).shardRegion(DocumentActor.shardName)
    rootDomain = system.settings.config.getString("domain.root-domain")
    ec = system.dispatcher
  }

  def bind(receiver: V8Object, domainName: String) = {
    receiver.add("name", domainName)
    val runtime = receiver.getRuntime
    val dw = new DomainWrapper(nodeJsActor, domainName)
    val domain = runtime.getObject("Domain")
    val prototype = runtime.executeObjectScript("Domain.prototype")
    prototype.registerJavaMethod(dw, "login", "login", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String], classOf[V8Function]), true)
    prototype.registerJavaMethod(dw, "logout", "logout", Array[Class[_]]())
    domain.setPrototype(prototype)
    prototype.release
    domain.release
  }
}

case class DomainWrapper(nodeJsActor: ActorRef, domainName: String) {
  import DomainWrapper._

  def login(receiver: V8Object, userName: String, password: String, callback: V8Function) =
    (domainRegion ? LoginDomain(s"${rootDomain}~.domains~${domainName}", userName, password)).map {
      case UserLoggedIn(id, author, revision, created, raw) => () => {
        val runtime = receiver.getRuntime
        val params = new V8Array(runtime)
        params.pushNull()
        val v8Object = new V8Object(runtime)
//        v8Object.add("", value)
        callback.call(receiver, params)
        v8Object.release
        params.release
      }
      case UserNotExists => () => {
        val params = new V8Array(receiver.getRuntime)
        callback.call(receiver, params)
        params.release
      }
    }.foreach { f =>
      
    }

  def logout = {
    System.out.println("logout Domain.")
  }
}