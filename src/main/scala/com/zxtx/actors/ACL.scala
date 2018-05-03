package com.zxtx.actors

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Try
import scala.util.Failure
import scala.util.Success

import com.zxtx.actors.DocumentActor._

import akka.actor.Actor
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.LWWMap
import akka.cluster.ddata.LWWMapKey
import akka.cluster.ddata.Replicator.Get
import akka.cluster.ddata.Replicator.GetSuccess
import akka.cluster.ddata.Replicator.NotFound
import akka.cluster.ddata.Replicator.ReadLocal
import akka.cluster.ddata.Replicator.ReadMajority
import akka.cluster.ddata.Replicator.Update
import akka.cluster.ddata.Replicator.UpdateSuccess
import akka.cluster.ddata.Replicator.UpdateFailure
import akka.cluster.ddata.Replicator.WriteLocal
import akka.cluster.ddata.Replicator.WriteMajority
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.Timeout

import pdi.jwt.Jwt
import pdi.jwt.JwtAlgorithm
import pdi.jwt.JwtBase64
import spray.json._
import akka.cluster.Cluster
import gnieh.diffson.sprayJson._
import akka.persistence.query.PersistenceQuery
import com.zxtx.persistence.ElasticSearchReadJournal
import akka.NotUsed
import akka.persistence.query.EventEnvelope
import akka.http.scaladsl.model.StatusCodes
import com.zxtx.persistence.ElasticSearchStore
import akka.Done

trait ACL { this: Actor =>
  import ACL._

  val replicator = DistributedData(context.system).replicator
  val secretCacheKey: String = "SecretCacheKey"

  val system = context.system
  implicit val executionContext = context.dispatcher
  implicit val cluster = Cluster(system)
  val documentRegion = ClusterSharding(context.system).shardRegion(DocumentActor.shardName)
  val store = ElasticSearchStore(system)

  implicit val duration = 5.seconds
  implicit val timeOut = Timeout(duration)

  def aclValue(aclObj: JsObject, op: String, name: String): Vector[String] = aclObj.getFields(op) match {
    case Seq(opObj: JsObject) => opObj.getFields(name) match {
      case Seq(ja: JsArray) => ja.elements.map { jv => jv.asInstanceOf[JsString].value }
      case _                => Vector[String]()
    }
    case _ => Vector[String]()
  }

  def profileValue(profile: JsObject, name: String): Vector[String] = profile.getFields(name) match {
    case Seq(ja: JsArray) => ja.elements.map { jv => jv.asInstanceOf[JsString].value }
    case _                => Vector[String]()
  }

  def fetchProfile(domain: String, user: String) = user match {
    case "anonymous" => Future.successful(Document("", "anonymous", 0L, 0L, 0L, None, JsObject()))
    case _           => documentRegion ? GetDocument(s"${domain}~profiles~${user}", user)
  }

  def checkPermission(user: String, command: Any): Future[Permission]

  def setACL(user: String, acl: JsValue, patch: JsonPatch) = checkPermission(user, SetACL).map {
    case Granted => Try {
      patch(acl)
    } match {
      case Success(_) => DoSetACL(user, patch, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
      case Failure(e) => SetACLException(e)
    }
    case Denied => Denied
  }.recover { case e => e }

  def setEventACL(user: String, persistenceId: String, patch: JsonPatch) = checkPermission(user, SetEventACL).flatMap {
    case Granted =>
      implicit val mat = ActorMaterializer()
      val readJournal = PersistenceQuery(system).readJournalFor[ElasticSearchReadJournal](ElasticSearchReadJournal.Identifier)
      readJournal.currentEventsByPersistenceId(persistenceId, 0, Long.MaxValue).map { ee =>
        val jo = ee.event.asInstanceOf[JsValue].asJsObject
        val meta = jo.fields("_metadata").asJsObject
        Try {
          patch(meta.fields("acl"))
        } match {
          case Success(acl) =>
            val _id = jo.fields("_id").asInstanceOf[JsString].value
            val _type = jo.fields("_type").asInstanceOf[JsString].value
            val _index = jo.fields("_index").asInstanceOf[JsString].value
            val op = s"""{"index":{"_index":"${_index}","_type":"${_type}","_id":"${_id}"}}"""
            op + "\n" + JsObject(jo.fields + ("_metadata" -> JsObject(meta.fields + ("acl" -> acl)))).compactPrint
          case Failure(e) => throw SetEventACLException(e)
        }
      }.fold("") { (b, o) => b + "\n" + o }.mapAsync(1) {
        case b: String =>
          store.post(uri = "http://localhost:9200/_bulk?refresh", entity = (b + "\n")) map {
            case (StatusCodes.OK, _) => DoSetEventACL(user, patch, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
            case (code, jv)          => throw new RuntimeException(s"Set event acl error: $jv")
          }
      }.runWith(Sink.last)
    case Denied => Future.successful(Denied)
  }.recover { case e => e }
}

object ACL {
  case class SetACL(pid: String, user: String, patch: JsonPatch) extends Command
  case class DoSetACL(user: String, patch: JsonPatch, raw: JsObject, request: Option[Any] = None)
  case class ACLSet(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends DocumentEvent
  case class SetACLException(exception: Throwable) extends Exception

  case class SetEventACL(pid: String, user: String, patch: JsonPatch) extends Command
  case class DoSetEventACL(user: String, patch: JsonPatch, raw: JsObject, request: Option[Any] = None)
  case class EventACLSet(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends DocumentEvent
  case class SetEventACLException(exception: Throwable) extends Exception

  def eventACL(user: String) = s"""{
        "get":{
            "roles":["administrator","user"],
            "users":["${user}"]
        },
        "delete":{
            "roles":["administrator"],
            "users":["${user}"]
        }
      }""".parseJson.asJsObject

  object JsonProtocol extends DocumentJsonProtocol {
    implicit object ACLSetFormat extends RootJsonFormat[ACLSet] {
      import gnieh.diffson.sprayJson.provider.marshall
      import gnieh.diffson.sprayJson.provider.patchMarshaller
      def write(acls: ACLSet) = {
        val metaObj = newMetaObject(acls.raw.getFields("_metadata"), acls.author, acls.revision, acls.created)
        JsObject(("id" -> JsString(acls.id)) :: ("patch" -> marshall(acls.patch)) :: acls.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, patch, jo) = extractFieldsWithPatch(value, "DomainAuthorized event expected!")
        ACLSet(id, author, revision, created, patch, jo)
      }
    }

    implicit object EventACLSetFormat extends RootJsonFormat[EventACLSet] {
      import gnieh.diffson.sprayJson.provider.marshall
      import gnieh.diffson.sprayJson.provider.patchMarshaller
      def write(acls: EventACLSet) = {
        val metaObj = newMetaObject(acls.raw.getFields("_metadata"), acls.author, acls.revision, acls.created)
        JsObject(("id" -> JsString(acls.id)) :: ("patch" -> marshall(acls.patch)) :: acls.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, patch, jo) = extractFieldsWithPatch(value, "DomainAuthorized event expected!")
        EventACLSet(id, author, revision, created, patch, jo)
      }
    }
  }

  trait Permission
  case object Granted extends Permission
  case object Denied extends Permission

  case class CheckPermission(pid: String, user: String, command: Any) extends Command
  case class CheckPermissionException(exception: Throwable) extends Exception
}
