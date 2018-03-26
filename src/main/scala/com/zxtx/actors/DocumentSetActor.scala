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
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.LWWMapKey
import akka.cluster.ddata.LWWMap
import akka.cluster.ddata.Replicator._
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.Passivate
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{ Publish, Subscribe }
import akka.persistence._
import akka.persistence.query._
import akka.stream.scaladsl._
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.util.Timeout

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
import akka.cluster.sharding.ClusterSharding

object DocumentSetActor {

  def props(): Props = Props[DocumentSetActor]

  object DocumentSet {
    val empty = new DocumentSet("", "", 0L, 0L, 0L, None, JsObject(Map[String, JsValue]()))
  }
  case class DocumentSet(id: String, author: String = "anonymous", revision: Long, created: Long, updated: Long, deleted: Option[Boolean], raw: JsObject)

  case class CreateDocumentSet(pid: String, user: String, raw: JsObject) extends Command
  case class GetDocumentSet(pid: String, user: String, path: String) extends Command
  case class ReplaceDocumentSet(pid: String, user: String, raw: JsObject) extends Command
  case class PatchDocumentSet(pid: String, user: String, patch: JsonPatch) extends Command
  case class DeleteDocumentSet(pid: String, user: String) extends Command
  case class FindDocuments(pid: String, user: String, params: Seq[(String, String)], body: JsObject) extends Command
  case class GarbageCollection(pid: String, user: String, request: Option[Any] = None) extends Command

  case class DocumentSetCreated(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DocumentSetReplaced(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DocumentSetPatched(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends DocumentEvent
  case class DocumentSetDeleted(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  object GarbageCollectionCompleted extends DocumentEvent

  object DocumentSetNotFound extends Exception
  object DocumentSetAlreadyExists extends Exception
  object DocumentSetIsCreating extends Exception
  object DocumentSetSoftDeleted extends Exception
  case class PatchDocumentSetException(exception: Throwable) extends Exception

  val idExtractor: ShardRegion.ExtractEntityId = { case cmd: Command => (cmd.pid, cmd) }
  val shardResolver: ShardRegion.ExtractShardId = { case cmd: Command => (math.abs(cmd.pid.hashCode) % 100).toString }
  val shardName: String = "DocumentSet"

  private case class DoCreateDocumentSet(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoGetDocumentSet(user: String, path: String, request: Option[Any] = None)
  private case class DoReplaceDocumentSet(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoPatchDocumentSet(user: String, patch: JsonPatch, request: Option[Any] = None)
  private case class DoDeleteDocumentSet(user: String, request: Option[Any] = None)
  private case class DoFindDocuments(user: String, params: Seq[(String, String)], body: JsObject, request: Option[Any] = None)
  private case class DoGarbageCollection(user: String, request: Option[Any] = None)
  private case class ClearCacheSuccess(user: String, request: Option[Any] = None)
  private case class InitializeIndices(user: String, raw: JsObject, request: Option[Any] = None)

  object JsonProtocol extends DocumentJsonProtocol {
    implicit object DocumentSetFormat extends RootJsonFormat[DocumentSet] {
      def write(ds: DocumentSet) = {
        val metaObj = newMetaObject(ds.raw.getFields("_metadata"), ds.author, ds.revision, ds.created, ds.updated, ds.deleted)
        JsObject(("id" -> JsString(ds.id)) :: ds.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, updated, deleted, jo) = extractFieldsWithUpdatedDeleted(value, "DocumentSet expected!")
        DocumentSet(id, author, revision, created, updated, deleted, jo)
      }
    }

    implicit object DocumentSetCreatedFormat extends RootJsonFormat[DocumentSetCreated] {
      def write(dsc: DocumentSetCreated) = {
        val metaObj = newMetaObject(dsc.raw.getFields("_metadata"), dsc.author, dsc.revision, dsc.created)
        JsObject(("id" -> JsString(dsc.id)) :: dsc.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "DocumentSetCreated event expected!")
        DocumentSetCreated(id, author, revision, created, jo)
      }
    }

    implicit object DocumentSetReplacedFormat extends RootJsonFormat[DocumentSetReplaced] {
      def write(dsr: DocumentSetReplaced) = {
        val metaObj = newMetaObject(dsr.raw.getFields("_metadata"), dsr.author, dsr.revision, dsr.created)
        JsObject(("id" -> JsString(dsr.id)) :: dsr.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "DocumentSetReplaced event expected!")
        DocumentSetReplaced(id, author, revision, created, jo)
      }
    }

    implicit object DocumentSetPatchedFormat extends RootJsonFormat[DocumentSetPatched] {
      def write(dsp: DocumentSetPatched) = {
        val metaObj = newMetaObject(dsp.raw.getFields("_metadata"), dsp.author, dsp.revision, dsp.created)
        JsObject(("id" -> JsString(dsp.id)) :: ("patch" -> marshall(dsp.patch)) :: dsp.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, patch, jo) = extractFieldsWithPatch(value, "DocumentSetPatched event expected!")
        DocumentSetPatched(id, author, revision, created, patch, jo)
      }
    }

    implicit object DocumentSetDeletedFormat extends RootJsonFormat[DocumentSetDeleted] {
      def write(dsd: DocumentSetDeleted) = {
        val metaObj = newMetaObject(dsd.raw.getFields("_metadata"), dsd.author, dsd.revision, dsd.created)
        JsObject(("id" -> JsString(dsd.id)) :: dsd.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "DocumentSetDeleted event expected!")
        DocumentSetDeleted(id, author, revision, created, jo)
      }
    }
  }

  private case class State(documentSet: DocumentSet, deleted: Boolean) {
    def updated(evt: DocumentEvent): State = evt match {
      case DocumentSetCreated(id, author, revision, created, raw) =>
        val metadata = JsObject(raw.fields("_metadata").asJsObject.fields + ("updated" -> JsNumber(created)))
        copy(documentSet = DocumentSet(id, author, revision, created, created, None, JsObject(raw.fields + ("_metadata" -> metadata))))
      case DocumentSetReplaced(_, _, revision, created, raw) =>
        val oldMetaFields = documentSet.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)))
        copy(documentSet = documentSet.copy(revision = revision, updated = created, raw = JsObject(raw.fields + ("_metadata" -> metadata))))
      case DocumentSetPatched(_, _, revision, created, patch, _) =>
        val patchedDoc = patch(documentSet.raw).asJsObject
        val oldMetaFields = patchedDoc.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)))
        copy(documentSet = documentSet.copy(revision = revision, updated = created, raw = JsObject(patchedDoc.fields - "_metadata" + ("_metadata" -> metadata))))
      case DocumentSetDeleted(_, _, revision, created, _) =>
        val oldMetaFields = documentSet.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("deleted" -> JsBoolean(true)))
        copy(documentSet = documentSet.copy(revision = revision, updated = created, deleted = Some(true), raw = JsObject(documentSet.raw.fields + ("_metadata" -> metadata))))
    }

    def updated(ds: DocumentSet): State = ds match {
      case DocumentSet(id, author, revision, created, updated, deleted, raw) => copy(documentSet = DocumentSet(id, author, revision, created, updated, deleted, raw))
    }
  }

  import spray.json._
  def defaultEventIndexTemplate(domainName: String, documentSetName: String): JsObject =
    s"""{
      "index_patterns": ["${domainName}~${documentSetName}_*~events-*"],
      "aliases" : {
        "${domainName}~${documentSetName}~hot~events" : {},
        "${domainName}~${documentSetName}~all~events" : {}
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

  def defaultSnapshotIndexTemplate(domainName: String, documentSetName: String): JsObject =
    s"""{
      "index_patterns": ["${domainName}~${documentSetName}_*~snapshots-*"],
      "aliases" : {
        "${domainName}~${documentSetName}~hot~snapshots" : {},
        "${domainName}~${documentSetName}~all~snapshots" : {}
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

class DocumentSetActor extends PersistentActor with ActorLogging with ACL {
  import DocumentSetActor._
  import DocumentSetActor.JsonProtocol._
  import spray.json._
  import DomainActor._
  import ElasticSearchStore._
  import ACL._

  override def persistenceId: String = self.path.name

  override def journalPluginId = "akka.persistence.elasticsearch.journal"
  override def snapshotPluginId = "akka.persistence.elasticsearch-snapshot-store"

  val system = context.system
  val rootDomain = system.settings.config.getString("domain.root-domain")
  val adminName = system.settings.config.getString("domain.administrator.name")
  val cacheKey = system.settings.config.getString("domain.cache-key")
  val replicator = DistributedData(system).replicator
  val domainRegion = ClusterSharding(system).shardRegion(DomainActor.shardName)

  implicit val cluster = Cluster(system)
  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system))
  implicit val executionContext = context.dispatcher

  private implicit val duration = 5.seconds
  private implicit val timeOut = Timeout(duration)
  private val readMajority = ReadMajority(duration)
  private val writeMajority = WriteMajority(duration)

  // passivate the entity when no activity
  context.setReceiveTimeout(2.minutes)

  private var state = State(DocumentSet.empty, false)
  private val store = ElasticSearchStore(system)

  def domain = persistenceId.split("%7E")(0)
  def id = persistenceId.split("%7E")(2)

  val mediator = DistributedPubSub(system).mediator
  mediator ! Subscribe(domain, self)

  override def receiveRecover: Receive = {
    case evt: DocumentSetCreated =>
      context.become(created)
      state = state.updated(evt)
    case evt: DocumentSetDeleted =>
      context.become(deleted)
      state = state.updated(evt)
    case evt: DocumentEvent =>
      state = state.updated(evt)
    case SnapshotOffer(_, jo: JsObject) =>
      val ds = jo.convertTo[DocumentSet]
      state = state.updated(ds)
      ds.deleted match {
        case Some(true) => context.become(deleted)
        case _          => context.become(created)
      }
    case RecoveryCompleted =>
      log.debug("DocumentSetActor recovery completed.")
  }

  override def receiveCommand: Receive = initial

  def initial: Receive = {
    case cds @ CreateDocumentSet(_, user, raw) =>
      val replyTo = sender
      val parent = context.parent
      Source.fromFuture(domainRegion ? CheckPermission(s"${rootDomain}~.domains~${domain}", user, cds)).runWith(Sink.head[Any]).foreach {
        case Granted => self ! InitializeIndices(user, raw, Some(replyTo))
        case Denied =>
          replyTo ! Denied
          parent ! Passivate(stopMessage = PoisonPill)
      }
      context.become(creating)
    case _: Command => sender ! DocumentSetNotFound
  }

  def creating: Receive = {
    case InitializeIndices(user, raw, Some(replyTo: ActorRef)) =>
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
      initIndices(templates).foreach { Done => self ! DoCreateDocumentSet(user, newRaw, Some(replyTo)) }
    case DoCreateDocumentSet(user, raw, Some(replyTo: ActorRef)) =>
      persist(DocumentSetCreated(id, user, lastSequenceNr + 1, System.currentTimeMillis, JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> DocumentSetActor.defaultACL(user)))))) { evt =>
        state = state.updated(evt)
        val ds = state.documentSet.toJson.asJsObject
        saveSnapshot(ds)
        context.become(created)
        updateCache.foreach { Done => replyTo ! evt.copy(raw = ds) }
      }
    case _: Command => sender ! DocumentSetIsCreating
  }

  def created: Receive = {
    case gds @ GetDocumentSet(_, user, path) =>
      val replyTo = sender
      checkPermission(user, gds).foreach {
        case Granted => self ! DoGetDocumentSet(user, path, Some(replyTo))
        case Denied  => replyTo ! Denied
      }
    case DoGetDocumentSet(user, path, Some(replyTo: ActorRef)) =>
      store.indices(s"${domain}~${id}_*").foreach {
        case (StatusCodes.OK, jv) =>
          val jo = state.documentSet.toJson.asJsObject()
          replyTo ! getJson(JsObject(jo.fields + ("indices" -> jv)), path.split("/"))
        case (code, _) => throw new RuntimeException(s"Error get indices:$code")
      }
    case rds @ ReplaceDocumentSet(_, user, raw) =>
      val replyTo = sender
      checkPermission(user, rds).foreach {
        case Granted => self ! DoReplaceDocumentSet(user, raw, Some(replyTo: ActorRef))
        case Denied  => replyTo ! Denied
      }
    case DoReplaceDocumentSet(user, raw, Some(replyTo: ActorRef)) =>
      persist(DocumentSetReplaced(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        state = state.updated(evt)
        val ds = state.documentSet.toJson.asJsObject
        saveSnapshot(ds)
        deleteSnapshot(lastSequenceNr - 1)
        replyTo ! evt.copy(raw = ds)
      }
    case pds @ PatchDocumentSet(_, user, patch) =>
      val replyTo = sender
      checkPermission(user, pds).foreach {
        case Granted => self ! DoPatchDocumentSet(user, patch, Some(replyTo))
        case Denied  => replyTo ! Denied
      }
    case DoPatchDocumentSet(user, patch, Some(replyTo: ActorRef)) =>
      Try {
        patch(state.documentSet.raw)
      } match {
        case Success(result) =>
          persist(DocumentSetPatched(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, JsObject())) { evt =>
            state = state.updated(evt)
            val ds = state.documentSet.toJson.asJsObject
            saveSnapshot(ds)
            deleteSnapshot(lastSequenceNr - 1)
            replyTo ! evt.copy(raw = ds)
          }
        case Failure(e) => replyTo ! PatchDocumentSetException(e)
      }
    case dds @ DeleteDocumentSet(_, user) =>
      val replyTo = sender
      checkPermission(user, dds).foreach {
        case Granted => clearCache.foreach { Done => self ! DoDeleteDocumentSet(user, Some(replyTo)) }
        case Denied  => replyTo ! Denied
      }
    case DoDeleteDocumentSet(user, Some(replyTo: ActorRef)) =>
      persist(DocumentSetDeleted(id, user, lastSequenceNr + 1, System.currentTimeMillis(), JsObject())) { evt =>
        state = state.updated(evt)
        deleteMessages(lastSequenceNr)
        deleteSnapshot(lastSequenceNr - 1)
        val ds = state.documentSet.toJson.asJsObject
        saveSnapshot(ds)
        context.become(deleted)
        replyTo ! evt.copy(raw = ds)
        mediator ! Publish(s"${domain}~${id}", evt.copy(raw = ds))
        context.parent ! Passivate(stopMessage = PoisonPill)
      }
    case SaveSnapshotSuccess(metadata)         =>
    case SaveSnapshotFailure(metadata, reason) =>
    case fd @ FindDocuments(_, user, params, body) =>
      val replyTo = sender
      checkPermission(user, fd).foreach {
        case Granted => self ! DoFindDocuments(user, params, body, Some(replyTo))
        case Denied  => replyTo ! Denied
      }
    case DoFindDocuments(user, params, body, Some(replyTo: ActorRef)) =>
      store.search(s"${domain}~${id}~all~snapshots", params, body.compactPrint).foreach {
        case (StatusCodes.OK, jo: JsObject) =>
          val fields = jo.fields
          val hitsFields = jo.fields("hits").asJsObject.fields
          replyTo ! JsObject(hitsFields + ("_metadata" -> JsObject((fields - "hits"))))
        case (code, jv) => throw new RuntimeException(s"Find documents error: $jv")
      }
    case gc @ GarbageCollection(_, user, _) =>
      val replyTo = sender
      checkPermission(user, gc).foreach {
        case Granted => self ! DoGarbageCollection(user, Some(replyTo))
        case Denied  => replyTo ! Denied
      }
    case DoGarbageCollection(user, Some(replyTo: ActorRef)) =>
      garbageCollection.foreach { Done => replyTo ! GarbageCollectionCompleted }
    case cds @ CreateDocumentSet(_, _, _) => sender ! DocumentSetAlreadyExists
    case CheckPermission(_, user, command) =>
      val replyTo = sender
      id match {
        case "users" | "roles" | "profiles" => replyTo ! Granted
        case _                              => checkPermission(user, command).foreach { replyTo ! _ }
      }
    case _: DomainDeleted => context.parent ! Passivate(stopMessage = PoisonPill)
  }

  def deleted: Receive = {
    case gds @ GetDocumentSet(_, user, path) =>
      val replyTo = sender
      checkPermission(user, gds).foreach {
        case Granted => self ! DoGetDocumentSet(user, path, Some(replyTo))
        case Denied  => replyTo ! Denied
      }
    case DoGetDocumentSet(_, _, Some(replyTo: ActorRef)) => replyTo ! state.documentSet
    case _: Command                                      => sender ! DocumentSetSoftDeleted
  }

  override def unhandled(msg: Any): Unit = msg match {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
    case _              => super.unhandled(msg)
  }

  private def checkPermission(user: String, command: Command) = fetchProfile(domain, user).map {
    case profile: Document =>
      val aclObj = state.documentSet.raw.fields("_metadata").asJsObject.fields("acl").asJsObject
      val userRoles = profileValue(profile.raw, "roles")
      val userGroups = profileValue(profile.raw, "groups")
      val (aclRoles, aclGroups, aclUsers) = command match {
        case _: GetDocumentSet     => (aclValue(aclObj, "get", "roles"), aclValue(aclObj, "get", "groups"), aclValue(aclObj, "get", "users"))
        case _: ReplaceDocumentSet => (aclValue(aclObj, "replace", "roles"), aclValue(aclObj, "replace", "groups"), aclValue(aclObj, "replace", "users"))
        case _: PatchDocumentSet   => (aclValue(aclObj, "patch", "roles"), aclValue(aclObj, "patch", "groups"), aclValue(aclObj, "patch", "users"))
        case _: DeleteDocumentSet  => (aclValue(aclObj, "delete", "roles"), aclValue(aclObj, "delete", "groups"), aclValue(aclObj, "delete", "users"))
        case _: CreateDocument     => (aclValue(aclObj, "create_document", "roles"), aclValue(aclObj, "create_document", "groups"), aclValue(aclObj, "create_document", "users"))
        case _: FindDocuments      => (aclValue(aclObj, "find_documents", "roles"), aclValue(aclObj, "find_documents", "groups"), aclValue(aclObj, "find_documents", "users"))
        case _: GarbageCollection  => (aclValue(aclObj, "gc", "roles"), aclValue(aclObj, "gc", "groups"), aclValue(aclObj, "gc", "users"))
        case _                     => (Vector[String](), Vector[String](), Vector[String]())
      }
      if (aclRoles.intersect(userRoles).isEmpty && aclGroups.intersect(userGroups).isEmpty && !aclUsers.contains(user)) Denied else Granted
    case _ => Denied
  }

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

  private def checkCache: Future[Boolean] = {
    val key = s"${domain}~${id}"
    Source.fromFuture {
      replicator ? Get(LWWMapKey[String, Any](cacheKey), readMajority)
    }.map {
      case g @ GetSuccess(LWWMapKey(_), _) =>
        g.dataValue match {
          case data: LWWMap[_, _] => data.asInstanceOf[LWWMap[String, Any]].get(key) match {
            case Some(_) => true
            case None    => false
          }
        }
      case NotFound(_, _) => false
    }.runWith(Sink.last[Boolean])
  }

  private def updateCache: Future[Done] = {
    val key = s"${domain}~${id}"
    Source.fromFuture {
      replicator ? Update(LWWMapKey[String, Any](cacheKey), LWWMap(), writeMajority)(_ + (key -> state.documentSet))
    }.map {
      case UpdateSuccess(LWWMapKey(_), _) => Done
      case _: UpdateResponse[_]           =>
    }.runWith(Sink.ignore)
  }

  private def clearCache: Future[Done] = {
    val key = s"${domain}~${id}"
    Source.fromFuture {
      replicator ? Update(LWWMapKey[String, Any](cacheKey), LWWMap(), writeMajority)(_ - key)
    }.map {
      case UpdateSuccess(LWWMapKey(_), _) => Done
      case _: UpdateResponse[_]           =>
    }.runWith(Sink.ignore)
  }

  private def garbageCollection: Future[Done] = {
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
      case (StatusCodes.OK, jo: JsObject) => Done
      case (_, jv)                        => throw new RuntimeException(jv.compactPrint)
    }
  }

}
