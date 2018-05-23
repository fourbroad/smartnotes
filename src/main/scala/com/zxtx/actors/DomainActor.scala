package com.zxtx.actors

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.collection._

import com.roundeights.hasher.Implicits.stringToHasher

import com.zxtx.actors.ACL._
import com.zxtx.actors.DocumentActor._
import com.zxtx.persistence.ElasticSearchStore

import akka.Done
import akka.pattern.ask
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.cluster.ddata.LWWMap
import akka.cluster.ddata.LWWMapKey
import akka.cluster.ddata.Replicator.Get
import akka.cluster.ddata.Replicator.GetSuccess
import akka.cluster.ddata.Replicator.NotFound
import akka.cluster.ddata.Replicator.ReadMajority
import akka.cluster.ddata.Replicator.Update
import akka.cluster.ddata.Replicator.UpdateFailure
import akka.cluster.ddata.Replicator.UpdateSuccess
import akka.cluster.ddata.Replicator.WriteMajority
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.Passivate
import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes
import akka.persistence.PersistentActor
import akka.persistence.RecoveryCompleted
import akka.persistence.SaveSnapshotFailure
import akka.persistence.SaveSnapshotSuccess
import akka.persistence.SnapshotOffer
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source

import pdi.jwt._
import spray.json._

import gnieh.diffson.sprayJson._
import akka.stream.stage.GraphStage
import akka.stream.stage.OutHandler
import akka.stream.Outlet
import akka.stream.SourceShape
import akka.stream.stage.GraphStageLogic
import akka.stream.Attributes

object DomainActor {

  def props(): Props = Props[DomainActor]

  object Domain {
    val empty = new Domain("", "", 0L, 0L, 0L, None, JsObject())
  }
  case class Domain(id: String, author: String = "anonymous", revision: Long, created: Long, updated: Long, deleted: Option[Boolean], raw: JsObject)

  case class CreateDomain(pid: String, user: String, raw: JsObject) extends Command
  case class GetDomain(pid: String, user: String) extends Command
  case class ReplaceDomain(pid: String, user: String, raw: JsObject) extends Command
  case class PatchDomain(pid: String, user: String, patch: JsonPatch) extends Command
  case class DeleteDomain(pid: String, user: String) extends Command
  case class ListCollections(pid: String, user: String) extends Command
  case class JoinDomain(pid: String, user: String, userName: String, permission: Option[JsObject] = None) extends Command
  case class QuitDomain(pid: String, user: String, userName: String) extends Command

  private case class DoCreateDomain(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoGetDomain(user: String, request: Option[Any] = None)
  private case class DoReplaceDomain(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoPatchDomain(user: String, patch: JsonPatch, raw: JsObject, request: Option[Any] = None)
  private case class DoDeleteDomain(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoListCollections(user: String, request: Option[Any] = None)
  private case class DoJoinDomain(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoQuitDomain(user: String, raw: JsObject, request: Option[Any] = None)

  case class DomainCreated(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DomainReplaced(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DomainPatched(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends DocumentEvent
  case class DomainDeleted(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DomainJoined(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DomainQuited(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent

  case object DomainNotFound extends Exception
  case object DomainAlreadyExists extends Exception
  case object DomainIsCreating extends Exception
  case object DomainSoftDeleted extends Exception
  case object UserAlreadyJoined extends Exception
  case object UserAlreadyQuited extends Exception
  case object UserNotJoined extends Exception
  case object UserProfileIsSoftDeleted extends Exception
  case class PatchDomainException(exception: Throwable) extends Exception

  val idExtractor: ShardRegion.ExtractEntityId = { case cmd: Command => (cmd.pid, cmd) }
  val shardResolver: ShardRegion.ExtractShardId = { case cmd: Command => (math.abs(cmd.pid.hashCode) % 100).toString }
  val shardName: String = "Domains"

  def persistenceId(rootDomain: String, domain: String): String = s"${rootDomain}~.domains~${domain}"

  object JsonProtocol extends DocumentJsonProtocol {
    implicit object DomainFormat extends RootJsonFormat[Domain] {
      def write(d: Domain) = {
        val metaObj = newMetaObject(d.raw.getFields("_metadata"), d.author, d.revision, d.created, d.updated, d.deleted)
        JsObject(("id" -> JsString(d.id)) :: d.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }

      def read(value: JsValue) = {
        val (id, author, revision, created, updated, deleted, jo) = extractFieldsWithUpdatedDeleted(value, "Domain expected!")
        Domain(id, author, revision, created, updated, deleted, jo)
      }
    }

    implicit object DomainCreatedFormat extends RootJsonFormat[DomainCreated] {
      def write(dc: DomainCreated) = {
        val metaObj = newMetaObject(dc.raw.getFields("_metadata"), dc.author, dc.revision, dc.created)
        JsObject(("id" -> JsString(dc.id)) :: dc.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "DomainCreated event expected!")
        DomainCreated(id, author, revision, created, jo)
      }
    }

    implicit object DomainReplacedFormat extends RootJsonFormat[DomainReplaced] {
      def write(dr: DomainReplaced) = {
        val metaObj = newMetaObject(dr.raw.getFields("_metadata"), dr.author, dr.revision, dr.created)
        JsObject(("id" -> JsString(dr.id)) :: dr.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "DomainReplaced event expected!")
        DomainReplaced(id, author, revision, created, jo)
      }
    }

    implicit object DomainPatchedFormat extends RootJsonFormat[DomainPatched] {
      import gnieh.diffson.sprayJson.provider.marshall
      import gnieh.diffson.sprayJson.provider.patchMarshaller
      def write(dp: DomainPatched) = {
        val metaObj = newMetaObject(dp.raw.getFields("_metadata"), dp.author, dp.revision, dp.created)
        JsObject(("id" -> JsString(dp.id)) :: ("patch" -> marshall(dp.patch)) :: dp.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, patch, jo) = extractFieldsWithPatch(value, "DomainPatched event expected!")
        DomainPatched(id, author, revision, created, patch, jo)
      }
    }

    implicit object DomainDeletedFormat extends RootJsonFormat[DomainDeleted] {
      def write(dd: DomainDeleted) = {
        val metaObj = newMetaObject(dd.raw.getFields("_metadata"), dd.author, dd.revision, dd.created)
        JsObject(("id" -> JsString(dd.id)) :: dd.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "DomainDeleted event expected!")
        DomainDeleted(id, author, revision, created, jo)
      }
    }

    implicit object DomainJoinedFormat extends RootJsonFormat[DomainJoined] {
      def write(dj: DomainJoined) = {
        val metaObj = newMetaObject(dj.raw.getFields("_metadata"), dj.author, dj.revision, dj.created)
        JsObject(("id" -> JsString(dj.id)) :: dj.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "DomainJoined event expected!")
        DomainJoined(id, author, revision, created, jo)
      }
    }

    implicit object DomainQuitedFormat extends RootJsonFormat[DomainQuited] {
      def write(dj: DomainQuited) = {
        val metaObj = newMetaObject(dj.raw.getFields("_metadata"), dj.author, dj.revision, dj.created)
        JsObject(("id" -> JsString(dj.id)) :: dj.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "DomainQuited event expected!")
        DomainQuited(id, author, revision, created, jo)
      }
    }

  }

  private case class State(domain: Domain, deleted: Boolean) {
    def updated(evt: DocumentEvent): State = evt match {
      case DomainCreated(id, author, revision, created, raw) =>
        val metadata = JsObject("author" -> JsString(author), "revision" -> JsNumber(revision), "created" -> JsNumber(created), "updated" -> JsNumber(created), "acl" -> acl(author))
        copy(domain = Domain(id, author, revision, created, created, None, JsObject(raw.fields + ("_metadata" -> metadata))))
      case DomainReplaced(_, _, revision, created, raw) =>
        val oldMetaFields = domain.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)))
        copy(domain = domain.copy(revision = revision, updated = created, raw = JsObject(raw.fields + ("_metadata" -> metadata))))
      case DomainPatched(_, _, revision, created, patch, _) =>
        val patchedDoc = patch(domain.raw).asJsObject
        val oldMetaFields = patchedDoc.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)))
        copy(domain = domain.copy(revision = revision, updated = created, raw = JsObject(patchedDoc.fields + ("_metadata" -> metadata))))
      case DomainDeleted(_, _, revision, created, _) =>
        val oldMetaFields = domain.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("deleted" -> JsBoolean(true)))
        copy(domain = domain.copy(revision = revision, updated = created, deleted = Some(true), raw = JsObject(domain.raw.fields + ("_metadata" -> metadata))))
      case ACLSet(_, _, revision, created, patch, _) =>
        val oldMetadata = domain.raw.fields("_metadata").asJsObject
        val patchedAuth = patch(oldMetadata.fields("acl"))
        val metadata = JsObject(oldMetadata.fields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("acl" -> patchedAuth))
        copy(domain = domain.copy(revision = revision, updated = created, raw = JsObject(domain.raw.fields + ("_metadata" -> metadata))))
      case PermissionSubjectRemoved(_, _, revision, created, raw) =>
        val oldMetadata = domain.raw.fields("_metadata").asJsObject
        val operation = raw.fields("operation").asInstanceOf[JsString].value
        val kind = raw.fields("kind").asInstanceOf[JsString].value
        val subject = raw.fields("subject").asInstanceOf[JsString].value
        val acl = doRemovePermissionSubject(oldMetadata.fields("acl").asJsObject, operation, kind, subject)
        val metadata = JsObject(oldMetadata.fields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("acl" -> acl))
        copy(domain = domain.copy(revision = revision, updated = created, raw = JsObject(domain.raw.fields + ("_metadata" -> metadata))))
      case _ => copy(domain = domain)
    }

    def updated(d: Domain): State = d match {
      case Domain(id, author, revision, created, updated, deleted, raw) => copy(domain = Domain(id, author, revision, created, updated, deleted, raw))
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
        "create_collection":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "list_collections":{
            "roles":["administrator","user"],
            "users":["${user}"]
        },
        "create_domain":{
            "roles":["administrator"]
        },
        "join_domain":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "quit_domain":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "set_acl":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "set_event_acl":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "gc":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "remove_permission_subject":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "remove_event_permission_subject":{
            "roles":["administrator"],
            "users":["${user}"]
        }
      }""".parseJson.asJsObject
}

class DomainActor extends PersistentActor with ACL with ActorLogging {
  import CollectionActor._
  import DomainActor._
  import DomainActor.JsonProtocol._

  val rootDomain = system.settings.config.getString("domain.root-domain")
  val adminName = system.settings.config.getString("domain.administrator.name")
  val domainRegion = ClusterSharding(system).shardRegion(DomainActor.shardName)
  val collectionRegion = ClusterSharding(system).shardRegion(CollectionActor.shardName)
  val userRegion = ClusterSharding(system).shardRegion(UserActor.shardName)

  override def persistenceId: String = self.path.name

  override def journalPluginId = "akka.persistence.elasticsearch.journal"
  override def snapshotPluginId = "akka.persistence.elasticsearch-snapshot-store"

  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system))

  private val readMajority = ReadMajority(duration)
  private val writeMajority = WriteMajority(duration)

  // passivate the entity when no activity
  context.setReceiveTimeout(2.minutes)

  private def id = persistenceId.split("%7E")(2)

  val mediator = DistributedPubSub(context.system).mediator

  private var state = State(Domain.empty, false)

  override def receiveRecover: Receive = {
    case evt: DomainCreated =>
      context.become(created)
      state = state.updated(evt)
    case evt: DomainDeleted =>
      context.become(deleted)
      state = state.updated(evt)
    case evt: DocumentEvent => state =
      state.updated(evt)
    case SnapshotOffer(_, jo: JsObject) =>
      val d = jo.convertTo[Domain]
      state = state.updated(d)
      d.deleted match {
        case Some(true) => context.become(deleted)
        case _          => context.become(created)
      }
    case RecoveryCompleted =>
      log.debug("DomainActor recovery completed.")
  }

  override def receiveCommand: Receive = initial

  def initial: Receive = {
    case CreateDomain(_, token, raw) =>
      val replyTo = sender
      val parent = context.parent
      createDomain(token, raw).foreach {
        case dcd: DoCreateDomain => self ! dcd.copy(request = Some(replyTo))
        case other =>
          replyTo ! other
          parent ! Passivate(stopMessage = PoisonPill)
      }
      context.become(creating)
    case _: Command => sender ! DomainNotFound
  }

  def creating: Receive = {
    case DoCreateDomain(user, raw, Some(replyTo: ActorRef)) =>
      persist(DomainCreated(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        state = state.updated(evt)
        val d = state.domain.toJson.asJsObject
        saveSnapshot(d)
        context.become(created)
        replyTo ! evt.copy(raw = d)
      }
    //    case CheckPermission(_, _, CreateCollection) => sender ! Granted
    case _: Command => sender ! DomainIsCreating
  }

  def created: Receive = {
    case JoinDomain(_, user, userName, permission) =>
      val replyTo = sender
      joinDomain(user, userName, permission).foreach {
        case djd: DoJoinDomain => self ! djd.copy(request = Some(replyTo))
        case other             => replyTo ! other
      }
    case DoJoinDomain(user, raw, Some(replyTo: ActorRef)) =>
      persist(DomainJoined(id, user, 0, System.currentTimeMillis, raw)) { evt =>
        replyTo ! evt
      }
    case QuitDomain(_, user, userName) =>
      val replyTo = sender
      quitDomain(user, userName).foreach {
        case dqd: DoQuitDomain => self ! dqd.copy(request = Some(replyTo))
        case other             => replyTo ! other
      }
    case DoQuitDomain(user, raw, Some(replyTo: ActorRef)) =>
      persist(DomainQuited(id, user, 0, System.currentTimeMillis, raw)) { evt =>
        replyTo ! evt
      }
    case GetDomain(_, user) =>
      val replyTo = sender
      getDomain(user).foreach {
        case dgd: DoGetDomain => replyTo ! state.domain
        case other            => replyTo ! other
      }
    case ReplaceDomain(_, user, raw) =>
      val replyTo = sender
      replaceDomain(user, raw).foreach {
        case drd: DoReplaceDomain => self ! drd.copy(request = Some(replyTo))
        case other                => replyTo ! other
      }
    case DoReplaceDomain(user, raw, Some(replyTo: ActorRef)) =>
      persist(DomainReplaced(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        replyTo ! evt.copy(raw = updateAndSave(evt))
      }
    case PatchDomain(_, user, patch) =>
      val replyTo = sender
      patchDomain(user, patch).foreach {
        case dpd: DoPatchDomain => self ! dpd.copy(request = Some(replyTo))
        case other              => replyTo ! other
      }
    case DoPatchDomain(user, patch, raw, Some(replyTo: ActorRef)) =>
      persist(DomainPatched(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, raw)) { evt =>
        replyTo ! evt.copy(raw = updateAndSave(evt))
      }
    case DeleteDomain(_, user) =>
      val replyTo = sender
      id match {
        case `rootDomain` => replyTo ! Denied
        case _ => deleteDomain(user).foreach {
          case ddd: DoDeleteDomain => self ! ddd.copy(request = Some(replyTo))
          case other               => replyTo ! other
        }
      }
    case DoDeleteDomain(user, raw, Some(replyTo: ActorRef)) =>
      persist(DomainDeleted(id, user, lastSequenceNr + 1, System.currentTimeMillis(), raw)) { evt =>
        state = state.updated(evt)
        deleteMessages(lastSequenceNr)
        deleteSnapshot(lastSequenceNr - 1)
        val d = state.domain.toJson.asJsObject
        saveSnapshot(d)
        context.become(deleted)
        mediator ! Publish(id, evt.copy(raw = d))
        context.parent ! Passivate(stopMessage = PoisonPill)
      }
    case SetACL(_, user, patch) =>
      val replyTo = sender
      setACL(user, state.domain.raw.fields("_metadata").asJsObject.fields("acl"), patch).foreach {
        case dsa: DoSetACL => self ! dsa.copy(request = Some(replyTo))
        case other         => replyTo ! other
      }
    case DoSetACL(user, patch, raw, Some(replyTo: ActorRef)) =>
      persist(ACLSet(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, raw)) { evt =>
        replyTo ! evt.copy(raw = updateAndSave(evt))
      }
    case SetEventACL(pid, user, patch) =>
      val replyTo = sender
      setEventACL(user, pid, patch).foreach {
        case dsea: DoSetEventACL => self ! dsea.copy(request = Some(replyTo))
        case other               => replyTo ! other
      }
    case DoSetEventACL(user, patch, raw, Some(replyTo: ActorRef)) =>
      persist(EventACLSet(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, raw)) { evt =>
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
        replyTo ! evt.copy(raw = updateAndSave(evt))
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
    case ListCollections(_, user) =>
      val replyTo = sender
      listCollections(user).foreach {
        case DoListCollections(_, Some(collections)) => replyTo ! collections
        case other                                   => replyTo ! other
      }
    case SaveSnapshotSuccess(metadata)         =>
    case SaveSnapshotFailure(metadata, reason) =>
    case GarbageCollection(_, user, _) =>
      val replyTo = sender
      garbageCollection(user).foreach {
        case Done  => replyTo ! GarbageCollectionCompleted
        case other => replyTo ! other
      }
    case CreateDomain(_, _, _) => sender ! DomainAlreadyExists
    case CheckPermission(_, user, command) =>
      val replyTo = sender
      checkPermission(user, command).foreach { replyTo ! _ }
  }

  def deleted: Receive = {
    case GetDomain(_, user) =>
      val replyTo = sender
      val parent = context.parent
      getDomain(user).foreach {
        case dgd: DoGetDomain =>
          self ! state.domain
          parent ! Passivate(stopMessage = PoisonPill)
        case other =>
          replyTo ! other
          parent ! Passivate(stopMessage = PoisonPill)
      }
    case _: Command => sender ! DomainSoftDeleted
  }

  override def unhandled(msg: Any): Unit = msg match {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
    case _              => super.unhandled(msg)
  }

  private def updateAndSave(evt: DocumentEvent): JsObject = {
    state = state.updated(evt)
    saveSnapshot(state.domain.toJson.asJsObject)
    deleteSnapshot(lastSequenceNr - 1)
    state.domain.toJson.asJsObject
  }

  private def createDomain(user: String, raw: JsObject) = {
    val newRaw = JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> eventACL(user))))
    id match {
      case `rootDomain` => initRootDomainCollections(user).flatMap {
        case Done => setCache(s"${rootDomain}", true).map {
          case CacheUpdated => DoCreateDomain(user, newRaw)
          case other        => other
        }
        case other => Future.successful(other)
      }.recover { case e => e }
      case _ => (domainRegion ? CheckPermission(s"${rootDomain}~.domains~${rootDomain}", user, CreateDomain)).flatMap {
        case Granted => initCollections(user).flatMap {
          case Done => setCache(s"${rootDomain}", true).map {
            case CacheUpdated => DoCreateDomain(user, newRaw)
            case other        => other
          }
          case other => Future.successful(other)
        }
        case Denied => Future.successful(Denied)
      }.recover { case e => e }
    }
  }

  private def getDomain(user: String) = checkPermission(user, GetDomain).map {
    case Granted => DoGetDomain(user)
    case other   => other
  }.recover { case e => e }

  private def joinDomain(user: String, userName: String, permission: Option[JsObject] = None) = checkPermission(user, JoinDomain).flatMap {
    case Granted =>
      val profile = permission match {
        case Some(jo) => JsObject(jo.fields + ("roles" -> JsArray(JsString("user"))))
        case None     => JsObject("roles" -> JsArray(JsString("user")))
      }
      (documentRegion ? CreateDocument(DocumentActor.persistenceId(id, ".profiles", userName), user, profile)).map {
        case DocumentCreated(_, _, _, _, raw) => DoJoinDomain(user, JsObject(raw.fields + ("name" -> JsString(userName)) + ("_metadata" -> JsObject("acl" -> eventACL(user)))))
        case DocumentAlreadyExists            => UserAlreadyJoined
        case DocumentSoftDeleted              => UserProfileIsSoftDeleted
        case other                            => other
      }
    case Denied => Future.successful(Denied)
  }.recover { case e => e }

  private def quitDomain(user: String, userName: String) = checkPermission(user, QuitDomain).flatMap {
    case Granted => (documentRegion ? DeleteDocument(DocumentActor.persistenceId(id, ".profiles", userName), user)).map {
      case _: DocumentDeleted  => DoQuitDomain(user, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
      case DocumentSoftDeleted => UserAlreadyQuited
      case DocumentNotFound    => UserNotJoined
      case other               => other
    }
    case Denied => Future.successful(Denied)
  }.recover { case e => e }

  private def replaceDomain(user: String, raw: JsObject) = checkPermission(user, ReplaceDomain).map {
    case Granted => DoReplaceDomain(user, raw)
    case Denied  => Denied
  }.recover { case e => e }

  private def patchDomain(user: String, patch: JsonPatch) = checkPermission(user, PatchDomain).map {
    case Granted =>
      Try {
        patch(state.domain.raw)
      } match {
        case Success(_) => DoPatchDomain(user, patch, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
        case Failure(e) => PatchDomainException(e)
      }
    case Denied => Denied
  }.recover { case e => e }

  private def deleteDomain(user: String) = checkPermission(user, DeleteDomain).flatMap {
    case Granted => clearCache(s"${id}").map {
      case CacheCleared => DoDeleteDomain(user, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
      case other        => other
    }
    case Denied => Future.successful(Denied)
  }.recover { case e => e }

  private def listCollections(user: String) = checkPermission(user, ListCollections).flatMap {
    case Granted =>
      (collectionRegion ? FindDocuments(CollectionActor.persistenceId(id, ".collections"), user, JsObject())).map {
        case jo: JsObject => DoListCollections(user, Some(jo))
        case jv: JsValue  => throw new RuntimeException(jv.compactPrint)
      }
    case Denied => Future.successful(Denied)
  }

  private def garbageCollection2(user: String) = checkPermission(user, GarbageCollection).flatMap {
    case Granted =>
      Thread.sleep(1000) // Bugfix: delay for deleting document in ElasticSearch.

      val source = Source.fromFuture(collectionRegion ? FindDocuments(s"${id}~.collections~.collections", user, JsObject()))
        .mapConcat(extractElements).mapAsync(1) {
          case (dsId: String, true)  => store.deleteIndices(s"${id}~${dsId}*")
          case (dsId: String, false) => collectionRegion ? GarbageCollection(s"${id}~.collections~${dsId}", user)
        }.map {
          case (StatusCodes.OK, _)          => Done
          case (_: StatusCode, jv: JsValue) => throw new RuntimeException(jv.compactPrint)
          case GarbageCollectionCompleted   => Done
        }
      id match {
        case `rootDomain` =>
          for {
            r1 <- Source.fromFuture(collectionRegion ? FindDocuments(s"${rootDomain}~.collections~.domains", user, JsObject()))
              .mapConcat(extractElements).filter { t => t._1 != rootDomain }.mapAsync(1) {
                case (domain, true)  => store.deleteIndices(s"${domain}*")
                case (domain, false) => domainRegion ? GarbageCollection(s"${rootDomain}~.domains~${domain}", user)
              }.map {
                case (StatusCodes.OK, _)          => Done
                case (_: StatusCode, jv: JsValue) => throw new RuntimeException(jv.compactPrint)
                case GarbageCollectionCompleted   => Done
              }.runWith(Sink.ignore)
            r2 <- source.runWith(Sink.ignore)
          } yield Done
        case _ =>
          source.runWith(Sink.ignore)
      }
    case Denied => Future.successful(Denied)
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
      id match {
        case `rootDomain` =>
          for {
            r1 <- Source.fromGraph(SearchSource(s"${rootDomain}~.domains~all~snapshots", query)).mapAsync(1) { jo =>
              store.deleteIndices(s"${jo.fields("_source").asJsObject.fields("id").asInstanceOf[JsString].value}~*")
            }.runWith(Sink.ignore)
            r2 <- Source.fromGraph(SearchSource(s"${rootDomain}~.collections~all~snapshots", query)).mapAsync(1) { jo =>
              store.deleteIndices(s"${rootDomain}~${jo.fields("_source").asJsObject.fields("id").asInstanceOf[JsString].value}*")
            }.runWith(Sink.ignore)
            r3 <- Source.fromGraph(SearchSource(s"${rootDomain}~.users~all~snapshots", query)).mapAsync(1) { jo =>
              val subject = jo.fields("_source").asJsObject.fields("id").asInstanceOf[JsString].value
              deleteProfiles(user, subject).flatMap { _ => clearACLs(user, subject) }
            }.runWith(Sink.ignore)
            r4 <- store.deleteByQuery(s"${rootDomain}~*", Seq[(String, String)](), query)
          } yield Done
        case _ =>
          for {
            r1 <- Source.fromGraph(SearchSource(s"${id}~.collections~all~snapshots", query)).mapAsync(1) { jo =>
              store.deleteIndices(s"${id}~${jo.fields("_source").asJsObject.fields("id").asInstanceOf[JsString].value}*")
            }.runWith(Sink.ignore)
            r2 <- store.deleteByQuery(s"${id}~*", Seq[(String, String)](), query)
          } yield Done
      }
    case Denied => Future.successful(Denied)
  }.recover { case e => e }

  case class SearchSource(index: String, query: String) extends GraphStage[SourceShape[JsObject]] {
    val out = Outlet[JsObject]("SearchSource.out")
    override val shape: SourceShape[JsObject] = SourceShape[JsObject](out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      private val bufferSize = 20
      private val buffer = mutable.Queue[JsObject]()
      private var hasMore = true
      private var scrollId: String = _

      private val initScrollUri = s"http://localhost:9200/${index}/_search&scroll=1m&size=${bufferSize / 2}"
      private val scrollUri = s"http://localhost:9200/_search/scroll"

      private val callback = getAsyncCallback[JsObject] { element =>
        buffer.enqueue(element)
        if (isAvailable(out)) push(out, buffer.dequeue())
      }

      override def preStart(): Unit = fetchDocuments(initScrollUri, query)

      setHandler(out, new OutHandler {
        override def onPull(): Unit = if (buffer.size > 0) {
          push(out, buffer.dequeue)
          if ((buffer.size < bufferSize / 2) && hasMore) {
            fetchDocuments(scrollUri, s"""{"scroll":"1m","scroll_id":"${scrollId}"}""")
          }
          if (buffer.size == 0 && !hasMore) {
            completeStage()
          }
        }

        override def onDownstreamFinish(): Unit = completeStage()
      })

      private def fetchDocuments(uri: String, entity: String) = search(uri, query).foreach(_.foreach(callback.invoke))

      private def search(uri: String, entity: String) = store.post(uri = uri, entity = entity).map {
        case (StatusCodes.OK, jv) =>
          scrollId = jv.asJsObject.fields("_scroll_id").asInstanceOf[JsString].value
          val elements = jv.asJsObject.fields("hits").asJsObject.fields("hits").asInstanceOf[JsArray].elements
          if (elements.size < bufferSize / 2) hasMore = false
          elements.map { jv => jv.asJsObject.fields("_source").asJsObject }
        case (code, _) => throw new RuntimeException(s"Error search documents: $code")
      }
    }
  }

  private def deleteProfiles(user: String, profile: String) = Source.fromGraph(SearchSource("*~.profiles~all~snapshots", s"""{
      "query":{
        "bool":{
          "must":[
            {"term":{"id.keyword":"${profile}"}}
          ],
          "must_not":[
            {"term":{"_metadata.deleted":true}}
          ]
        }
      }
    }""")).mapAsync(1) { jo =>
    documentRegion ? DeleteDocument(s"${jo.fields("_index").asInstanceOf[JsString].value.split("~")(0)}~.profiles~${profile}", user)
  }.runWith(Sink.ignore)

  private def clearACLs(user: String, subject: String) = Source.fromGraph(SearchSource("*~all~snapshots", s"""{
      "query":{
        "bool":{
          "should":[
            {"term":{"_metadata.acl.create_domain.users":"${subject}"}},
            {"term":{"_metadata.acl.create_collection.users":"${subject}"}},
            {"term":{"_metadata.acl.create_document.users":"${subject}"}},
            {"term":{"_metadata.acl.get.users":"${subject}"}},
            {"term":{"_metadata.acl.replace.users":"${subject}"}},
            {"term":{"_metadata.acl.patch.users":"${subject}"}},
            {"term":{"_metadata.acl.delete.users":"${subject}"}},
            {"term":{"_metadata.acl.reset_password.users":"${subject}"}},
            {"term":{"_metadata.acl.list_collections.users":"${subject}"}},
            {"term":{"_metadata.acl.find_documents.users":"${subject}"}},
            {"term":{"_metadata.acl.gc.users":"${subject}"}},
            {"term":{"_metadata.acl.execute.users":"${subject}"}}            
          ]
        }
      }
    }""")).mapAsync(1) { jo =>
    val segments = jo.fields("_index").asInstanceOf[JsString].value.split("~")
    val domain = segments(0)
    val collection = segments(1).split("_")(0)
    val source = jo.fields("_source").asJsObject
    val id = source.fields("id").asInstanceOf[JsString].value
    val pid = s"${domain}~${collection}~${id}"
    collection match {
      case ".collections" => (collectionRegion ? RemovePermissionSubject(pid, user, "*", "users", subject)).flatMap { _ =>
        collectionRegion ? RemoveEventPermissionSubject(pid, user, "*", "users", subject)
      }
      case ".domains" => (domainRegion ? RemovePermissionSubject(pid, user, "*", "users", subject)).flatMap { _ =>
        domainRegion ? RemoveEventPermissionSubject(pid, user, "*", "users", subject)
      }
      case "users" => (userRegion ? RemovePermissionSubject(pid, user, "*", "users", subject)).flatMap { _ =>
        userRegion ? RemoveEventPermissionSubject(pid, user, "*", "users", subject)
      }
      case docs => (documentRegion ? RemovePermissionSubject(pid, user, "*", "users", subject)).flatMap { _ =>
        documentRegion ? RemoveEventPermissionSubject(pid, user, "*", "users", subject)
      }
    }
  }.runWith(Sink.ignore)

  override def checkPermission(user: String, command: Any) = fetchProfile(id, user).map {
    case Document(_, _, _, _, _, _, profile) =>
      val aclObj = state.domain.raw.fields("_metadata").asJsObject.fields("acl").asJsObject
      val userRoles = profileValue(profile, "roles")
      val userGroups = profileValue(profile, "groups")
      val (aclRoles, aclGroups, aclUsers) = command match {
        case CreateDomain                 => (aclValue(aclObj, "create_domain", "roles"), aclValue(aclObj, "create_domain", "groups"), aclValue(aclObj, "create_domain", "users"))
        case GetDomain                    => (aclValue(aclObj, "get", "roles"), aclValue(aclObj, "get", "groups"), aclValue(aclObj, "get", "users"))
        case ReplaceDomain                => (aclValue(aclObj, "replace", "roles"), aclValue(aclObj, "replace", "groups"), aclValue(aclObj, "replace", "users"))
        case PatchDomain                  => (aclValue(aclObj, "patch", "roles"), aclValue(aclObj, "patch", "groups"), aclValue(aclObj, "patch", "users"))
        case DeleteDomain                 => (aclValue(aclObj, "delete", "roles"), aclValue(aclObj, "delete", "groups"), aclValue(aclObj, "delete", "users"))
        case SetACL                       => (aclValue(aclObj, "set_acl", "roles"), aclValue(aclObj, "set_acl", "groups"), aclValue(aclObj, "set_acl", "users"))
        case SetEventACL                  => (aclValue(aclObj, "set_event_acl", "roles"), aclValue(aclObj, "set_event_acl", "groups"), aclValue(aclObj, "set_event_acl", "users"))
        case JoinDomain                   => (aclValue(aclObj, "join_domain", "roles"), aclValue(aclObj, "join_domain", "groups"), aclValue(aclObj, "join_domain", "users"))
        case QuitDomain                   => (aclValue(aclObj, "quit_domain", "roles"), aclValue(aclObj, "quit_domain", "groups"), aclValue(aclObj, "quit_domain", "users"))
        case CreateCollection             => (aclValue(aclObj, "create_document_set", "roles"), aclValue(aclObj, "create_document_set", "groups"), aclValue(aclObj, "create_document_set", "users"))
        case ListCollections              => (aclValue(aclObj, "list_collections", "roles"), aclValue(aclObj, "list_collections", "groups"), aclValue(aclObj, "list_collections", "users"))
        case GarbageCollection            => (aclValue(aclObj, "gc", "roles"), aclValue(aclObj, "gc", "groups"), aclValue(aclObj, "gc", "users"))
        case RemovePermissionSubject      => (aclValue(aclObj, "remove_permission_subject", "roles"), aclValue(aclObj, "remove_permission_subject", "groups"), aclValue(aclObj, "remove_permission_subject", "users"))
        case RemoveEventPermissionSubject => (aclValue(aclObj, "remove_event_permission_subject", "roles"), aclValue(aclObj, "remove_event_permission_subject", "groups"), aclValue(aclObj, "remove_event_permission_subject", "users"))

        case _                            => (Vector[String](), Vector[String](), Vector[String]())
      }
      //            System.out.println(s"~~~~~~~~~~~~aclRoles~~~~~${aclRoles}")
      //            System.out.println(s"~~~~~~~~~~~~userRoles~~~~~${userRoles}")
      //            System.out.println(s"~~~~~~~~~~~~aclGroups~~~~~${aclGroups}")
      //            System.out.println(s"~~~~~~~~~~~~userGroups~~~~~${userGroups}")
      //            System.out.println(s"~~~~~~~~~~~~aclUsers~~~~~${aclUsers}")
      //            System.out.println(s"~~~~~~~~~~~~user~~~~~${user}")
      if (aclRoles.intersect(userRoles).isEmpty && aclGroups.intersect(userGroups).isEmpty && !aclUsers.contains(user)) Denied else Granted
    case _ => Denied
  }

  private def initCollections(user: String): Future[Done] = {
    val adminRole = JsObject("description" -> JsString("The administrator role of current domain."))
    val userRole = JsObject("description" -> JsString("The user role of current domain."))
    val profile = JsObject("roles" -> JsArray(JsString("administrator"), JsString("user")))
    for {
      r1 <- collectionRegion ? CreateCollection(CollectionActor.persistenceId(id, ".collections"), user, JsObject(), Some(true))
      r2 <- collectionRegion ? CreateCollection(CollectionActor.persistenceId(id, ".roles"), user, JsObject(), Some(true))
      r3 <- collectionRegion ? CreateCollection(CollectionActor.persistenceId(id, ".profiles"), user, JsObject(), Some(true))
      r4 <- documentRegion ? CreateDocument(DocumentActor.persistenceId(id, ".roles", "administrator"), user, adminRole, Some(true))
      r5 <- documentRegion ? CreateDocument(DocumentActor.persistenceId(id, ".roles", "user"), user, userRole, Some(true))
      r6 <- documentRegion ? CreateDocument(DocumentActor.persistenceId(id, ".profiles", user), user, profile, Some(true))
    } yield Done
  }

  private def initRootDomainCollections(user: String): Future[Done] = {
    import com.roundeights.hasher.Implicits._
    val adminRole = JsObject("description" -> JsString("The administrator role of current domain."))
    val userRole = JsObject("description" -> JsString("The user role of current domain."))
    val profile = JsObject("roles" -> JsArray(JsString("administrator"), JsString("user")))

    val adminName = system.settings.config.getString("domain.administrator.name")
    val password = system.settings.config.getString("domain.administrator.password")
    val adminUser = JsObject("password" -> JsString(password.md5.hex))

    for {
      r1 <- collectionRegion ? CreateCollection(CollectionActor.persistenceId(rootDomain, ".collections"), adminName, JsObject(), Some(true))
      r2 <- collectionRegion ? CreateCollection(CollectionActor.persistenceId(rootDomain, ".domains"), adminName, JsObject(), Some(true))
      r3 <- collectionRegion ? CreateCollection(CollectionActor.persistenceId(rootDomain, ".users"), adminName, JsObject(), Some(true))
      r4 <- collectionRegion ? CreateCollection(CollectionActor.persistenceId(rootDomain, ".roles"), adminName, JsObject(), Some(true))
      r5 <- collectionRegion ? CreateCollection(CollectionActor.persistenceId(rootDomain, ".profiles"), adminName, JsObject(), Some(true))
      r6 <- documentRegion ? CreateDocument(DocumentActor.persistenceId(rootDomain, ".users", adminName), adminName, adminUser, Some(true))
      r7 <- documentRegion ? CreateDocument(DocumentActor.persistenceId(rootDomain, ".roles", "administrator"), adminName, adminRole, Some(true))
      r8 <- documentRegion ? CreateDocument(DocumentActor.persistenceId(rootDomain, ".roles", "user"), adminName, userRole, Some(true))
      r9 <- documentRegion ? CreateDocument(DocumentActor.persistenceId(rootDomain, ".profiles", adminName), adminName, profile, Some(true))
    } yield Done
  }

  private def extractElements(any: Any) = any match {
    case jo: JsObject =>
      jo.fields("hits").asInstanceOf[JsArray].elements.map {
        case ds: JsObject =>
          val source = ds.fields("_source").asJsObject
          val dsId = source.fields("id").asInstanceOf[JsString].value
          val deleted = source.fields("_metadata").asJsObject.getFields("deleted") match {
            case Seq(JsBoolean(d)) => d
            case _                 => false
          }
          (dsId, deleted)
        case jv: JsValue => throw new RuntimeException(jv.compactPrint)
      }
    case jv: JsValue => throw new RuntimeException(jv.compactPrint)
  }

}
