package com.zxtx.httpservice

import java.io.File

import scala.collection.mutable.Queue
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.blocking
import scala.concurrent.duration.DurationInt
import scala.io.StdIn

import com.eclipsesource.v8.JavaVoidCallback
import com.eclipsesource.v8.NodeJS
import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Object
import com.typesafe.config.ConfigFactory
import com.zxtx.actors.CollectionActor
import com.zxtx.actors.UserActor
import com.zxtx.actors.ViewActor
import com.zxtx.actors.FormActor
import com.zxtx.actors.RoleActor
import com.zxtx.actors.ProfileActor
import com.zxtx.actors.DocumentActor
import com.zxtx.actors.DomainActor
import com.zxtx.actors.DomainActor._
import com.zxtx.actors.wrappers._

import akka.Done
import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ClusterShardingSettings
import akka.event.LogSource
import akka.event.Logging
import akka.pattern.ask
import akka.util.Timeout
import posix.Signal
import spray.json.JsObject
import scala.concurrent.Await


object Main extends App {
  val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + 2551).withFallback(ConfigFactory.load())
  implicit val system = ActorSystem("SmartNotes", config)
  implicit val executionContext = system.dispatcher

  implicit val logSource: LogSource[AnyRef] = new LogSource[AnyRef] {
    def genString(o: AnyRef): String = o.getClass.getName
    override def getClazz(o: AnyRef): Class[_] = o.getClass
  }
  val log = Logging(system, this)

  implicit val duration = 1.minutes
  implicit val timeOut = Timeout(duration)

  val domainRegion = ClusterSharding(system).start(
    typeName = DomainActor.shardName,
    entityProps = DomainActor.props(),
    settings = ClusterShardingSettings(system),
    extractEntityId = DomainActor.idExtractor,
    extractShardId = DomainActor.shardResolver)
  val collectionRegion = ClusterSharding(system).start(
    typeName = CollectionActor.shardName,
    entityProps = CollectionActor.props(),
    settings = ClusterShardingSettings(system),
    extractEntityId = CollectionActor.idExtractor,
    extractShardId = CollectionActor.shardResolver)
  val documentRegion = ClusterSharding(system).start(
    typeName = DocumentActor.shardName,
    entityProps = DocumentActor.props(),
    settings = ClusterShardingSettings(system),
    extractEntityId = DocumentActor.idExtractor,
    extractShardId = DocumentActor.shardResolver)
  val userRegion = ClusterSharding(system).start(
    typeName = UserActor.shardName,
    entityProps = UserActor.props(),
    settings = ClusterShardingSettings(system),
    extractEntityId = UserActor.idExtractor,
    extractShardId = UserActor.shardResolver)
  val viewRegion = ClusterSharding(system).start(
    typeName = ViewActor.shardName,
    entityProps = ViewActor.props(),
    settings = ClusterShardingSettings(system),
    extractEntityId = ViewActor.idExtractor,
    extractShardId = ViewActor.shardResolver)
  val formRegion = ClusterSharding(system).start(
    typeName = FormActor.shardName,
    entityProps = FormActor.props(),
    settings = ClusterShardingSettings(system),
    extractEntityId = FormActor.idExtractor,
    extractShardId = FormActor.shardResolver)
  val roleRegion = ClusterSharding(system).start(
    typeName = RoleActor.shardName,
    entityProps = RoleActor.props(),
    settings = ClusterShardingSettings(system),
    extractEntityId = RoleActor.idExtractor,
    extractShardId = RoleActor.shardResolver)
  val profileRegion = ClusterSharding(system).start(
    typeName = ProfileActor.shardName,
    entityProps = ProfileActor.props(),
    settings = ClusterShardingSettings(system),
    extractEntityId = ProfileActor.idExtractor,
    extractShardId = ProfileActor.shardResolver)

  val rootDomain = system.settings.config.getString("domain.root-domain")
  val adminName = system.settings.config.getString("domain.administrator.name")

  def init() = {
    implicit val ec = system.dispatchers.lookup("akka.init-dispatcher")
    implicit val duration = 1.minutes
    implicit val timeOut = Timeout(duration)
    (domainRegion ? GetDomain(s"${rootDomain}~.domains~${rootDomain}", adminName)).flatMap{
      case DomainNotFound =>
        (domainRegion ? CreateDomain(s"${rootDomain}~.domains~${rootDomain}", adminName, JsObject())).map{
          case _:Domain => "System intialize successfully!"
          case other => s"System intialize failed: ${other}"
        }
      case _ => Future.successful("System has intialized!")
    }
  }

  println(Await.result[String](init(), 120.seconds))

  val callbackQueue = Queue[CallbackWrapper]()
  val domainWrapper = DomainWrapper(system, callbackQueue)
  val collectionWrapper = CollectionWrapper(system, callbackQueue)
  val documentWrapper = DocumentWrapper(system, callbackQueue)
  val userWrapper = UserWrapper(system, callbackQueue)
  val viewWrapper = ViewWrapper(system, callbackQueue)
  val formWrapper = FormWrapper(system, callbackQueue)
  val roleWrapper = RoleWrapper(system, callbackQueue)
  val profileWrapper = ProfileWrapper(system, callbackQueue)

  import scala.sys.process._
  val processId = Seq("sh", "-c", "echo $PPID").!!.trim.toInt
  val sigal = Signal.SIGWINCH
  val runnable = new Runnable() {
    var running = true
    def stop = {
      running = false
      sigal.kill(processId)
    }
    def run = {
      val nodeJS = NodeJS.createNodeJS()
      val runtime = nodeJS.getRuntime

      val callback = new JavaVoidCallback() {
        def invoke(receiver: V8Object, parameters: V8Array) = {
          if (callbackQueue.size > 0) {
            callbackQueue.synchronized {
              callbackQueue.dequeueAll(_ => true)
            }
          }.foreach { cw => cw.call() }
        }
      }

      runtime.registerJavaMethod(callback, "asyncCallback")
      runtime.registerJavaMethod(domainWrapper, "bind", "__DomainWrapper", Array[Class[_]](classOf[V8Object]), true)
      runtime.registerJavaMethod(collectionWrapper, "bind", "__CollectionWrapper", Array[Class[_]](classOf[V8Object]), true)
      runtime.registerJavaMethod(viewWrapper, "bind", "__ViewWrapper", Array[Class[_]](classOf[V8Object]), true)      
      runtime.registerJavaMethod(formWrapper, "bind", "__FormWrapper", Array[Class[_]](classOf[V8Object]), true)      
      runtime.registerJavaMethod(roleWrapper, "bind", "__RoleWrapper", Array[Class[_]](classOf[V8Object]), true)      
      runtime.registerJavaMethod(profileWrapper, "bind", "__ProfileWrapper", Array[Class[_]](classOf[V8Object]), true)      
      runtime.registerJavaMethod(documentWrapper, "bind", "__DocumentWrapper", Array[Class[_]](classOf[V8Object]), true)
      runtime.registerJavaMethod(userWrapper, "bind", "__UserWrapper", Array[Class[_]](classOf[V8Object]), true)

      nodeJS.exec(new File("backend/www.js"))
//      nodeJS.exec(new File("backend/test.js"))

      while (nodeJS.isRunning() && running) {
        nodeJS.handleMessage()
      }
      nodeJS.release();
    }
  }
  new Thread(runnable).start()

  val promise = Promise[Done]()
  sys.addShutdownHook {
    promise.trySuccess(Done)
  }
  Future {
    blocking {
      if (StdIn.readLine("Press RETURN to stop...\n") != null)
        promise.trySuccess(Done)
    }
  }
  promise.future.onComplete { _ => runnable.stop; system.terminate() }
}