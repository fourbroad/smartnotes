package com.zxtx.actors

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.gilt.handlebars.scala.Handlebars
import com.gilt.handlebars.scala.binding.sprayjson._

import com.zxtx.persistence._

import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.cluster.ddata.Replicator.ReadMajority
import akka.cluster.ddata.Replicator.WriteMajority
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.Passivate
import akka.http.scaladsl.model.StatusCodes
import akka.pattern.ask
import akka.persistence.PersistentActor
import akka.persistence.RecoveryCompleted
import akka.persistence.SaveSnapshotFailure
import akka.persistence.SaveSnapshotSuccess
import akka.persistence.SnapshotOffer
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import spray.json._
import gnieh.diffson.sprayJson.JsonPatch

object DocumentActor {

  def props(): Props = Props[DocumentActor]

  object Document {
    val empty = new Document("", "", 0L, 0L, 0L, None, spray.json.JsObject())
  }
  case class Document(id: String, author: String = "anonymous", revision: Long, created: Long, updated: Long, deleted: Option[Boolean], raw: JsObject)

  case class GetDocument(pid: String, user: String) extends Command
  case class ExecuteDocument(pid: String, user: String, params: Seq[(String, String)], body: JsObject) extends Command
  case class CreateDocument(pid: String, user: String, raw: JsObject, initFlag: Option[Boolean] = None) extends Command
  case class ReplaceDocument(pid: String, user: String, raw: JsObject) extends Command
  case class PatchDocument(pid: String, user: String, patch: JsonPatch) extends Command
  case class DeleteDocument(pid: String, user: String) extends Command

  case class DocumentCreated(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DocumentReplaced(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DocumentPatched(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends DocumentEvent
  case class DocumentDeleted(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent

  case object DocumentNotFound extends Exception
  case object DocumentAlreadyExists extends Exception
  case object DocumentIsCreating extends Exception
  case object DocumentSoftDeleted extends Exception
  case class ExecuteDocumentException(exception: Throwable) extends Exception
  case class PatchDocumentException(exception: Throwable) extends Exception

  val idExtractor: ShardRegion.ExtractEntityId = { case cmd: Command => (cmd.pid, cmd) }
  val shardResolver: ShardRegion.ExtractShardId = { case cmd: Command => (math.abs(cmd.pid.hashCode) % 100).toString }
  val shardName: String = "Document"

  def persistenceId(domain: String, collection: String, docId: String) = s"${domain}~${collection}~${docId}"

  private case class DoGetDocument(user: String, request: Option[Any] = None)
  private case class DoExecuteDocument(user: String, params: Seq[(String, String)], body: JsObject, request: Option[Any] = None)
  private case class DoCreateDocument(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoReplaceDocument(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoPatchDocument(user: String, patch: JsonPatch, request: Option[Any] = None)
  private case class DoDeleteDocument(user: String, request: Option[Any] = None)

  object JsonProtocol extends DocumentJsonProtocol {
    implicit object DocumentFormat extends RootJsonFormat[Document] {
      def write(doc: Document) = {
        val metaObj = newMetaObject(doc.raw.getFields("_metadata"), doc.author, doc.revision, doc.created, doc.updated, doc.deleted)
        JsObject(("id" -> JsString(doc.id)) :: doc.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, updated, deleted, raw) = extractFieldsWithUpdatedDeleted(value, "Document expected!")
        Document(id, author, revision, created, updated, deleted, raw)
      }
    }

    implicit object DocumentCreatedFormat extends RootJsonFormat[DocumentCreated] {
      def write(dc: DocumentCreated) = {
        val metaObj = newMetaObject(dc.raw.getFields("_metadata"), dc.author, dc.revision, dc.created)
        JsObject(("id" -> JsString(dc.id)) :: dc.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, raw) = extractFields(value, "DocumentCreated event expected!")
        DocumentCreated(id, author, revision, created, raw)
      }
    }

    implicit object DocumentReplacedFormat extends RootJsonFormat[DocumentReplaced] {
      def write(dr: DocumentReplaced) = {
        val metaObj = newMetaObject(dr.raw.getFields("_metadata"), dr.author, dr.revision, dr.created)
        JsObject(("id" -> JsString(dr.id)) :: dr.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, raw) = extractFields(value, "DocumentReplaced event expected!")
        DocumentReplaced(id, author, revision, created, raw)
      }
    }

    implicit object DocumentPatchedFormat extends RootJsonFormat[DocumentPatched] {
      import gnieh.diffson.sprayJson.provider._
      import gnieh.diffson.sprayJson.provider.marshall

      def write(dp: DocumentPatched) = {
        val metaObj = newMetaObject(dp.raw.getFields("_metadata"), dp.author, dp.revision, dp.created)
        spray.json.JsObject(("id" -> JsString(dp.id)) :: ("patch" -> marshall(dp.patch)) :: dp.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, patch, raw) = extractFieldsWithPatch(value, "DocumentPatched event expected!")
        DocumentPatched(id, author, revision, created, patch, raw)
      }
    }

    implicit object DocumentDeletedFormat extends RootJsonFormat[DocumentDeleted] {
      def write(dd: DocumentDeleted) = {
        val metaObj = newMetaObject(dd.raw.getFields("_metadata"), dd.author, dd.revision, dd.created)
        JsObject(("id" -> JsString(dd.id)) :: dd.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, raw) = extractFields(value, "DocumentDeleted event expected!")
        DocumentDeleted(id, author, revision, created, raw)
      }
    }
  }

  def acl(user: String) = s"""{
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
        "execute":{
            "roles":["administrator","user"],
            "users":["${user}"]
        }
      }""".parseJson.asJsObject

  private case class State(document: Document) {
    def updated(evt: DocumentEvent): State = evt match {
      case DocumentCreated(id, author, revision, created, raw) =>
        val metadata = JsObject(raw.fields("_metadata").asJsObject.fields + ("updated" -> JsNumber(created)))
        copy(document = Document(id, author, revision, created, created, None, JsObject(raw.fields + ("_metadata" -> metadata))))
      case DocumentReplaced(_, _, revision, created, raw) =>
        val oldMetaFields = document.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)))
        copy(document = document.copy(revision = revision, updated = created, raw = JsObject(raw.fields + ("_metadata" -> metadata))))
      case DocumentPatched(_, _, revision, created, patch, _) =>
        val patchedDoc = patch(document.raw).asJsObject
        val oldMetaFields = patchedDoc.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)))
        copy(document = document.copy(revision = revision, updated = created, raw = JsObject((patchedDoc.fields - "_metadata") + ("_metadata" -> metadata))))
      case DocumentDeleted(_, _, revision, created, _) =>
        val oldMetaFields = document.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("deleted" -> JsBoolean(true)))
        copy(document = document.copy(revision = revision, updated = created, deleted = Some(true), raw = JsObject(document.raw.fields + ("_metadata" -> metadata))))
    }

    def updated(doc: Document): State = doc match {
      case Document(id, author, revision, created, updated, deleted, raw) => copy(document = Document(id, author, revision, created, updated, deleted, raw))
    }
  }
}

class DocumentActor extends PersistentActor with ACL with ActorLogging {
  import ACL._
  import CollectionActor._
  import DocumentActor._
  import DocumentActor.JsonProtocol._
  import DomainActor._
  import com.zxtx.persistence.ElasticSearchStore._

  // self.path.parent.name is the type name (utf-8 URL-encoded)
  // self.path.name is the entry identifier (utf-8 URL-encoded)
  override def persistenceId: String = self.path.name

  override def journalPluginId = "akka.persistence.elasticsearch.journal"
  override def snapshotPluginId = "akka.persistence.elasticsearch-snapshot-store"

  val collectionRegion = ClusterSharding(system).shardRegion(CollectionActor.shardName)

  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system))

  private val readMajority = ReadMajority(duration)
  private val writeMajority = WriteMajority(duration)

  // passivate the entity when no activity
  context.setReceiveTimeout(2.minutes)

  private def domain = persistenceId.split("%7E")(0)
  private def collection = persistenceId.split("%7E")(1)
  private def id = persistenceId.split("%7E")(2)

  val mediator = DistributedPubSub(system).mediator
  mediator ! Subscribe(domain, self)
  mediator ! Subscribe(s"${domain}~${collection}", self)

  private var state = State(Document.empty)
  private val store = ElasticSearchStore(system)

  override def receiveRecover: Receive = {
    case evt: DocumentCreated =>
      state = state.updated(evt)
      context.become(created)
    case evt: DocumentDeleted =>
      state = state.updated(evt)
      context.become(deleted)
    case evt: DocumentEvent =>
      state = state.updated(evt)
    case SnapshotOffer(_, jo: JsObject) =>
      val doc = jo.convertTo[Document]
      state = state.updated(doc)
      doc.deleted match {
        case Some(true) => context.become(deleted)
        case _          => context.become(created)
      }
    case RecoveryCompleted =>
      log.debug("DocumentActor recovery completed.")
  }

  override def receiveCommand: Receive = initial

  def initial: Receive = {
    case CreateDocument(_, user, raw, initFlag) =>
      val replyTo = sender
      val parent = context.parent
      createDocument(user, raw, initFlag).foreach {
        case dcd: DoCreateDocument => self ! dcd.copy(request = Some(replyTo))
        case other =>
          replyTo ! other
          parent ! Passivate(stopMessage = PoisonPill)
      }
      context.become(creating)
    case _: Command => sender ! DocumentNotFound
  }

  def creating: Receive = {
    case DoCreateDocument(user, raw, Some(replyTo: ActorRef)) =>
      persist(DocumentCreated(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        state = state.updated(evt)
        val doc = state.document.toJson.asJsObject
        saveSnapshot(doc)
        context.become(created)
        replyTo ! evt.copy(raw = doc)
      }
    case _: Command => sender ! DocumentIsCreating
  }

  def created: Receive = {
    case GetDocument(_, user) =>
      val replyTo = sender
      getDocument(user).foreach {
        case dgd: DoGetDocument => replyTo ! state.document
        case other              => replyTo ! other
      }
    case ReplaceDocument(_, user, raw) =>
      val replyTo = sender
      replaceDocument(user, raw).foreach {
        case drd: DoReplaceDocument => self ! drd.copy(request = Some(replyTo))
        case other                  => replyTo ! other
      }
    case DoReplaceDocument(user, raw, Some(replyTo: ActorRef)) =>
      persist(DocumentReplaced(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        state = state.updated(evt)
        val doc = state.document.toJson.asJsObject
        saveSnapshot(doc)
        deleteSnapshot(lastSequenceNr - 1)
        replyTo ! evt.copy(raw = doc)
      }
    case PatchDocument(_, user, patch) =>
      val replyTo = sender
      patchDocument(user, patch).foreach {
        case dpd: DoPatchDocument => self ! dpd.copy(request = Some(replyTo))
        case other                => replyTo ! other
      }
    case DoPatchDocument(user, patch, Some(replyTo: ActorRef)) =>
      persist(DocumentPatched(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, JsObject())) { evt =>
        state = state.updated(evt)
        val doc = state.document.toJson.asJsObject
        saveSnapshot(doc)
        deleteSnapshot(lastSequenceNr - 1)
        replyTo ! evt.copy(raw = doc)
      }
    case DeleteDocument(_, user) =>
      val replyTo = sender
      deleteDocument(user).foreach {
        case ddd: DoDeleteDocument => self ! ddd.copy(request = Some(replyTo))
        case other                 => replyTo ! other
      }
    case DoDeleteDocument(user, Some(replyTo: ActorRef)) =>
      persist(DocumentDeleted(id, user, lastSequenceNr + 1, System.currentTimeMillis(), JsObject())) { evt =>
        state = state.updated(evt)
        deleteMessages(lastSequenceNr)
        deleteSnapshot(lastSequenceNr - 1)
        val doc = state.document.toJson.asJsObject
        saveSnapshot(doc)
        context.become(deleted)
        replyTo ! evt.copy(raw = doc)
        context.parent ! Passivate(stopMessage = PoisonPill)
      }
    case ExecuteDocument(_, user, params, body) =>
      val replyTo = sender
      executeDocument(user, params, body).foreach {
        case DoExecuteDocument(_, _, _, Some(result)) => self ! result
        case other                                    => replyTo ! other
      }
    case SaveSnapshotSuccess(metadata)           =>
    case SaveSnapshotFailure(metadata, reason)   =>
    case _: CreateDocument                       => sender ! DocumentAlreadyExists
    case _: DomainDeleted | _: CollectionDeleted => context.parent ! Passivate(stopMessage = PoisonPill)
  }

  def deleted: Receive = {
    case GetDocument(_, user) =>
      val replyTo = sender
      val parent = context.parent
      getDocument(user).foreach {
        case dgd: DoGetDocument =>
          self ! state.document
          parent ! Passivate(stopMessage = PoisonPill)
        case other =>
          replyTo ! other
          parent ! Passivate(stopMessage = PoisonPill)
      }
    case _: Command => sender ! DocumentSoftDeleted
  }

  override def unhandled(msg: Any): Unit = msg match {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
    case _              => super.unhandled(msg)
  }

  private def createDocument(user: String, raw: JsObject, initFlag: Option[Boolean]) = {
    val dcd = DoCreateDocument(user, JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> acl(user)))))
    initFlag match {
      case Some(true) => Future.successful(dcd)
      case other => (collectionRegion ? CheckPermission(s"${domain}~.collections~${collection}", user, CreateDocument)).map {
        case Granted => dcd
        case Denied  => Denied
      }
    }
  }

  private def getDocument(user: String) = checkPermission(user, GetDocument).map {
    case Granted => DoGetDocument(user)
    case other   => other
  }.recover { case e => e }

  private def replaceDocument(user: String, raw: JsObject) = checkPermission(user, ReplaceDocument).map {
    case Granted => DoReplaceDocument(user, raw)
    case other   => other
  }.recover { case e => e }

  private def patchDocument(user: String, patch: JsonPatch) = checkPermission(user, PatchDocument).map {
    case Granted =>
      Try {
        patch(state.document.raw)
      } match {
        case Success(_) => DoPatchDocument(user, patch)
        case Failure(e) => PatchDocumentException(e)
      }
    case other => other
  }.recover { case e => e }

  private def deleteDocument(user: String) = checkPermission(user, DeleteDocument).map {
    case Granted => DoDeleteDocument(user)
    case other   => other
  }.recover { case e => e }

  private def executeDocument(user: String, params: Seq[(String, String)], body: JsObject) = checkPermission(user, ExecuteDocument).flatMap {
    case Granted =>
      val domainId = persistenceId.split("%7E")(0)
      val raw = state.document.raw
      val collection = raw.fields("targets").asInstanceOf[JsArray].elements(0).asInstanceOf[JsString].value
      val search = raw.fields("search")
      val hb = Handlebars(search.prettyPrint)
      val jo = JsObject(params.map(t => (t._1, JsString(t._2))): _*)
      store.search(s"${domainId}~${collection}~all~snapshots", params, hb(jo)).map {
        case (StatusCodes.OK, jo: JsObject) =>
          val fields = jo.fields
          val hitsFields = jo.fields("hits").asJsObject.fields
          DoExecuteDocument(user, params, body, Some(JsObject(hitsFields + ("_metadata" -> JsObject((fields - "hits"))))))
        case (code, jv) => throw new RuntimeException(s"Find documents error: $jv")
      }
    case other => Future.successful(other)
  }.recover { case e => e }

  override def checkPermission(user: String, command: Any): Future[Permission] = (collection, id) match {
    case ("profiles", `user`) => Future.successful(Granted)
    case _ => fetchProfile(domain, user).map {
      case profile: Document =>
        val aclObj = state.document.raw.fields("_metadata").asJsObject.fields("acl").asJsObject
        val userRoles = profileValue(profile.raw, "roles")
        val userGroups = profileValue(profile.raw, "groups")
        val (aclRoles, aclGroups, aclUsers) = command match {
          case GetDocument     => (aclValue(aclObj, "get", "roles"), aclValue(aclObj, "get", "groups"), aclValue(aclObj, "get", "users"))
          case ReplaceDocument => (aclValue(aclObj, "replace", "roles"), aclValue(aclObj, "replace", "groups"), aclValue(aclObj, "replace", "users"))
          case PatchDocument   => (aclValue(aclObj, "patch", "roles"), aclValue(aclObj, "patch", "groups"), aclValue(aclObj, "patch", "users"))
          case DeleteDocument  => (aclValue(aclObj, "delete", "roles"), aclValue(aclObj, "delete", "groups"), aclValue(aclObj, "delete", "users"))
          case ExecuteDocument => (aclValue(aclObj, "execute", "roles"), aclValue(aclObj, "execute", "groups"), aclValue(aclObj, "execute", "users"))
          case _               => (Vector[String](), Vector[String](), Vector[String]())
        }
        if (aclRoles.intersect(userRoles).isEmpty && aclGroups.intersect(userGroups).isEmpty && !aclUsers.contains(user)) Denied else Granted
      case _ => Denied
    }
  }

}
