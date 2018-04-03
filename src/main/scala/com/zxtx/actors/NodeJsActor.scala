package com.zxtx.actors

import java.io.File

import scala.collection.mutable.Queue

import com.eclipsesource.v8.NodeJS
import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Function
import com.eclipsesource.v8.V8Object

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.Props
import posix.Signal

import com.zxtx.actors.DomainActor._
import com.zxtx.actors.DocumentSetActor._
import com.zxtx.actors.DocumentActor._
import akka.cluster.sharding.ClusterSharding
import spray.json.JsObject

import gnieh.diffson.sprayJson._
import gnieh.diffson.sprayJson.provider._

object NodeJsActor {
  def props(): Props = Props[NodeJsActor]

  import scala.sys.process._
  val processId = Seq("sh", "-c", "echo $PPID").!!.trim.toInt
  val sigal = Signal.SIGWINCH

}

class NodeJsActor extends Actor with ActorLogging {
  import NodeJsActor._

  val system = context.system
  val domainRegion = ClusterSharding(system).shardRegion(DomainActor.shardName)
  val documentSetRegion = ClusterSharding(system).shardRegion(DocumentSetActor.shardName)
  val documentRegion = ClusterSharding(system).shardRegion(DocumentActor.shardName)

  val messageQueue = Queue[Any]()

  def receive: Receive = {
    case cd @ CreateDomain => domainRegion ! cd
    case gd @ GetDomain => domainRegion ! gd
    case rd @ ReplaceDomain => domainRegion ! rd
    case pd @ PatchDomain => domainRegion ! pd
    case dd @ DeleteDomain => domainRegion ! dd
    case ad @ AuthorizeDomain => domainRegion ! ad
    case fds @ FindDocumentSets => domainRegion ! fds
    case ru @ RegisterUser => domainRegion ! ru
    case jd @ JoinDomain => domainRegion ! jd
    case lid @ LoginDomain => domainRegion ! lid
    case lod @ LogoutDomain => domainRegion ! lod

    case DomainCreated(id, author, revision, created, raw) =>
    case DomainReplaced(id, author, revision, created, raw) =>
    case DomainPatched(id, author, revision, created, patch, raw) =>
    case DomainDeleted(id, author, revision, created, raw) =>
    case DomainAuthorized(id, author, revision, created, patch, raw) =>
    case UserRegistered(id, author, revision, created, raw) =>
    case DomainJoined(id, author, revision, created, raw) =>
    case UserLoggedIn(id, author, revision, created, raw) =>
    case UserLoggedOut(id, author, revision, created, raw) =>

    case DomainNotFound =>
    case DomainAlreadyExists =>
    case DomainIsCreating =>
    case DomainSoftDeleted =>
    case UserNotExists =>
    case UserAlreadyRegistered =>
    case UserAlreadyJoined =>
    case UserNamePasswordError =>
    case PatchDomainException(exception: Throwable) =>
    case AuthorizeDomainException(exception: Throwable) =>

    case _ => sigal.kill(processId)
  }

}