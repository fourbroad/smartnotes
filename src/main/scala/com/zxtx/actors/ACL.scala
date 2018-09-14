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
  val cacheKey: String = "CacheKey"

  val system = context.system
  implicit val executionContext = context.dispatcher
  implicit val cluster = Cluster(system)
  val documentRegion = ClusterSharding(context.system).shardRegion(DocumentActor.shardName)
  val store = ElasticSearchStore(system)

  implicit val duration = 10.seconds
  implicit val timeOut = Timeout(duration)

  def profilePID(domainId: String, userId: String) = s"${domainId}~.profiles~${userId}"

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
    case "anonymous" => Future.successful(Document("", "anonymous", 0L, 0L, 0L, None, JsObject("roles" -> JsArray(JsString("anonymous")))))
    case _           => documentRegion ? GetDocument(profilePID(domain, user), user)
  }
  
  def filterQuery(domain:String, user: String, query: JsObject): Future[JsObject] = {
    val qObj = query.getFields("query") match {
      case Seq(qo: JsObject) => qo
      case _                 => JsObject();
    }
    val bObj = qObj.getFields("bool") match {
      case Seq(b: JsObject) => b
      case _                => JsObject();
    }
    val fObj = bObj.getFields("filter") match {
      case Seq(f: JsObject) => JsArray(f)
      case Seq(f: JsArray)  => f
      case _                => JsArray()
    }
    val removedObj = JsObject("term" -> JsObject("_metadata.removed" -> JsBoolean(true)))

    fetchProfile(domain, user).map {
      case Document(_, _, _, _, _, _, profile) =>
        val roles = profileValue(profile, "roles").map { r => JsObject("term" -> JsObject("_metadata.acl.get.roles.keyword" -> JsString(r))) }
        val groups = profileValue(profile, "groups").map { g => JsObject("term" -> JsObject("_metadata.acl.get.groups.keyword" -> JsString(g)))}
        val userObj = JsObject("term" -> JsObject("_metadata.acl.get.users.keyword" -> JsString(user)))
        val should = JsArray(roles ++ groups ++ Vector(userObj))
        val filter = JsObject("bool" -> JsObject("should" -> should))
        JsObject(query.fields + ("query" -> JsObject(qObj.fields + ("bool" -> JsObject(bObj.fields + ("filter" -> JsArray(fObj.elements :+ filter)) + ("must_not"->JsArray(Vector(removedObj))))))))
      case _ =>
        val filter = JsObject("bool" -> JsObject("should" -> JsArray(Vector(JsObject("term" -> JsObject("_metadata.acl.get.users.keyword" -> JsString("anonymous")))))))
        JsObject(query.fields + ("query" -> JsObject(qObj.fields + ("bool" -> JsObject(bObj.fields + ("filter" -> JsArray(fObj.elements :+ filter))+ ("must_not"->JsArray(Vector(removedObj))))))))
    }
  }

  def hitCache(domainId: String): Future[Option[Boolean]] = (replicator ? Get(LWWMapKey[String, Boolean](cacheKey), ReadLocal)).map {
    case g @ GetSuccess(LWWMapKey(_), _) =>
      g.dataValue match {
        case data: LWWMap[_, _] => data.asInstanceOf[LWWMap[String, Boolean]].get(domainId)
        case _                  => None
      }
    case NotFound(_, _) => None
  }

  def setCache(domainId: String, exist: Boolean) = (replicator ? Update(LWWMapKey[String, Boolean](cacheKey), LWWMap(), WriteLocal)(_ + (domainId -> exist))).map {
    case UpdateSuccess(LWWMapKey(_), _)    => CacheUpdated
    case _: UpdateFailure[LWWMapKey[_, _]] => UpdateCacheError
  }

  def clearCache(domainId: String) = (replicator ? Update(LWWMapKey[String, Boolean](cacheKey), LWWMap(), WriteLocal)(_ - domainId)).map {
    case UpdateSuccess(LWWMapKey(_), _)    => CacheCleared
    case _: UpdateFailure[LWWMapKey[_, _]] => ClearCacheError
  }

  def checkPermission(user: String, command: Any): Future[Permission]

  def getACL(user: String) = checkPermission(user, GetACL).map {
    case Granted => DoGetACL(user)
    case Denied  => Denied
  }.recover { case e => e }

  def replaceACL(user: String, raw: JsObject) = checkPermission(user, ReplaceACL).map {
    case Granted => DoReplaceACL(user, JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> eventACL(user)))))
    case other   => other
  }.recover { case e => e }

  def patchACL(user: String, acl: JsValue, patch: JsonPatch) = checkPermission(user, PatchACL).map {
    case Granted => Try {
      patch(acl)
    } match {
      case Success(_) => DoPatchACL(user, patch, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
      case Failure(e) => PatchACLException(e)
    }
    case Denied => Denied
  }.recover { case e => e }

  def setEventACL(user: String, persistenceId: String, patch: JsonPatch) = checkPermission(user, PatchEventACL).flatMap {
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
          case Failure(e) => throw PatchEventACLException(e)
        }
      }.reduce { _ + "\n" + _ }.mapAsync(1) { b =>
        store.post(uri = "http://localhost:9200/_bulk?refresh", entity = (b + "\n")) map {
          case (StatusCodes.OK, _) => DoPatchEventACL(user, patch, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
          case (code, jv)          => throw new RuntimeException(s"Set event acl error: $jv")
        }
      }.runWith(Sink.last)
    case Denied => Future.successful(Denied)
  }.recover { case e => e }

  def removePermissionSubject(user: String, operation: String, kind: String, subject: String) = checkPermission(user, RemovePermissionSubject).map {
    case Granted => DoRemovePermissionSubject(user, operation, kind, subject,
      JsObject("operation" -> JsString(operation), "kind" -> JsString(kind), "subject" -> JsString(subject), "_metadata" -> JsObject("acl" -> eventACL(user))))
    case Denied => Denied
  }.recover { case e => e }

  def removeEventPermissionSubject(user: String, pid: String, operation: String, kind: String, subject: String) = checkPermission(user, RemoveEventPermissionSubject).flatMap {
    case Granted =>
      val source = operation match {
        case "*" => s"""
          def acl = ctx._source._metadata.acl;
          if(acl.containsKey("get") && acl.get.containsKey("${kind}")) { acl.get.${kind}.removeIf(item->item == "params.subject")};
          if(acl.containsKey("delete") && acl.delete.containsKey("${kind}")) { acl.delete.${kind}.removeIf(item->item == "params.subject")};
        """
        case other => s"""
          def acl = ctx._source._metadata.acl;
          if(acl.containsKey("${operation}") && acl.${operation}.containsKey("${kind}")) { acl.${operation}.${kind}.removeIf(item->item == "params.subject")};
        """
      }
      val segments = pid.split("%7E")
      val id = segments(2)
      val alias = s"${segments(0)}~${segments(1)}~all~events"
      val removeByQeury = s"""{
        "query":{
          "bool":{
            "must":[
              {"term":{"id.keyword":"${id}"}}
            ]
          }
        },
        "script":{
          "lang": "painless",
          "source": "${source}"
          "params": {"subject": "${subject}"}
        }
      }"""

      val uri = s"http://localhost:9200/${alias}/_update_by_query"
      store.post(uri = uri, entity = removeByQeury).map {
        case (StatusCodes.OK, _) => DoRemoveEventPermissionSubject(user, operation, kind, subject,
          JsObject("operation" -> JsString(operation), "kind" -> JsString(kind), "subject" -> JsString(subject), "_metadata" -> JsObject("acl" -> eventACL(user))))
        case (code, _) => throw new RuntimeException(s"Error remove event permission subject: $code")
      }
    case Denied => Future.successful(Denied)
  }.recover { case e => e }
}

object ACL {
  case class GetACL(pid: String, user: String) extends Command
  case class DoGetACL(user: String, request: Option[Any] = None)

  case class ReplaceACL(pid: String, user: String, acl: JsObject) extends Command
  case class DoReplaceACL(user: String, acl: JsObject, request: Option[Any] = None)
  case class ACLReplaced(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case class ReplaceACLException(exception: Throwable) extends Exception

  case class PatchACL(pid: String, user: String, patch: JsonPatch) extends Command
  case class DoPatchACL(user: String, patch: JsonPatch, raw: JsObject, request: Option[Any] = None)
  case class ACLPatched(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends Event
  case class PatchACLException(exception: Throwable) extends Exception

  case class RemovePermissionSubject(pid: String, user: String, operation: String, kind: String, subject: String) extends Command
  case class DoRemovePermissionSubject(user: String, operation: String, kind: String, subject: String, raw: JsObject, request: Option[Any] = None)
  case class PermissionSubjectRemoved(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case class RemovePermissionSubjectException(exception: Throwable) extends Exception

  case class PatchEventACL(pid: String, user: String, patch: JsonPatch) extends Command
  case class DoPatchEventACL(user: String, patch: JsonPatch, raw: JsObject, request: Option[Any] = None)
  case class EventACLPatched(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends Event
  case class PatchEventACLException(exception: Throwable) extends Exception

  case class RemoveEventPermissionSubject(pid: String, user: String, operation: String, kind: String, subject: String) extends Command
  case class DoRemoveEventPermissionSubject(user: String, operation: String, kind: String, subject: String, raw: JsObject, request: Option[Any] = None)
  case class EventPermissionSubjectRemoved(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case class RemoveEventPermissionSubjectException(exception: Throwable) extends Exception

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

    implicit object ACLReplacedFormat extends RootJsonFormat[ACLReplaced] {
      def write(ar: ACLReplaced) = {
        val metaObj = newMetaObject(ar.raw.getFields("_metadata"), ar.author, ar.revision, ar.created)
        JsObject(("id" -> JsString(ar.id)) :: ar.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, raw) = extractFields(value, "ACLReplaced event expected!")
        ACLReplaced(id, author, revision, created, raw)
      }
    }

    implicit object ACLPatchedFormat extends RootJsonFormat[ACLPatched] {
      import gnieh.diffson.sprayJson.provider.marshall
      import gnieh.diffson.sprayJson.provider.patchMarshaller
      def write(acls: ACLPatched) = {
        val metaObj = newMetaObject(acls.raw.getFields("_metadata"), acls.author, acls.revision, acls.created)
        JsObject(("id" -> JsString(acls.id)) :: ("patch" -> marshall(acls.patch)) :: acls.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, patch, jo) = extractFieldsWithPatch(value, "ACLPatched event expected!")
        ACLPatched(id, author, revision, created, patch, jo)
      }
    }

    implicit object EventACLPatchedFormat extends RootJsonFormat[EventACLPatched] {
      import gnieh.diffson.sprayJson.provider.marshall
      import gnieh.diffson.sprayJson.provider.patchMarshaller
      def write(acls: EventACLPatched) = {
        val metaObj = newMetaObject(acls.raw.getFields("_metadata"), acls.author, acls.revision, acls.created)
        JsObject(("id" -> JsString(acls.id)) :: ("patch" -> marshall(acls.patch)) :: acls.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, patch, jo) = extractFieldsWithPatch(value, "EventACLPatched event expected!")
        EventACLPatched(id, author, revision, created, patch, jo)
      }
    }

    implicit object PermissionSubjectRemovedFormat extends RootJsonFormat[PermissionSubjectRemoved] {
      def write(psr: PermissionSubjectRemoved) = {
        val metaObj = newMetaObject(psr.raw.getFields("_metadata"), psr.author, psr.revision, psr.created)
        JsObject(("id" -> JsString(psr.id)) :: psr.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "PermissionSubjectRemoved event expected!")
        PermissionSubjectRemoved(id, author, revision, created, jo)
      }
    }

    implicit object EventPermissionSubjectRemovedFormat extends RootJsonFormat[EventPermissionSubjectRemoved] {
      def write(epsr: EventPermissionSubjectRemoved) = {
        val metaObj = newMetaObject(epsr.raw.getFields("_metadata"), epsr.author, epsr.revision, epsr.created)
        JsObject(("id" -> JsString(epsr.id)) :: epsr.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "EventPermissionSubjectRemoved event expected!")
        EventPermissionSubjectRemoved(id, author, revision, created, jo)
      }
    }

  }

  def doRemovePermissionSubject(acl: JsObject, operation: String, kind: String, subject: String) = {
    var aclFields: List[(String, JsValue)] = Nil
    acl.fields.foreach { op =>
      op._1 match {
        case name if (operation == "*" || name == operation) =>
          var opFields: List[(String, JsValue)] = Nil
          op._2.asJsObject.fields.foreach { k =>
            k._1 match {
              case `kind` => opFields = (kind, JsArray(k._2.asInstanceOf[JsArray].elements.filter(jv => jv.asInstanceOf[JsString].value != subject))) :: opFields
              case other  => opFields = k :: opFields
            }
          }
          aclFields = (op._1, JsObject(opFields: _*)) :: aclFields
        case other => aclFields = op :: aclFields
      }
    }
    JsObject(aclFields: _*)
  }

  trait Permission
  case object Granted extends Permission
  case object Denied extends Permission

  case class CheckPermission(pid: String, user: String, command: Any) extends Command
  case class CheckPermissionException(exception: Throwable) extends Exception

  sealed trait CacheResult
  case object CacheUpdated extends CacheResult
  case object UpdateCacheError extends CacheResult
  case object CacheCleared extends CacheResult
  case object ClearCacheError extends CacheResult
}
