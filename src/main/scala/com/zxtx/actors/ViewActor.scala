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

object ViewActor {

  def props(): Props = Props[ViewActor]

  object View {
    val empty = new View("", "", 0L, 0L, 0L, None, spray.json.JsObject())
  }
  case class View(id: String, author: String = "anonymous", revision: Long, created: Long, updated: Long, removed: Option[Boolean], raw: JsObject)

  case class GetView(pid: String, user: String) extends Command
  case class CreateView(pid: String, user: String, raw: JsObject, initFlag: Option[Boolean] = None) extends Command
  case class ReplaceView(pid: String, user: String, raw: JsObject) extends Command
  case class PatchView(pid: String, user: String, patch: JsonPatch) extends Command
  case class RemoveView(pid: String, user: String) extends Command
  case class Refresh(pid: String, user: String) extends Command
  case class FindDocuments(pid: String, user: String, query: JsObject) extends Command

  private case class DoGetView(user: String, request: Option[Any] = None)
  private case class DoCreateView(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoReplaceView(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoPatchView(user: String, patch: JsonPatch, raw: JsObject, request: Option[Any] = None)
  private case class DoRemoveView(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoFindDocuments(user: String, query: JsObject, request: Option[Any] = None)
  private case class DoRefresh(user: String, request: Option[Any] = None)

  case class ViewCreated(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case class ViewReplaced(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case class ViewPatched(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends Event
  case class ViewRemoved(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case object Refreshed extends Event

  case object ViewNotFound extends Exception
  case object ViewAlreadyExists extends Exception
  case object ViewIsCreating extends Exception
  case object ViewSoftRemoved extends Exception
  case class PatchViewException(exception: Throwable) extends Exception

  val idExtractor: ShardRegion.ExtractEntityId = { case cmd: Command => (cmd.pid, cmd) }
  val shardResolver: ShardRegion.ExtractShardId = { case cmd: Command => (math.abs(cmd.pid.hashCode) % 100).toString }
  val shardName: String = "Views"

  def persistenceId(domain: String, viewId: String) = s"${domain}~.views~${viewId}"

  object JsonProtocol extends DocumentJsonProtocol {
    implicit object ViewFormat extends RootJsonFormat[View] {
      def write(doc: View) = {
        val metaObj = newMetaObject(doc.raw.getFields("_metadata"), doc.author, doc.revision, doc.created, doc.updated, doc.removed)
        JsObject(("id" -> JsString(doc.id)) :: doc.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, updated, removed, raw) = extractFieldsWithUpdatedRemoved(value, "View expected!")
        View(id, author, revision, created, updated, removed, raw)
      }
    }

    implicit object ViewCreatedFormat extends RootJsonFormat[ViewCreated] {
      def write(dc: ViewCreated) = {
        val metaObj = newMetaObject(dc.raw.getFields("_metadata"), dc.author, dc.revision, dc.created)
        JsObject(("id" -> JsString(dc.id)) :: dc.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, raw) = extractFields(value, "ViewCreated event expected!")
        ViewCreated(id, author, revision, created, raw)
      }
    }

    implicit object ViewReplacedFormat extends RootJsonFormat[ViewReplaced] {
      def write(dr: ViewReplaced) = {
        val metaObj = newMetaObject(dr.raw.getFields("_metadata"), dr.author, dr.revision, dr.created)
        JsObject(("id" -> JsString(dr.id)) :: dr.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, raw) = extractFields(value, "ViewReplaced event expected!")
        ViewReplaced(id, author, revision, created, raw)
      }
    }

    implicit object ViewPatchedFormat extends RootJsonFormat[ViewPatched] {
      import gnieh.diffson.sprayJson.provider._
      import gnieh.diffson.sprayJson.provider.marshall

      def write(dp: ViewPatched) = {
        val metaObj = newMetaObject(dp.raw.getFields("_metadata"), dp.author, dp.revision, dp.created)
        spray.json.JsObject(("id" -> JsString(dp.id)) :: ("patch" -> patchValueToString(marshall(dp.patch),"ViewPatched event expected!")) :: dp.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, patch, raw) = extractFieldsWithPatch(value, "ViewPatched event expected!")
        ViewPatched(id, author, revision, created, patch, raw)
      }
    }

    implicit object ViewRemovedFormat extends RootJsonFormat[ViewRemoved] {
      def write(dd: ViewRemoved) = {
        val metaObj = newMetaObject(dd.raw.getFields("_metadata"), dd.author, dd.revision, dd.created)
        JsObject(("id" -> JsString(dd.id)) :: dd.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, raw) = extractFields(value, "ViewRemoved event expected!")
        ViewRemoved(id, author, revision, created, raw)
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
        "findDocuments":{
            "roles":["administrator","user"],
            "users":["${user}"]
        },
        "refresh":{
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

  private case class State(document: View) {
    def updated(evt: Event): State = evt match {
      case ViewCreated(id, author, revision, created, raw) =>
        val metaFields = raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(metaFields+("author" -> JsString(author))+("revision" -> JsNumber(revision))+("created" -> JsNumber(created))+("updated" -> JsNumber(created))+("acl" -> acl(author)))
        copy(document = View(id, author, revision, created, created, None, JsObject(raw.fields + ("_metadata" -> metadata))))
      case ViewReplaced(_, _, revision, created, raw) =>
        val oldMetaFields = document.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)))
        copy(document = document.copy(revision = revision, updated = created, raw = JsObject(raw.fields + ("_metadata" -> metadata))))
      case ViewPatched(_, _, revision, created, patch, _) =>
        val patchedDoc = patch(document.raw).asJsObject
        val oldMetaFields = patchedDoc.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)))
        copy(document = document.copy(revision = revision, updated = created, raw = JsObject((patchedDoc.fields - "_metadata") + ("_metadata" -> metadata))))
      case ViewRemoved(_, _, revision, created, _) =>
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

    def updated(doc: View): State = doc match {
      case View(id, author, revision, created, updated, removed, raw) => copy(document = View(id, author, revision, created, updated, removed, raw))
    }
  }
}

class ViewActor extends PersistentActor with ACL with ActorLogging {
  import ViewActor._
  import ViewActor.JsonProtocol._
  import DomainActor._
  import DocumentActor._
  import com.zxtx.persistence.ElasticSearchStore._

  // self.path.parent.name is the type name (utf-8 URL-encoded)
  // self.path.name is the entry identifier (utf-8 URL-encoded)
  override def persistenceId: String = self.path.name

  override def journalPluginId = "akka.persistence.elasticsearch.journal"
  override def snapshotPluginId = "akka.persistence.elasticsearch-snapshot-store"

  val rootDomain = system.settings.config.getString("domain.root-domain")
  val domainRegion = ClusterSharding(system).shardRegion(DomainActor.shardName)

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

  private var state = State(View.empty)

  override def receiveRecover: Receive = {
    case evt: ViewCreated =>
      state = state.updated(evt)
      context.become(created)
    case evt: ViewRemoved =>
      state = state.updated(evt)
      context.become(removed)
    case evt: Event =>
      state = state.updated(evt)
    case SnapshotOffer(_, jo: JsObject) =>
      val doc = jo.convertTo[View]
      state = state.updated(doc)
      doc.removed match {
        case Some(true) => context.become(removed)
        case _          => context.become(created)
      }
    case RecoveryCompleted =>
      log.debug("ViewActor recovery completed.")
  }

  override def receiveCommand: Receive = initial

  def initial: Receive = {
    case CreateView(_, user, raw, initFlag) =>
      val replyTo = sender
      val parent = context.parent
      createView(user, raw, initFlag).foreach {
        case dcd: DoCreateView => self ! dcd.copy(request = Some(replyTo))
        case other =>
          replyTo ! other
          parent ! Passivate(stopMessage = PoisonPill)
      }
      context.become(creating)
    case _: Command => sender ! ViewNotFound
  }

  def creating: Receive = {
    case DoCreateView(user, raw, Some(replyTo: ActorRef)) =>
      persist(ViewCreated(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        state = state.updated(evt)
        val doc = state.document.toJson.asJsObject
        saveSnapshot(doc)
        context.become(created)
        replyTo ! state.document
      }
    case _: Command => sender ! ViewIsCreating
  }

  def created: Receive = {
    case GetView(pid, user) =>
      val replyTo = sender
      getView(user).foreach {
        case dgd: DoGetView => replyTo ! state.document
        case other          => replyTo ! other
      }
    case ReplaceView(_, user, raw) =>
      val replyTo = sender
      replaceView(user, raw).foreach {
        case drd: DoReplaceView => self ! drd.copy(request = Some(replyTo))
        case other              => replyTo ! other
      }
    case DoReplaceView(user, raw, Some(replyTo: ActorRef)) =>
      persist(ViewReplaced(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        replyTo ! updateAndSave(evt)
      }
    case PatchView(_, user, patch) =>
      val replyTo = sender
      patchView(user, patch).foreach {
        case dpd: DoPatchView => self ! dpd.copy(request = Some(replyTo))
        case other            => replyTo ! other
      }
    case DoPatchView(user, patch, raw, Some(replyTo: ActorRef)) =>
      persist(ViewPatched(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, raw)) { evt =>
        replyTo ! updateAndSave(evt)
      }
    case RemoveView(_, user) =>
      val replyTo = sender
      removeView(user).foreach {
        case ddd: DoRemoveView => self ! ddd.copy(request = Some(replyTo))
        case other             => replyTo ! other
      }
    case DoRemoveView(user, raw, Some(replyTo: ActorRef)) =>
      persist(ViewRemoved(id, user, lastSequenceNr + 1, System.currentTimeMillis(), raw)) { evt =>
        state = state.updated(evt)
        deleteMessages(lastSequenceNr)
        deleteSnapshots(SnapshotSelectionCriteria.Latest)
        val doc = state.document.toJson.asJsObject
        saveSnapshot(doc)
        context.become(removed)
        replyTo ! evt.copy(raw = doc)
        context.parent ! Passivate(stopMessage = PoisonPill)
      }
    case FindDocuments(_, user, query) =>
      val replyTo = sender
      findDocuments(user, query).foreach {
        case DoFindDocuments(_, _, Some(result)) => replyTo ! result
        case other                               => replyTo ! other
      }
    case Refresh(pid, user) =>
      val replyTo = sender
      refresh(user).foreach {
        case dr: DoRefresh => replyTo ! Refreshed
        case other         => replyTo ! other
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
    case SaveSnapshotSuccess(metadata) =>
    case SaveSnapshotFailure(metadata, reason) =>
    case _: CreateView => sender ! ViewAlreadyExists
    case _: DomainRemoved | _: CollectionActor.CollectionRemoved => context.parent ! Passivate(stopMessage = PoisonPill)
  }

  def removed: Receive = {
    case GetView(_, user) =>
      val replyTo = sender
      val parent = context.parent
      getView(user).foreach {
        case dgd: DoGetView =>
          replyTo ! state.document
          parent ! Passivate(stopMessage = PoisonPill)
        case other =>
          replyTo ! other
          parent ! Passivate(stopMessage = PoisonPill)
      }
    case _: Command =>
      sender ! ViewSoftRemoved
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

  private def createView(user: String, raw: JsObject, initFlag: Option[Boolean]) = {
    val dcd = DoCreateView(user, JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> eventACL(user),"type" -> JsString("view")))))
    initFlag match {
      case Some(true) => Future.successful(dcd)
      case other => (domainRegion ? CheckPermission(DomainActor.persistenceId(rootDomain, domain), user, CreateView)).map {
        case Granted => dcd
        case Denied  => Denied
      }
    }
  }

  private def getView(user: String) = checkPermission(user, GetView).map {
    case Granted => DoGetView(user)
    case other   => other
  }.recover { case e => e }

  private def replaceView(user: String, raw: JsObject) = checkPermission(user, ReplaceView).map {
    case Granted => DoReplaceView(user, JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> eventACL(user)))))
    case other   => other
  }.recover { case e => e }

  private def patchView(user: String, patch: JsonPatch) = checkPermission(user, PatchView).map {
    case Granted =>
      Try {
        patch(state.document.raw)
      } match {
        case Success(_) => DoPatchView(user, patch, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
        case Failure(e) => PatchViewException(e)
      }
    case other => other
  }.recover { case e => e }

  private def removeView(user: String) = checkPermission(user, RemoveView).map {
    case Granted => DoRemoveView(user, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
    case other   => other
  }.recover { case e => e }

  private def getIndexAliases: String = state.document.raw.getFields("collections") match {
    case Seq(collections: JsArray) => collections.elements.map { jv => s"${domain}~${jv.asInstanceOf[JsString].value}~all~snapshots" }.reduceLeft(_ + "," + _)
    case _                         => "_all"
  }

  private def refresh(user: String) = checkPermission(user, Refresh).flatMap {
    case Granted => store.refresh(getIndexAliases).map {
      case (StatusCodes.OK, _) => DoRefresh(user)
      case (code, jv)          => throw new RuntimeException(s"Find documents error: $jv")
    }
    case other => Future.successful(other)
  }.recover { case e => e }

  private def findDocuments(user: String, query: JsObject) = checkPermission(user, FindDocuments).flatMap {
    case Granted => filterQuery(domain, user, query).flatMap {
      case fq: JsObject => store.search(getIndexAliases, fq.compactPrint).map {
        case (StatusCodes.OK, jo: JsObject) => DoFindDocuments(user, query, Some(jo))
        case (code, jv)                     => throw new RuntimeException(s"Find documents error: $jv")
      }
      case other => throw new RuntimeException(s"Find documents error: $other")
    }
    case other => Future.successful(other)
  }.recover { case e => e }

  val commandPermissionMap = Map[Any, String](
    GetView -> "get",
    ReplaceView -> "replace",
    PatchView -> "patch",
    RemoveView -> "remove",
    GetACL -> "getACL",
    ReplaceACL -> "replaceACL",
    PatchACL -> "patchACL",
    PatchEventACL -> "patchEventACL",
    FindDocuments -> "findDocuments",
    Refresh -> "refresh",
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
