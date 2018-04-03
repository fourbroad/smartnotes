package com.zxtx.httpservice

import java.io.File

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.blocking
import scala.io.StdIn

import com.eclipsesource.v8.NodeJS
import com.eclipsesource.v8.V8Object
import com.typesafe.config.ConfigFactory
import com.zxtx.actors.DomainActor
import com.zxtx.actors.NodeJsActor
import com.zxtx.actors.wrappers.DomainWrapper

import akka.Done
import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ClusterShardingSettings
import akka.event.LogSource
import akka.event.Logging
import com.zxtx.actors.DocumentActor
import com.zxtx.actors.DocumentSetActor
import com.eclipsesource.v8.JavaCallback
import com.eclipsesource.v8.V8Array
import posix.Signal

object Main extends App {
  val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + 2551).withFallback(ConfigFactory.load())
  implicit val system = ActorSystem("SmartNotes", config)
  implicit val executionContext = system.dispatcher

  implicit val logSource: LogSource[AnyRef] = new LogSource[AnyRef] {
    def genString(o: AnyRef): String = o.getClass.getName
    override def getClazz(o: AnyRef): Class[_] = o.getClass
  }
  val log = Logging(system, this)

  val domainRegion = ClusterSharding(system).start(
    typeName = DomainActor.shardName,
    entityProps = DomainActor.props(),
    settings = ClusterShardingSettings(system),
    extractEntityId = DomainActor.idExtractor,
    extractShardId = DomainActor.shardResolver)
  val documentSetRegion = ClusterSharding(system).start(
    typeName = DocumentSetActor.shardName,
    entityProps = DocumentSetActor.props(),
    settings = ClusterShardingSettings(system),
    extractEntityId = DocumentSetActor.idExtractor,
    extractShardId = DocumentSetActor.shardResolver)
  val documentRegion = ClusterSharding(system).start(
    typeName = DocumentActor.shardName,
    entityProps = DocumentActor.props(),
    settings = ClusterShardingSettings(system),
    extractEntityId = DocumentActor.idExtractor,
    extractShardId = DocumentActor.shardResolver)

  //  val nodeJsActor = system.actorOf(NodeJsActor.props, "NodeJs")

//  DomainWrapper.init(system, nodeJsActor)

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
      //      runtime.registerJavaMethod(DomainWrapper, "bind", "Domain", Array[Class[_]](classOf[V8Object], classOf[String]), true)

      nodeJS.exec(new File("nodejs/bin/www"))

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