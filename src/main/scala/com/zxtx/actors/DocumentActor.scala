package com.zxtx.actors

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.gilt.handlebars.scala.Handlebars
import com.gilt.handlebars.scala.binding.sprayjson._

import com.zxtx.persistence._
import com.zxtx.actors.ACL._

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
import akka.persistence.SnapshotSelectionCriteria

object DocumentActor {

  def props(): Props = Props[DocumentActor]

  object Document {
    val empty = new Document("", "", 0L, 0L, 0L, None, spray.json.JsObject())
  }
  case class Document(id: String, author: String = "anonymous", revision: Long, created: Long, updated: Long, removed: Option[Boolean], raw: JsObject)

  case class GetDocument(pid: String, user: String) extends Command
  case class ExecuteDocument(pid: String, user: String, params: Seq[(String, String)], body: JsObject) extends Command
  case class CreateDocument(pid: String, user: String, raw: JsObject, initFlag: Option[Boolean] = None) extends Command
  case class ReplaceDocument(pid: String, user: String, raw: JsObject) extends Command
  case class PatchDocument(pid: String, user: String, patch: JsonPatch) extends Command
  case class RemoveDocument(pid: String, user: String) extends Command

  private case class DoGetDocument(user: String, request: Option[Any] = None)
  private case class DoExecuteDocument(user: String, params: Seq[(String, String)], body: JsObject, request: Option[Any] = None)
  private case class DoCreateDocument(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoReplaceDocument(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoPatchDocument(user: String, patch: JsonPatch, raw: JsObject, request: Option[Any] = None)
  private case class DoRemoveDocument(user: String, raw: JsObject, request: Option[Any] = None)

  case class DocumentCreated(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case class DocumentReplaced(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case class DocumentPatched(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends Event
  case class DocumentRemoved(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event

  case object DocumentNotFound extends Exception
  case object DocumentAlreadyExists extends Exception
  case object DocumentIsCreating extends Exception
  case object DocumentSoftRemoved extends Exception
  case class ExecuteDocumentException(exception: Throwable) extends Exception
  case class PatchDocumentException(exception: Throwable) extends Exception

  val idExtractor: ShardRegion.ExtractEntityId = { case cmd: Command => (cmd.pid, cmd) }
  val shardResolver: ShardRegion.ExtractShardId = { case cmd: Command => (math.abs(cmd.pid.hashCode) % 100).toString }
  val shardName: String = "Documents"

  def persistenceId(domain: String, collection: String, docId: String) = s"${domain}~${collection}~${docId}"

  object JsonProtocol extends DocumentJsonProtocol {
    implicit object DocumentFormat extends RootJsonFormat[Document] {
      def write(doc: Document) = {
        val metaObj = newMetaObject(doc.raw.getFields("_metadata"), doc.author, doc.revision, doc.created, doc.updated, doc.removed)
        JsObject(("id" -> JsString(doc.id)) :: doc.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, updated, removed, raw) = extractFieldsWithUpdatedRemoved(value, "Document expected!")
        Document(id, author, revision, created, updated, removed, raw)
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

    implicit object DocumentRemovedFormat extends RootJsonFormat[DocumentRemoved] {
      def write(dd: DocumentRemoved) = {
        val metaObj = newMetaObject(dd.raw.getFields("_metadata"), dd.author, dd.revision, dd.created)
        JsObject(("id" -> JsString(dd.id)) :: dd.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, raw) = extractFields(value, "DocumentRemoved event expected!")
        DocumentRemoved(id, author, revision, created, raw)
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
        "remove":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "execute":{
            "roles":["administrator","user"],
            "users":["${user}"]
        },
        "getACL":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "replaceACL":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "patchACL":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "patchEventACL":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "removePermissionSubject":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "removeEventPermissionSubject":{
            "roles":["administrator"],
            "users":["${user}"]
        }
      }""".parseJson.asJsObject

  private case class State(document: Document) {
    def updated(evt: Event): State = evt match {
      case DocumentCreated(id, author, revision, created, raw) =>
        val metadata = JsObject("author" -> JsString(author), "revision" -> JsNumber(revision), "created" -> JsNumber(created), "updated" -> JsNumber(created), "acl" -> acl(author))
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
      case DocumentRemoved(_, _, revision, created, _) =>
        val oldMetaFields = document.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("removed" -> JsBoolean(true)))
        copy(document = document.copy(revision = revision, updated = created, removed = Some(true), raw = JsObject(document.raw.fields + ("_metadata" -> metadata))))
      case ACLReplaced(_, _, revision, created, raw) =>
        val oldMetadata = document.raw.fields("_metadata").asJsObject
        val replacedACL = JsObject(raw.fields - "_metadata" - "id")
        val metadata = JsObject(oldMetadata.fields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("acl" -> replacedACL))
        copy(document = document.copy(revision = revision, updated = created, raw = JsObject(document.raw.fields + ("_metadata" -> metadata))))
      case ACLPatched(_, _, revision, created, patch, _) =>
        val oldMetadata = document.raw.fields("_metadata").asJsObject
        val patchedACL = patch(oldMetadata.fields("acl"))
        val metadata = JsObject(oldMetadata.fields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("acl" -> patchedACL))
        copy(document = document.copy(revision = revision, updated = created, raw = JsObject(document.raw.fields + ("_metadata" -> metadata))))
      case PermissionSubjectRemoved(_, _, revision, created, raw) =>
        val oldMetadata = document.raw.fields("_metadata").asJsObject
        val operation = raw.fields("operation").asInstanceOf[JsString].value
        val kind = raw.fields("kind").asInstanceOf[JsString].value
        val subject = raw.fields("subject").asInstanceOf[JsString].value
        val acl = doRemovePermissionSubject(oldMetadata.fields("acl").asJsObject, operation, kind, subject)
        val metadata = JsObject(oldMetadata.fields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("acl" -> acl))
        copy(document = document.copy(revision = revision, updated = created, raw = JsObject(document.raw.fields + ("_metadata" -> metadata))))
      case _ => copy(document = document)
    }

    def updated(doc: Document): State = doc match {
      case Document(id, author, revision, created, updated, removed, raw) => copy(document = Document(id, author, revision, created, updated, removed, raw))
    }
  }
}

class DocumentActor extends PersistentActor with ACL with ActorLogging {
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

  override def receiveRecover: Receive = {
    case evt: DocumentCreated =>
      state = state.updated(evt)
      context.become(created)
    case evt: DocumentRemoved =>
      state = state.updated(evt)
      context.become(removed)
    case evt: Event =>
      state = state.updated(evt)
    case SnapshotOffer(_, jo: JsObject) =>
      val doc = jo.convertTo[Document]
      state = state.updated(doc)
      doc.removed match {
        case Some(true) => context.become(removed)
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
        replyTo ! state.document
      }
    case _: Command => sender ! DocumentIsCreating
  }

  def created: Receive = {
    case GetDocument(pid, user) =>
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
        replyTo ! updateAndSave(evt)
      }
    case PatchDocument(_, user, patch) =>
      val replyTo = sender
      patchDocument(user, patch).foreach {
        case dpd: DoPatchDocument => self ! dpd.copy(request = Some(replyTo))
        case other                => replyTo ! other
      }
    case DoPatchDocument(user, patch, raw, Some(replyTo: ActorRef)) =>
      persist(DocumentPatched(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, raw)) { evt =>
        replyTo ! updateAndSave(evt)
      }
    case RemoveDocument(_, user) =>
      val replyTo = sender
      removeDocument(user).foreach {
        case ddd: DoRemoveDocument => self ! ddd.copy(request = Some(replyTo))
        case other                 => replyTo ! other
      }
    case DoRemoveDocument(user, raw, Some(replyTo: ActorRef)) =>
      persist(DocumentRemoved(id, user, lastSequenceNr + 1, System.currentTimeMillis(), raw)) { evt =>
        state = state.updated(evt)
        deleteMessages(lastSequenceNr)
        deleteSnapshots(SnapshotSelectionCriteria.Latest)        
        val doc = state.document.toJson.asJsObject
        saveSnapshot(doc)
        context.become(removed)
        replyTo ! evt.copy(raw = doc)
        context.parent ! Passivate(stopMessage = PoisonPill)
      }
    case GetACL(_, user) =>
      val replyTo = sender
      getACL(user).foreach {
        case dga: DoGetACL => replyTo ! state.document.raw.fields("_metadata").asJsObject.fields("acl")
        case other         => replyTo ! other
      }
    case ReplaceACL(_, user, raw) =>
      val replyTo = sender
      replaceACL(user, raw).foreach {
        case dra: DoReplaceACL => self ! dra.copy(request = Some(replyTo))
        case other             => replyTo ! other
      }
    case DoReplaceACL(user, raw, Some(replyTo: ActorRef)) =>
      persist(ACLReplaced(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        replyTo ! evt
      }
    case PatchACL(_, user, patch) =>
      val replyTo = sender
      patchACL(user, state.document.raw.fields("_metadata").asJsObject.fields("acl"), patch).foreach {
        case dsa: DoPatchACL => self ! dsa.copy(request = Some(replyTo))
        case other           => replyTo ! other
      }
    case DoPatchACL(user, patch, raw, Some(replyTo: ActorRef)) =>
      persist(ACLPatched(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, raw)) { evt =>
        updateAndSave(evt)
        replyTo ! evt
      }
    case PatchEventACL(pid, user, patch) =>
      val replyTo = sender
      setEventACL(user, pid, patch).foreach {
        case dsea: DoPatchEventACL => self ! dsea.copy(request = Some(replyTo))
        case other                 => replyTo ! other
      }
    case DoPatchEventACL(user, patch, raw, Some(replyTo: ActorRef)) =>
      persist(EventACLPatched(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, raw)) { evt =>
        replyTo ! evt
      }
    case RemovePermissionSubject(pid, user, operation, kind, subject) =>
      val replyTo = sender
      removePermissionSubject(user, operation, kind, subject).foreach {
        case drps: DoRemovePermissionSubject => self ! drps.copy(request = Some(replyTo))
        case other                           => replyTo ! other
      }
    case DoRemovePermissionSubject(user, operation, kind, subject, raw, Some(replyTo: ActorRef)) =>
      persist(PermissionSubjectRemoved(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        updateAndSave(evt)
        replyTo ! evt
      }
    case RemoveEventPermissionSubject(pid, user, operation, kind, subject) =>
      val replyTo = sender
      removeEventPermissionSubject(user, pid, operation, kind, subject).foreach {
        case dreps: DoRemoveEventPermissionSubject => self ! dreps.copy(request = Some(replyTo))
        case other                                 => replyTo ! other
      }
    case DoRemoveEventPermissionSubject(user, operation, kind, subject, raw, Some(replyTo: ActorRef)) =>
      persist(EventPermissionSubjectRemoved(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        replyTo ! evt
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
    case _: DomainRemoved | _: CollectionRemoved => context.parent ! Passivate(stopMessage = PoisonPill)
  }

  def removed: Receive = {
    case GetDocument(_, user) =>
      val replyTo = sender
      val parent = context.parent
      getDocument(user).foreach {
        case dgd: DoGetDocument =>
          replyTo ! state.document
          parent ! Passivate(stopMessage = PoisonPill)
        case other =>
          replyTo ! other
          parent ! Passivate(stopMessage = PoisonPill)
      }
    case _: Command =>
      sender ! DocumentSoftRemoved
      context.parent ! Passivate(stopMessage = PoisonPill)
  }

  override def unhandled(msg: Any): Unit = msg match {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
    case _              => super.unhandled(msg)
  }

  private def updateAndSave(evt: Event) = {
    state = state.updated(evt)
    deleteSnapshots(SnapshotSelectionCriteria.Latest)    
    saveSnapshot(state.document.toJson.asJsObject)
    state.document
  }

  private def createDocument(user: String, raw: JsObject, initFlag: Option[Boolean]) = {
    val dcd = DoCreateDocument(user, JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> eventACL(user)))))
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
    case Granted => DoReplaceDocument(user, JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> eventACL(user)))))
    case other   => other
  }.recover { case e => e }

  private def patchDocument(user: String, patch: JsonPatch) = checkPermission(user, PatchDocument).map {
    case Granted =>
      Try {
        patch(state.document.raw)
      } match {
        case Success(_) => DoPatchDocument(user, patch, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
        case Failure(e) => PatchDocumentException(e)
      }
    case other => other
  }.recover { case e => e }

  private def removeDocument(user: String) = checkPermission(user, RemoveDocument).map {
    case Granted => DoRemoveDocument(user, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
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
      store.search(s"${domainId}~${collection}~all~snapshots", hb(jo)).map {
        case (StatusCodes.OK, jo: JsObject) =>
          val fields = jo.fields
          val hitsFields = jo.fields("hits").asJsObject.fields
          DoExecuteDocument(user, params, body, Some(JsObject(hitsFields + ("_metadata" -> JsObject((fields - "hits"))))))
        case (code, jv) => throw new RuntimeException(s"Find documents error: $jv")
      }
    case other => Future.successful(other)
  }.recover { case e => e }

  val commandPermissionMap = Map[Any, String](
    GetDocument -> "get",
    ReplaceDocument -> "replace",
    PatchDocument -> "patch",
    RemoveDocument -> "remove",
    ExecuteDocument -> "execute",
    GetACL -> "getACL",
    ReplaceACL -> "replaceACL",
    PatchACL -> "patchACL",
    PatchEventACL -> "patchEventACL",
    FindDocuments -> "findDocuments",
    GarbageCollection -> "gc",
    RemovePermissionSubject -> "removePermissionSubject",
    RemoveEventPermissionSubject -> "removeEventPermissionSubject")

  override def checkPermission(user: String, command: Any): Future[Permission] = hitCache(s"${domain}").flatMap {
    case Some(true) => hitCache(s"${domain}~${collection}").flatMap {
      case Some(true) => (collection, id) match {
        case (".profiles", `user`) => Future.successful(Granted)
        case _ => fetchProfile(domain, user).map {
          case Document(_, _, _, _, _, _, profile) =>
            val aclObj = state.document.raw.fields("_metadata").asJsObject.fields("acl").asJsObject
            val userRoles = profileValue(profile, "roles")
            val userGroups = profileValue(profile, "groups")
            val (aclRoles, aclGroups, aclUsers) = commandPermissionMap.get(command) match {
              case Some(permission) => (aclValue(aclObj, permission, "roles"), aclValue(aclObj, permission, "groups"), aclValue(aclObj, permission, "users"))
              case None             => (Vector[String](), Vector[String](), Vector[String]())
            }
            if (aclRoles.intersect(userRoles).isEmpty && aclGroups.intersect(userGroups).isEmpty && !aclUsers.contains(user)) Denied else Granted
          case _ => Denied
        }
      }
      case _ => Future.successful(Granted)
    }
    case _ => Future.successful(Granted)
  }

}
