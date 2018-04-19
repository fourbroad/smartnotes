package com.zxtx.actors

import scala.concurrent._
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import akka.NotUsed
import akka.Done
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.pattern.ask
import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes
import akka.cluster.Cluster
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.Passivate
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{ Publish, Subscribe }
import akka.cluster.sharding.ClusterSharding
import akka.persistence._
import akka.persistence.query._
import akka.stream.scaladsl._
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings

import gnieh.diffson.sprayJson._
import gnieh.diffson.sprayJson.provider._

import spray.json.DefaultJsonProtocol
import spray.json.DeserializationException
import spray.json.JsBoolean
import spray.json.JsNumber
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import spray.json.RootJsonFormat

import com.zxtx.actors.DocumentActor._
import com.zxtx.persistence._

object CollectionActor {

  def props(): Props = Props[CollectionActor]

  object Collection {
    val empty = new Collection("", "", 0L, 0L, 0L, None, JsObject(Map[String, JsValue]()))
  }
  case class Collection(id: String, author: String = "anonymous", revision: Long, created: Long, updated: Long, deleted: Option[Boolean], raw: JsObject)

  case class CreateCollection(pid: String, user: String, raw: JsObject, initFlag: Option[Boolean] = None) extends Command
  case class GetCollection(pid: String, user: String, path: String) extends Command
  case class ReplaceCollection(pid: String, user: String, raw: JsObject) extends Command
  case class PatchCollection(pid: String, user: String, patch: JsonPatch) extends Command
  case class DeleteCollection(pid: String, user: String) extends Command
  case class FindDocuments(pid: String, user: String, params: Seq[(String, String)], body: JsObject) extends Command
  case class GarbageCollection(pid: String, user: String, request: Option[Any] = None) extends Command

  case class CollectionCreated(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class CollectionReplaced(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class CollectionPatched(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends DocumentEvent
  case class CollectionDeleted(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  object GarbageCollectionCompleted extends DocumentEvent

  case object CollectionNotFound extends Exception
  case object CollectionAlreadyExists extends Exception
  case object CollectionIsCreating extends Exception
  case object CollectionSoftDeleted extends Exception
  case class PatchCollectionException(exception: Throwable) extends Exception

  val idExtractor: ShardRegion.ExtractEntityId = { case cmd: Command => (cmd.pid, cmd) }
  val shardResolver: ShardRegion.ExtractShardId = { case cmd: Command => (math.abs(cmd.pid.hashCode) % 100).toString }
  val shardName: String = "Collection"

  def persistenceId(domain: String, collection: String) = s"${domain}~.collections~${collection}"

  private case class DoCreateCollection(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoGetCollection(user: String, path: String, request: Option[Any] = None)
  private case class DoReplaceCollection(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoPatchCollection(user: String, patch: JsonPatch, request: Option[Any] = None)
  private case class DoDeleteCollection(user: String, request: Option[Any] = None)
  private case class DoFindDocuments(user: String, params: Seq[(String, String)], body: JsObject, request: Option[Any] = None)
  private case class DoGarbageCollection(user: String, request: Option[Any] = None)
  private case class ClearCacheSuccess(user: String, request: Option[Any] = None)
  private case class InitializeIndices(user: String, raw: JsObject, request: Option[Any] = None)

  object JsonProtocol extends DocumentJsonProtocol {
    implicit object CollectionFormat extends RootJsonFormat[Collection] {
      def write(ds: Collection) = {
        val metaObj = newMetaObject(ds.raw.getFields("_metadata"), ds.author, ds.revision, ds.created, ds.updated, ds.deleted)
        JsObject(("id" -> JsString(ds.id)) :: ds.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, updated, deleted, jo) = extractFieldsWithUpdatedDeleted(value, "Collection expected!")
        Collection(id, author, revision, created, updated, deleted, jo)
      }
    }

    implicit object CollectionCreatedFormat extends RootJsonFormat[CollectionCreated] {
      def write(dsc: CollectionCreated) = {
        val metaObj = newMetaObject(dsc.raw.getFields("_metadata"), dsc.author, dsc.revision, dsc.created)
        JsObject(("id" -> JsString(dsc.id)) :: dsc.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "CollectionCreated event expected!")
        CollectionCreated(id, author, revision, created, jo)
      }
    }

    implicit object CollectionReplacedFormat extends RootJsonFormat[CollectionReplaced] {
      def write(dsr: CollectionReplaced) = {
        val metaObj = newMetaObject(dsr.raw.getFields("_metadata"), dsr.author, dsr.revision, dsr.created)
        JsObject(("id" -> JsString(dsr.id)) :: dsr.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "CollectionReplaced event expected!")
        CollectionReplaced(id, author, revision, created, jo)
      }
    }

    implicit object CollectionPatchedFormat extends RootJsonFormat[CollectionPatched] {
      def write(dsp: CollectionPatched) = {
        val metaObj = newMetaObject(dsp.raw.getFields("_metadata"), dsp.author, dsp.revision, dsp.created)
        JsObject(("id" -> JsString(dsp.id)) :: ("patch" -> marshall(dsp.patch)) :: dsp.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, patch, jo) = extractFieldsWithPatch(value, "CollectionPatched event expected!")
        CollectionPatched(id, author, revision, created, patch, jo)
      }
    }

    implicit object CollectionDeletedFormat extends RootJsonFormat[CollectionDeleted] {
      def write(dsd: CollectionDeleted) = {
        val metaObj = newMetaObject(dsd.raw.getFields("_metadata"), dsd.author, dsd.revision, dsd.created)
        JsObject(("id" -> JsString(dsd.id)) :: dsd.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "CollectionDeleted event expected!")
        CollectionDeleted(id, author, revision, created, jo)
      }
    }
  }

  private case class State(collection: Collection, deleted: Boolean) {
    def updated(evt: DocumentEvent): State = evt match {
      case CollectionCreated(id, author, revision, created, raw) =>
        val metadata = JsObject(raw.fields("_metadata").asJsObject.fields + ("updated" -> JsNumber(created)))
        copy(collection = Collection(id, author, revision, created, created, None, JsObject(raw.fields + ("_metadata" -> metadata))))
      case CollectionReplaced(_, _, revision, created, raw) =>
        val oldMetaFields = collection.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)))
        copy(collection = collection.copy(revision = revision, updated = created, raw = JsObject(raw.fields + ("_metadata" -> metadata))))
      case CollectionPatched(_, _, revision, created, patch, _) =>
        val patchedDoc = patch(collection.raw).asJsObject
        val oldMetaFields = patchedDoc.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)))
        copy(collection = collection.copy(revision = revision, updated = created, raw = JsObject(patchedDoc.fields - "_metadata" + ("_metadata" -> metadata))))
      case CollectionDeleted(_, _, revision, created, _) =>
        val oldMetaFields = collection.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("deleted" -> JsBoolean(true)))
        copy(collection = collection.copy(revision = revision, updated = created, deleted = Some(true), raw = JsObject(collection.raw.fields + ("_metadata" -> metadata))))
    }

    def updated(ds: Collection): State = ds match {
      case Collection(id, author, revision, created, updated, deleted, raw) => copy(collection = Collection(id, author, revision, created, updated, deleted, raw))
    }
  }

  import spray.json._
  def defaultEventIndexTemplate(domainName: String, collectionName: String): JsObject =
    s"""{
      "index_patterns": ["${domainName}~${collectionName}_*~events-*"],
      "aliases" : {
        "${domainName}~${collectionName}~hot~events" : {},
        "${domainName}~${collectionName}~all~events" : {}
      },      
      "settings": {
        "number_of_shards": 5,
        "number_of_replicas": 1
      },
     "mappings": {
        "event": {
          "properties": {
            "_metadata":{
              "properties":{
                "created": {
                  "type": "date",
                  "format": "yyyy-MM-dd HH:mm:ss||yyyy-MM-dd||epoch_millis"
                }
              }
            }
          }
        }
      }
    }""".parseJson.asJsObject

  def defaultSnapshotIndexTemplate(domainName: String, collectionName: String): JsObject =
    s"""{
      "index_patterns": ["${domainName}~${collectionName}_*~snapshots-*"],
      "aliases" : {
        "${domainName}~${collectionName}~hot~snapshots" : {},
        "${domainName}~${collectionName}~all~snapshots" : {}
      },      
      "settings": {
        "number_of_shards": 5,
        "number_of_replicas": 1
      },
      "mappings": {
        "snapshot": {
          "properties": {
            "_metadata":{
              "properties":{
                "created": {
                  "type": "date",
                  "format": "yyyy-MM-dd HH:mm:ss||yyyy-MM-dd||epoch_millis"
                },
                "updated": {
                  "type": "date",
                  "format": "yyyy-MM-dd HH:mm:ss||yyyy-MM-dd||epoch_millis"
                }
              }
            }
          }
        }
      }
    }""".parseJson.asJsObject

  def defaultACL(user: String) = s"""{
        "get":{
            "roles":["administrator","user"],
            "users":["${user}"]
        },
        "replace":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "patch":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "delete":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "create_document":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "find_documents":{
            "roles":["administrator","user"],
            "users":["${user}"]
        },
        "gc":{
            "roles":["administrator"],
            "users":["${user}"]
        }
      }""".parseJson.asJsObject

  private def getJson(jsValue: JsValue, path: Array[String]): JsValue = path.size match {
    case size if size <= 1 => jsValue
    case _ =>
      jsValue match {
        case jo: JsObject =>
          Try {
            getJson(jo.fields(path(1)), path.slice(1, path.size))
          }.recover { case e: java.util.NoSuchElementException => spray.json.JsNull }.get
        case _ => spray.json.JsNull
      }
  }
}

class CollectionActor extends PersistentActor with ACL with ActorLogging {
  import CollectionActor._
  import CollectionActor.JsonProtocol._
  import spray.json._
  import DomainActor._
  import ElasticSearchStore._
  import ACL._

  override def persistenceId: String = self.path.name

  override def journalPluginId = "akka.persistence.elasticsearch.journal"
  override def snapshotPluginId = "akka.persistence.elasticsearch-snapshot-store"

  val rootDomain = system.settings.config.getString("domain.root-domain")
  val adminName = system.settings.config.getString("domain.administrator.name")
  val cacheKey = system.settings.config.getString("domain.cache-key")
  val domainRegion = ClusterSharding(system).shardRegion(DomainActor.shardName)

  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system))

  // passivate the entity when no activity
  context.setReceiveTimeout(2.minutes)

  private var state = State(Collection.empty, false)
  private val store = ElasticSearchStore(system)

  def domain = persistenceId.split("%7E")(0)
  def id = persistenceId.split("%7E")(2)

  val mediator = DistributedPubSub(system).mediator
  mediator ! Subscribe(domain, self)

  override def receiveRecover: Receive = {
    case evt: CollectionCreated =>
      context.become(created)
      state = state.updated(evt)
    case evt: CollectionDeleted =>
      context.become(deleted)
      state = state.updated(evt)
    case evt: DocumentEvent =>
      state = state.updated(evt)
    case SnapshotOffer(_, jo: JsObject) =>
      val ds = jo.convertTo[Collection]
      state = state.updated(ds)
      ds.deleted match {
        case Some(true) => context.become(deleted)
        case _          => context.become(created)
      }
    case RecoveryCompleted =>
      log.debug("CollectionActor recovery completed.")
  }

  override def receiveCommand: Receive = initial

  def initial: Receive = {
    case CreateCollection(_, user, raw, initFlag) =>
      val replyTo = sender
      val parent = context.parent
      createCollection(user, raw, initFlag).foreach {
        case dcc: DoCreateCollection => self ! dcc.copy(request = Some(replyTo))
        case other =>
          replyTo ! other
          parent ! Passivate(stopMessage = PoisonPill)
      }
      context.become(creating)
    case _: Command => sender ! CollectionNotFound
  }

  def creating: Receive = {
    case DoCreateCollection(user, raw, Some(replyTo: ActorRef)) =>
      persist(CollectionCreated(id, user, lastSequenceNr + 1, System.currentTimeMillis, JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> CollectionActor.defaultACL(user)))))) { evt =>
        state = state.updated(evt)
        val ds = state.collection.toJson.asJsObject
        saveSnapshot(ds)
        context.become(created)
        replyTo ! evt.copy(raw = ds)
      }
    case _: Command => sender ! CollectionIsCreating
  }

  def created: Receive = {
    case GetCollection(_, user, path) =>
      val replyTo = sender
      getCollection(user, path).foreach {
        case DoGetCollection(_, _, Some(result)) => replyTo ! result
        case other                               => replyTo ! other
      }
    case ReplaceCollection(_, user, raw) =>
      val replyTo = sender
      replaceCollection(user, raw).foreach {
        case drc: DoReplaceCollection => self ! drc.copy(request = Some(replyTo))
        case other                    => replyTo ! other
      }
    case DoReplaceCollection(user, raw, Some(replyTo: ActorRef)) =>
      persist(CollectionReplaced(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        state = state.updated(evt)
        val ds = state.collection.toJson.asJsObject
        saveSnapshot(ds)
        deleteSnapshot(lastSequenceNr - 1)
        replyTo ! evt.copy(raw = ds)
      }
    case PatchCollection(_, user, patch) =>
      val replyTo = sender
      patchCollection(user, patch).foreach {
        case dpc: DoPatchCollection => self ! dpc.copy(request = Some(replyTo))
        case other                  => replyTo ! other
      }
    case DoPatchCollection(user, patch, Some(replyTo: ActorRef)) =>
      persist(CollectionPatched(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, JsObject())) { evt =>
        state = state.updated(evt)
        val ds = state.collection.toJson.asJsObject
        saveSnapshot(ds)
        deleteSnapshot(lastSequenceNr - 1)
        replyTo ! evt.copy(raw = ds)
      }
    case DeleteCollection(_, user) =>
      val replyTo = sender
      deleteCollection(user).foreach {
        case ddc: DoDeleteCollection => self ! ddc.copy(request = Some(replyTo))
        case other                   => replyTo ! other
      }
    case DoDeleteCollection(user, Some(replyTo: ActorRef)) =>
      persist(CollectionDeleted(id, user, lastSequenceNr + 1, System.currentTimeMillis(), JsObject())) { evt =>
        state = state.updated(evt)
        deleteMessages(lastSequenceNr)
        deleteSnapshot(lastSequenceNr - 1)
        val ds = state.collection.toJson.asJsObject
        saveSnapshot(ds)
        context.become(deleted)
        replyTo ! evt.copy(raw = ds)
        mediator ! Publish(s"${domain}~${id}", evt.copy(raw = ds))
        context.parent ! Passivate(stopMessage = PoisonPill)
      }
    case SaveSnapshotSuccess(metadata)         =>
    case SaveSnapshotFailure(metadata, reason) =>
    case FindDocuments(_, user, params, body) =>
      val replyTo = sender
      findDocuments(user, params, body).foreach {
        case DoFindDocuments(_, _, _, Some(result)) => self ! result
        case other                                  => replyTo ! other
      }
    case GarbageCollection(_, user, _) =>
      val replyTo = sender
      garbageCollection(user).foreach {
        case dgc: DoGarbageCollection => self ! GarbageCollectionCompleted
        case other                    => replyTo ! other
      }
    case CheckPermission(_, user, command) =>
      val replyTo = sender
      checkPermission(user, command).foreach { replyTo ! _ }
    case _: CreateCollection => sender ! CollectionAlreadyExists
    case _: DomainDeleted    => context.parent ! Passivate(stopMessage = PoisonPill)
  }

  def deleted: Receive = {
    case GetCollection(_, user, path) =>
      val replyTo = sender
      val parent = context.parent
      getCollection(user, path).foreach {
        case DoGetCollection(_, _, Some(result)) =>
          replyTo ! result
          parent ! Passivate(stopMessage = PoisonPill)
        case other =>
          replyTo ! other
          parent ! Passivate(stopMessage = PoisonPill)
      }
    case _: Command => sender ! CollectionSoftDeleted
  }

  override def unhandled(msg: Any): Unit = msg match {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
    case _              => super.unhandled(msg)
  }

  private def createCollection(user: String, raw: JsObject, initFlag: Option[Boolean]) = initFlag match {
    case Some(true) => doCreateCollection(user, raw)
    case other =>
      (domainRegion ? CheckPermission(DomainActor.persistenceId(rootDomain, domain), user, CreateCollection)).flatMap {
        case Granted => doCreateCollection(user, raw)
        case Denied  => Future.successful(Denied)
      }.recover { case e => e }

  }

  private def doCreateCollection(user: String, raw: JsObject) = {
    val eventIndexTemplate = raw.fields.get("indexTemplates") match {
      case Some(it: JsObject) =>
        it.fields.get("eventIndexTemplate") match {
          case Some(eit: JsObject) => eit
          case Some(_) | None      => defaultEventIndexTemplate(domain, id)
        }
      case Some(_) | None => defaultEventIndexTemplate(domain, id)
    }
    val snapshotIndexTemplate = raw.fields.get("indexTemplates") match {
      case Some(it: JsObject) =>
        it.fields.get("snapshotIndexTemplate") match {
          case Some(sit: JsObject) => sit
          case Some(_) | None      => defaultSnapshotIndexTemplate(domain, id)
        }
      case Some(_) | None => defaultSnapshotIndexTemplate(domain, id)
    }
    val templates = Map("event_index_template" -> eventIndexTemplate, "snapshot_index_template" -> snapshotIndexTemplate)
    val newRaw = JsObject(raw.fields + ("indexTemplates" -> JsObject(templates)))
    initIndices(templates).map {
      case Done  => DoCreateCollection(user, newRaw)
      case other => other
    }
  }

  private def getCollection(user: String, path: String) = checkPermission(user, GetCollection).flatMap {
    case Granted =>
      store.indices(s"${domain}~${id}_*").map {
        case (StatusCodes.OK, jv) =>
          val jo = state.collection.toJson.asJsObject()
          DoGetCollection(user, path, Some(getJson(JsObject(jo.fields + ("indices" -> jv)), path.split("/"))));
        case (code, _) => throw new RuntimeException(s"Error get indices:$code")
      }
    case other => Future.successful(other)
  }.recover { case e => e }

  private def replaceCollection(user: String, raw: JsObject) = checkPermission(user, ReplaceCollection).map {
    case Granted => DoReplaceCollection(user, raw)
    case other   => other
  }.recover { case e => e }

  private def deleteCollection(user: String) = checkPermission(user, DeleteCollection).map {
    case Granted => DoDeleteCollection(user)
    case other   => other
  }.recover { case e => e }

  private def patchCollection(user: String, patch: JsonPatch) = checkPermission(user, PatchCollection).map {
    case Granted =>
      Try {
        patch(state.collection.raw)
      } match {
        case Success(_) => Future.successful(DoPatchCollection(user, patch))
        case Failure(e) => Future.successful(PatchCollectionException(e))
      }
    case other => other
  }.recover { case e => e }

  private def findDocuments(user: String, params: Seq[(String, String)], body: JsObject) = checkPermission(user, FindDocuments).map {
    case Granted =>
      store.search(s"${domain}~${id}~all~snapshots", params, body.compactPrint).foreach {
        case (StatusCodes.OK, jo: JsObject) =>
          val fields = jo.fields
          val hitsFields = jo.fields("hits").asJsObject.fields
          DoFindDocuments(user, params, body, Some(JsObject(hitsFields + ("_metadata" -> JsObject((fields - "hits"))))))
        case (code, jv) => throw new RuntimeException(s"Find documents error: $jv")
      }
    case other => other
  }.recover { case e => e }

  private def garbageCollection(user: String) = checkPermission(user, GarbageCollection).flatMap {
    case Granted =>
      val query = s"""{
        "query":{
          "bool":{
            "must":[
              {"term":{"_metadata.deleted":true}}
            ]
          }
        }
      }"""
      store.deleteByQuery(s"${domain}~${id}*", Seq[(String, String)](), query).map {
        case (StatusCodes.OK, jo: JsObject) => DoGarbageCollection(user)
        case (_, jv)                        => throw new RuntimeException(jv.compactPrint)
      }
    case other => Future.successful(other)
  }.recover { case e => e }

  private def initIndices(templates: Map[String, JsObject]): Future[Done] = {
    val source = Source.fromIterator(() => templates.iterator)
    val initIndexTemplate = Flow[(String, JsValue)].mapAsync(2) {
      case (id, content: JsObject) => store.newTemplate(id, content.compactPrint).map { case (code, jv) => (id, code, jv) }
    }
    val initIndex = Flow[(String, StatusCode, JsValue)].mapAsync(2) {
      case ("event_index_template", StatusCodes.OK, _) =>
        val segments = persistenceId.split("%7E")
        val domainId = segments(0)
        val id = segments(2)
        store.newIndex(s"${domainId}~${id}_${lastSequenceNr + 1}~events-1")
      case ("snapshot_index_template", StatusCodes.OK, _) =>
        val segments = persistenceId.split("%7E")
        val domainId = segments(0)
        val id = segments(2)
        store.newIndex(s"${domainId}~${id}_${lastSequenceNr + 1}~snapshots-1")
    }
    source.via(initIndexTemplate).via(initIndex).runWith(Sink.ignore)
  }

  override def checkPermission(user: String, command: Any) = fetchProfile(domain, user).map {
    case profile: Document =>
      val aclObj = state.collection.raw.fields("_metadata").asJsObject.fields("acl").asJsObject
      val userRoles = profileValue(profile.raw, "roles")
      val userGroups = profileValue(profile.raw, "groups")
      val (aclRoles, aclGroups, aclUsers) = command match {
        case GetCollection     => (aclValue(aclObj, "get", "roles"), aclValue(aclObj, "get", "groups"), aclValue(aclObj, "get", "users"))
        case ReplaceCollection => (aclValue(aclObj, "replace", "roles"), aclValue(aclObj, "replace", "groups"), aclValue(aclObj, "replace", "users"))
        case PatchCollection   => (aclValue(aclObj, "patch", "roles"), aclValue(aclObj, "patch", "groups"), aclValue(aclObj, "patch", "users"))
        case DeleteCollection  => (aclValue(aclObj, "delete", "roles"), aclValue(aclObj, "delete", "groups"), aclValue(aclObj, "delete", "users"))
        case CreateDocument    => (aclValue(aclObj, "create_document", "roles"), aclValue(aclObj, "create_document", "groups"), aclValue(aclObj, "create_document", "users"))
        case FindDocuments     => (aclValue(aclObj, "find_documents", "roles"), aclValue(aclObj, "find_documents", "groups"), aclValue(aclObj, "find_documents", "users"))
        case GarbageCollection => (aclValue(aclObj, "gc", "roles"), aclValue(aclObj, "gc", "groups"), aclValue(aclObj, "gc", "users"))
        case _                 => (Vector[String](), Vector[String](), Vector[String]())
      }
      if (aclRoles.intersect(userRoles).isEmpty && aclGroups.intersect(userGroups).isEmpty && !aclUsers.contains(user)) Denied else Granted
    case _ => Denied
  }

}
