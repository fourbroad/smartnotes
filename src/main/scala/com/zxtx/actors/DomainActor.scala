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
import com.zxtx.persistence.ElasticSearchStore._

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
import akka.persistence.SnapshotSelectionCriteria

object DomainActor {

  def props(): Props = Props[DomainActor]

  object Domain {
    val empty = new Domain("", "", 0L, 0L, 0L, None, JsObject())
  }
  case class Domain(id: String, author: String = "anonymous", revision: Long, created: Long, updated: Long, removed: Option[Boolean], raw: JsObject)

  case class CreateDomain(pid: String, user: String, raw: JsObject) extends Command
  case class GetDomain(pid: String, user: String) extends Command
  case class ReplaceDomain(pid: String, user: String, raw: JsObject) extends Command
  case class PatchDomain(pid: String, user: String, patch: JsonPatch) extends Command
  case class RemoveDomain(pid: String, user: String) extends Command
  case class FindDomains(pid: String, user: String, query: JsObject) extends Command
  case class FindCollections(pid: String, user: String, query: JsObject) extends Command
  case class FindViews(pid: String, user: String, query: JsObject) extends Command
  case class FindForms(pid: String, user: String, query: JsObject) extends Command
  case class JoinDomain(pid: String, user: String, userName: String, permission: Option[JsObject] = None) extends Command
  case class QuitDomain(pid: String, user: String, userName: String) extends Command
  case class RefreshDomain(pid: String, user: String) extends Command

  private case class DoCreateDomain(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoGetDomain(user: String, request: Option[Any] = None)
  private case class DoReplaceDomain(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoPatchDomain(user: String, patch: JsonPatch, raw: JsObject, request: Option[Any] = None)
  private case class DoRemoveDomain(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoFindDomains(user: String, query: JsObject, request: Option[Any] = None)
  private case class DoFindCollections(user: String, query: JsObject, request: Option[Any] = None)
  private case class DoFindViews(user: String, query: JsObject, request: Option[Any] = None)
  private case class DoFindForms(user: String, query: JsObject, request: Option[Any] = None)
  private case class DoJoinDomain(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoQuitDomain(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoRefreshDomain(user: String, request: Option[Any] = None)

  case class DomainCreated(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case class DomainReplaced(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case class DomainPatched(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends Event
  case class DomainRemoved(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case class DomainJoined(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case class DomainQuited(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case object DomainRefreshed extends Event

  case object DomainNotFound extends Exception
  case object DomainAlreadyExists extends Exception
  case object DomainIsCreating extends Exception
  case object DomainSoftRemoved extends Exception
  case object UserAlreadyJoined extends Exception
  case object UserAlreadyQuited extends Exception
  case object UserNotJoined extends Exception
  case object UserProfileIsSoftRemoved extends Exception
  case class PatchDomainException(exception: Throwable) extends Exception

  val idExtractor: ShardRegion.ExtractEntityId = { case cmd: Command => (cmd.pid, cmd) }
  val shardResolver: ShardRegion.ExtractShardId = { case cmd: Command => (math.abs(cmd.pid.hashCode) % 100).toString }
  val shardName: String = "Domains"

  def persistenceId(rootDomain: String, domain: String): String = s"${rootDomain}~.domains~${domain}"

  object JsonProtocol extends DocumentJsonProtocol {
    implicit object DomainFormat extends RootJsonFormat[Domain] {
      def write(d: Domain) = {
        val metaObj = newMetaObject(d.raw.getFields("_metadata"), d.author, d.revision, d.created, d.updated, d.removed)
        JsObject(("id" -> JsString(d.id)) :: d.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }

      def read(value: JsValue) = {
        val (id, author, revision, created, updated, removed, jo) = extractFieldsWithUpdatedRemoved(value, "Domain expected!")
        Domain(id, author, revision, created, updated, removed, jo)
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
        JsObject(("id" -> JsString(dp.id)) :: ("patch" -> patchValueToString(marshall(dp.patch), "DomainPatched event expected!")) :: dp.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, patch, jo) = extractFieldsWithPatch(value, "DomainPatched event expected!")
        DomainPatched(id, author, revision, created, patch, jo)
      }
    }

    implicit object DomainRemovedFormat extends RootJsonFormat[DomainRemoved] {
      def write(dd: DomainRemoved) = {
        val metaObj = newMetaObject(dd.raw.getFields("_metadata"), dd.author, dd.revision, dd.created)
        JsObject(("id" -> JsString(dd.id)) :: dd.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "DomainRemoved event expected!")
        DomainRemoved(id, author, revision, created, jo)
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

  private case class State(domain: Domain, removed: Boolean) {
    def updated(evt: Event): State = evt match {
      case DomainCreated(id, author, revision, created, raw) =>
        val metaFields = raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(metaFields + ("author" -> JsString(author)) + ("revision" -> JsNumber(revision)) + ("created" -> JsNumber(created)) + ("updated" -> JsNumber(created)) + ("acl" -> acl(author)))
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
      case DomainRemoved(_, _, revision, created, _) =>
        val oldMetaFields = domain.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("removed" -> JsBoolean(true)))
        copy(domain = domain.copy(revision = revision, updated = created, removed = Some(true), raw = JsObject(domain.raw.fields + ("_metadata" -> metadata))))
      case ACLReplaced(_, _, revision, created, raw) =>
        val oldMetadata = domain.raw.fields("_metadata").asJsObject
        val replacedACL = JsObject(raw.fields - "_metadata" - "id")
        val metadata = JsObject(oldMetadata.fields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("acl" -> replacedACL))
        copy(domain = domain.copy(revision = revision, updated = created, raw = JsObject(domain.raw.fields + ("_metadata" -> metadata))))
      case ACLPatched(_, _, revision, created, patch, _) =>
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
      case Domain(id, author, revision, created, updated, removed, raw) => copy(domain = Domain(id, author, revision, created, updated, removed, raw))
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
        "createCollection":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "createView":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "createForm":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "createRole":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "createProfile":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "createUser":{
            "roles":["administrator"],
            "users":["${user}","anonymous"]
        },
        "findDomains":{
            "roles":["administrator","user"],
            "users":["${user}"]
        },
        "findCollections":{
            "roles":["administrator","user"],
            "users":["${user}"]
        },
        "findViews":{
            "roles":["administrator","user"],
            "users":["${user}"]
        },
        "findForms":{
            "roles":["administrator","user"],
            "users":["${user}"]
        },
        "createDomain":{
            "roles":["administrator"]
        },
        "join":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "quit":{
            "roles":["administrator"],
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
        "refresh":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "gc":{
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
}

class DomainActor extends PersistentActor with ACL with ActorLogging {
  import CollectionActor._
  import ViewActor._
  import FormActor._
  import RoleActor._
  import ProfileActor._
  import UserActor._
  import DomainActor._
  import DomainActor.JsonProtocol._

  val rootDomain = system.settings.config.getString("domain.root-domain")
  val adminName = system.settings.config.getString("domain.administrator.name")
  val domainRegion = ClusterSharding(system).shardRegion(DomainActor.shardName)
  val collectionRegion = ClusterSharding(system).shardRegion(CollectionActor.shardName)
  val viewRegion = ClusterSharding(system).shardRegion(ViewActor.shardName)
  val formRegion = ClusterSharding(system).shardRegion(FormActor.shardName)
  val roleRegion = ClusterSharding(system).shardRegion(RoleActor.shardName)
  val profileRegion = ClusterSharding(system).shardRegion(ProfileActor.shardName)
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
    case evt: DomainRemoved =>
      context.become(removed)
      state = state.updated(evt)
    case evt: Event => state =
      state.updated(evt)
    case SnapshotOffer(_, jo: JsObject) =>
      val d = jo.convertTo[Domain]
      state = state.updated(d)
      d.removed match {
        case Some(true) => context.become(removed)
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
        saveSnapshot(state.domain.toJson.asJsObject)
        context.become(created)
        replyTo ! state.domain
      }
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
        replyTo ! updateAndSave(evt)
      }
    case PatchDomain(_, user, patch) =>
      val replyTo = sender
      patchDomain(user, patch).foreach {
        case dpd: DoPatchDomain => self ! dpd.copy(request = Some(replyTo))
        case other              => replyTo ! other
      }
    case DoPatchDomain(user, patch, raw, Some(replyTo: ActorRef)) =>
      persist(DomainPatched(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, raw)) { evt =>
        replyTo ! updateAndSave(evt)
      }
    case RemoveDomain(_, user) =>
      val replyTo = sender
      id match {
        case `rootDomain` => replyTo ! Denied
        case _ => removeDomain(user).foreach {
          case ddd: DoRemoveDomain => self ! ddd.copy(request = Some(replyTo))
          case other               => replyTo ! other
        }
      }
    case DoRemoveDomain(user, raw, Some(replyTo: ActorRef)) =>
      persist(DomainRemoved(id, user, lastSequenceNr + 1, System.currentTimeMillis(), raw)) { evt =>
        state = state.updated(evt)
        deleteMessages(lastSequenceNr)
        deleteSnapshots(SnapshotSelectionCriteria.Latest)
        saveSnapshot(state.domain.toJson.asJsObject)
        context.become(removed)
        replyTo ! evt
        mediator ! Publish(id, evt)
        context.parent ! Passivate(stopMessage = PoisonPill)
      }
    case GetACL(_, user) =>
      val replyTo = sender
      getACL(user).foreach {
        case dga: DoGetACL => replyTo ! state.domain.raw.fields("_metadata").asJsObject.fields("acl")
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
      patchACL(user, state.domain.raw.fields("_metadata").asJsObject.fields("acl"), patch).foreach {
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
    case FindDomains(_, user, query) =>
      val replyTo = sender
      findDomains(user, query).foreach {
        case DoFindDomains(_, query, Some(domains)) => replyTo ! domains
        case other                                  => replyTo ! other
      }
    case FindCollections(_, user, query) =>
      val replyTo = sender
      findCollections(user, query).foreach {
        case DoFindCollections(_, query, Some(collections)) => replyTo ! collections
        case other => replyTo ! other
      }
    case FindViews(_, user, query) =>
      val replyTo = sender
      findViews(user, query).foreach {
        case DoFindViews(_, query, Some(views)) => replyTo ! views
        case other                              => replyTo ! other
      }
    case FindForms(_, user, query) =>
      val replyTo = sender
      findForms(user, query).foreach {
        case DoFindForms(_, query, Some(forms)) => replyTo ! forms
        case other                              => replyTo ! other
      }
    case RefreshDomain(_, user) =>
      val replyTo = sender
      refreshDomain(user).foreach {
        case drd: DoRefreshDomain => replyTo ! DomainRefreshed
        case other                => replyTo ! other
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

  def removed: Receive = {
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
    case _: Command => sender ! DomainSoftRemoved
  }

  override def unhandled(msg: Any): Unit = msg match {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
    case _              => super.unhandled(msg)
  }

  private def updateAndSave(evt: Event) = {
    state = state.updated(evt)
    deleteSnapshots(SnapshotSelectionCriteria.Latest)
    saveSnapshot(state.domain.toJson.asJsObject)
    state.domain
  }

  private def createDomain(user: String, raw: JsObject) = {
    val newRaw = JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> eventACL(user), "type" -> JsString("domain"))))
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
          case Done => setCache(s"${id}", true).map {
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
        case Document(_, _, _, _, _, _, raw) => DoJoinDomain(user, JsObject(raw.fields + ("name" -> JsString(userName)) + ("_metadata" -> JsObject("acl" -> eventACL(user)))))
        case DocumentAlreadyExists           => UserAlreadyJoined
        case DocumentSoftRemoved             => UserProfileIsSoftRemoved
        case other                           => other
      }
    case Denied => Future.successful(Denied)
  }.recover { case e => e }

  private def quitDomain(user: String, userName: String) = checkPermission(user, QuitDomain).flatMap {
    case Granted => (documentRegion ? RemoveDocument(DocumentActor.persistenceId(id, ".profiles", userName), user)).map {
      case _: DocumentRemoved  => DoQuitDomain(user, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
      case DocumentSoftRemoved => UserAlreadyQuited
      case DocumentNotFound    => UserNotJoined
      case other               => other
    }
    case Denied => Future.successful(Denied)
  }.recover { case e => e }

  private def replaceDomain(user: String, raw: JsObject) = checkPermission(user, ReplaceDomain).map {
    case Granted => DoReplaceDomain(user, JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> eventACL(user)))))
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

  private def removeDomain(user: String) = checkPermission(user, RemoveDomain).flatMap {
    case Granted => clearCache(s"${id}").map {
      case CacheCleared => DoRemoveDomain(user, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
      case other        => other
    }
    case Denied => Future.successful(Denied)
  }.recover { case e => e }

  private def findDomains(user: String, query: JsObject) = checkPermission(user, FindDomains).flatMap {
    case Granted => (collectionRegion ? CollectionActor.FindDocuments(CollectionActor.persistenceId(id, ".domains"), user, query)).map {
      case jo: JsObject => DoFindDomains(user, query, Some(jo))
      case jv: JsValue  => throw new RuntimeException(jv.compactPrint)
    }
    case Denied => Future.successful(Denied)
  }

  private def findCollections(user: String, query: JsObject) = checkPermission(user, FindCollections).flatMap {
    case Granted => (collectionRegion ? CollectionActor.FindDocuments(CollectionActor.persistenceId(id, ".collections"), user, query)).map {
      case jo: JsObject => DoFindCollections(user, query, Some(jo))
      case jv: JsValue  => throw new RuntimeException(jv.compactPrint)
    }
    case Denied => Future.successful(Denied)
  }

  private def findViews(user: String, query: JsObject) = checkPermission(user, FindViews).flatMap {
    case Granted => (collectionRegion ? CollectionActor.FindDocuments(CollectionActor.persistenceId(id, ".views"), user, query)).map {
      case jo: JsObject => DoFindViews(user, query, Some(jo))
      case jv: JsValue  => throw new RuntimeException(jv.compactPrint)
    }
    case Denied => Future.successful(Denied)
  }

  private def findForms(user: String, query: JsObject) = checkPermission(user, FindForms).flatMap {
    case Granted =>
      (collectionRegion ? CollectionActor.FindDocuments(CollectionActor.persistenceId(id, ".forms"), user, query)).map {
        case jo: JsObject => DoFindForms(user, query, Some(jo))
        case jv: JsValue  => throw new RuntimeException(jv.compactPrint)
      }
    case Denied => Future.successful(Denied)
  }

  case class SearchSource(index: String, query: String) extends GraphStage[SourceShape[JsObject]] {
    val out = Outlet[JsObject]("SearchSource.out")
    override val shape: SourceShape[JsObject] = SourceShape[JsObject](out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      private val bufferSize = 1000
      private val buffer = mutable.Queue[JsObject]()
      private var hasMore = true
      private var scrollId: String = _

      private val initScrollUri = s"http://localhost:9200/${index}/_search?scroll=1m&size=${bufferSize / 2}"
      private val scrollUri = s"http://localhost:9200/_search/scroll"

      private val enqueue = getAsyncCallback[JsObject] { element =>
        buffer.enqueue(element)
        if (isAvailable(out)) push(out, buffer.dequeue())
      }

      private val complete = getAsyncCallback[Any] { _ =>
        if (buffer.size > 0 && isAvailable(out))
          push(out, buffer.dequeue())
        else
          completeStage()
      }

      override def preStart(): Unit = fetchDocuments(initScrollUri, query)

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          if (buffer.size > 0) {
            push(out, buffer.dequeue())
            if ((buffer.size < bufferSize / 2) && hasMore) {
              fetchDocuments(scrollUri, s"""{"scroll":"1m","scroll_id":"${scrollId}"}""")
            }
          }
          if (buffer.size == 0 && !hasMore) {
            completeStage()
          }
        }

        override def onDownstreamFinish(): Unit = completeStage()
      })

      private def fetchDocuments(uri: String, entity: String) = search(uri, query).foreach { docs =>
        if (docs.size > 0)
          docs.foreach(enqueue.invoke)
        else complete.invoke(None)
      }

      private def search(uri: String, entity: String) = store.post(uri = uri, entity = entity).map {
        case (StatusCodes.OK, jv) =>
          scrollId = jv.asJsObject.fields("_scroll_id").asInstanceOf[JsString].value
          val elements = jv.asJsObject.fields("hits").asJsObject.fields("hits").asInstanceOf[JsArray].elements
          if (elements.size < bufferSize / 2) hasMore = false
          elements.map { jv => jv.asJsObject }
        case (code, result) => throw new RuntimeException(s"Error search documents: $code-$result")
      }
    }
  }

  private def refreshDomain(user: String) = checkPermission(user, RefreshDomain).flatMap {
    case Granted => id match {
      case `rootDomain` => store.refresh.map {
        case (StatusCodes.OK, _) => DoRefreshDomain(user)
        case (code, result)      => throw new RuntimeException(s"Error refresh domain: $code-$result")
      }
      case _ => store.refresh(id).map {
        case (StatusCodes.OK, _) => DoRefreshDomain(user)
        case (code, result)      => throw new RuntimeException(s"Error refresh domain: $code-$result")
      }
    }
    case Denied => Future.successful(Denied)
  }.recover { case e => e }

  private def garbageCollection(user: String) = checkPermission(user, GarbageCollection).flatMap {
    case Granted =>
      Thread.sleep(1000) // Bugfix: delay for deleting document in ElasticSearch.

      val query = s"""{
        "query":{
          "bool":{
            "must":[
              {"term":{"_metadata.removed":true}}
            ]
          }
        }
      }"""
      id match {
        case `rootDomain` => for {
          r1 <- Source.fromGraph(SearchSource(s"${rootDomain}~.domains~all~snapshots", query)).mapAsync(1) { jo =>
            store.deleteIndices(s"${jo.fields("_source").asJsObject.fields("id").asInstanceOf[JsString].value}~*")
          }.runWith(Sink.ignore)
          r2 <- Source.fromGraph(SearchSource(s"${rootDomain}~.collections~all~snapshots", query)).mapAsync(1) { jo =>
            store.deleteIndices(s"${rootDomain}~${jo.fields("_source").asJsObject.fields("id").asInstanceOf[JsString].value}*")
          }.runWith(Sink.ignore)
          r3 <- Source.fromGraph(SearchSource(s"${rootDomain}~.users~all~snapshots", query)).mapAsync(1) { jo =>
            val subject = jo.fields("_source").asJsObject.fields("id").asInstanceOf[JsString].value
            deleteProfiles(user, subject).flatMap { any => clearACLs(user, subject) }
          }.runWith(Sink.ignore)
          r4 <- store.deleteByQuery(s"${rootDomain}~*", Seq[(String, String)](), query)
        } yield Done
        case _ => for {
          r1 <- Source.fromGraph(SearchSource(s"${id}~.collections~all~snapshots", query)).mapAsync(1) { jo =>
            store.deleteIndices(s"${id}~${jo.fields("_source").asJsObject.fields("id").asInstanceOf[JsString].value}*")
          }.runWith(Sink.ignore)
          r2 <- store.deleteByQuery(s"${id}~*", Seq[(String, String)](), query)
        } yield Done
      }
    case Denied => Future.successful(Denied)
  }.recover { case e => e }

  private def deleteProfiles(user: String, profile: String) = Source.fromGraph(SearchSource("*~.profiles~all~snapshots", s"""{
      "query":{
        "bool":{
          "must":[
            {"term":{"id.keyword":"${profile}"}}
          ],
          "must_not":[
            {"term":{"_metadata.removed":true}}
          ]
        }
      }
    }""")).mapAsync(1) { jo =>
    documentRegion ? RemoveDocument(s"${jo.fields("_index").asInstanceOf[JsString].value.split("~")(0)}~.profiles~${profile}", user)
  }.runWith(Sink.ignore)

  private def clearACLs(user: String, subject: String) = Source.fromGraph(SearchSource("*~all~snapshots", s"""{
      "query":{
        "bool":{
          "should":[
            {"term":{"_metadata.acl.createDomain.users":"${subject}"}},
            {"term":{"_metadata.acl.createCollection.users":"${subject}"}},
            {"term":{"_metadata.acl.createView.users":"${subject}"}},
            {"term":{"_metadata.acl.createForm.users":"${subject}"}},
            {"term":{"_metadata.acl.createRole.users":"${subject}"}},
            {"term":{"_metadata.acl.createProfile.users":"${subject}"}},
            {"term":{"_metadata.acl.createDocument.users":"${subject}"}},
            {"term":{"_metadata.acl.createUser.users":"${subject}"}},
            {"term":{"_metadata.acl.get.users":"${subject}"}},
            {"term":{"_metadata.acl.replace.users":"${subject}"}},
            {"term":{"_metadata.acl.patch.users":"${subject}"}},
            {"term":{"_metadata.acl.remove.users":"${subject}"}},
            {"term":{"_metadata.acl.resetPassword.users":"${subject}"}},
            {"term":{"_metadata.acl.findDomains.users":"${subject}"}},
            {"term":{"_metadata.acl.findCollections.users":"${subject}"}},
            {"term":{"_metadata.acl.findViews.users":"${subject}"}},
            {"term":{"_metadata.acl.findForms.users":"${subject}"}},
            {"term":{"_metadata.acl.findDocuments.users":"${subject}"}},
            {"term":{"_metadata.acl.getACL.users":"${subject}"}},
            {"term":{"_metadata.acl.replaceACL.users":"${subject}"}},
            {"term":{"_metadata.acl.patchACL.users":"${subject}"}},
            {"term":{"_metadata.acl.refresh.users":"${subject}"}},
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

  val commandPermissionMap = Map[Any, String](
    CreateDomain -> "createDomain",
    GetDomain -> "get",
    ReplaceDomain -> "replace",
    PatchDomain -> "patch",
    RemoveDomain -> "remove",
    PatchACL -> "patchACL",
    PatchEventACL -> "patchEventACL",
    JoinDomain -> "join",
    QuitDomain -> "quit",
    CreateCollection -> "createCollection",
    CreateView -> "createView",
    CreateForm -> "createForm",
    CreateRole -> "createRole",
    CreateProfile -> "createProfile",
    CreateUser -> "createUser",
    FindDomains -> "findDomains",
    FindCollections -> "findCollections",
    FindViews -> "findViews",
    FindForms -> "findForms",
    RefreshDomain -> "refresh",
    GarbageCollection -> "gc",
    RemovePermissionSubject -> "removePermissionSubject",
    RemoveEventPermissionSubject -> "removeEventPermissionSubject")

  override def checkPermission(user: String, command: Any) = fetchProfile(id, user).map {
    case Document(_, _, _, _, _, _, profile) =>
      val aclObj = state.domain.raw.fields("_metadata").asJsObject.fields("acl").asJsObject
      val userRoles = profileValue(profile, "roles")
      val userGroups = profileValue(profile, "groups")
      val (aclRoles, aclGroups, aclUsers) = commandPermissionMap.get(command) match {
        case Some(permission) => (aclValue(aclObj, permission, "roles"), aclValue(aclObj, permission, "groups"), aclValue(aclObj, permission, "users"))
        case None             => (Vector[String](), Vector[String](), Vector[String]())
      }
      if (aclRoles.intersect(userRoles).isEmpty && aclGroups.intersect(userGroups).isEmpty && aclUsers.intersect(Vector[String](user, "anonymous")).isEmpty) Denied else Granted
    case _ => Denied
  }

  private def createCollection(userId: String, domainId: String, collectionId: String, raw: JsObject, initFlag: Option[Boolean]) =
    collectionRegion ? CreateCollection(CollectionActor.persistenceId(domainId, collectionId), userId, raw, initFlag)

  private def createView(userId: String, domainId: String, viewId: String, raw: JsObject, initFlag: Option[Boolean]) =
    viewRegion ? CreateView(ViewActor.persistenceId(domainId, viewId), userId, raw, initFlag)

  private def createForm(userId: String, domainId: String, formId: String, raw: JsObject, initFlag: Option[Boolean]) =
    formRegion ? CreateForm(FormActor.persistenceId(domainId, formId), userId, raw, initFlag)

  private def createRole(userId: String, domainId: String, roleId: String, raw: JsObject, initFlag: Option[Boolean]) =
    roleRegion ? CreateRole(RoleActor.persistenceId(domainId, roleId), userId, raw, initFlag)

  private def createProfile(userId: String, domainId: String, profileId: String, raw: JsObject, initFlag: Option[Boolean]) =
    profileRegion ? CreateProfile(ProfileActor.persistenceId(domainId, profileId), userId, raw, initFlag)

  private def createUser(userId: String, domainId: String, newUserId: String, raw: JsObject, initFlag: Option[Boolean]) =
    userRegion ? CreateUser(UserActor.persistenceId(domainId, userId), newUserId, raw, initFlag)

  private def createDocument(userId: String, domainId: String, collectionId: String, docId: String, raw: JsObject, initFlag: Option[Boolean]) =
    documentRegion ? CreateDocument(DocumentActor.persistenceId(domainId, collectionId, docId), userId, raw, initFlag)

  private def initCollections(user: String): Future[Done] = {
    val adminRole = JsObject("description" -> JsString("The administrator role of current domain."))
    val userRole = JsObject("description" -> JsString("The user role of current domain."))
    val profile = JsObject("roles" -> JsArray(JsString("administrator"), JsString("user")))

    for {
      r1 <- createCollection(user, id, ".collections", JsObject("title" -> JsString("Collections")), Some(true))
      r2 <- createCollection(user, id, ".roles", JsObject("title" -> JsString("Roles")), Some(true))
      r3 <- createCollection(user, id, ".profiles", JsObject("title" -> JsString("Profiles")), Some(true))
      r4 <- createCollection(user, id, ".views", JsObject("title" -> JsString("Views")), Some(true))
      r5 <- createCollection(user, id, ".forms", JsObject("title" -> JsString("Forms")), Some(true))

      r6 <- createRole(user, id, "administrator", adminRole, Some(true))
      r7 <- createRole(user, id, "user", userRole, Some(true))
      r8 <- createProfile(user, id, user, profile, Some(true))

      r9 <- createView(user, id, ".collections", JsObject("title" -> JsString("Collections"), "collections" -> JsArray(JsString(".collections"))), Some(true))
      r10 <- createView(user, id, ".roles", JsObject("title" -> JsString("Roles"), "collections" -> JsArray(JsString(".roles"))), Some(true))
      r11 <- createView(user, id, ".profiles", JsObject("title" -> JsString("Profiles"), "collections" -> JsArray(JsString(".profiles"))), Some(true))
      r12 <- createView(user, id, ".views", JsObject("title" -> JsString("Views"), "collections" -> JsArray(JsString(".views"))), Some(true))
      r13 <- createView(user, id, ".forms", JsObject("title" -> JsString("Forms"), "collections" -> JsArray(JsString(".forms"))), Some(true))
    } yield Done
  }

  private def initRootDomainCollections(user: String): Future[Done] = {
    import com.roundeights.hasher.Implicits._
    val adminRole = JsObject("description" -> JsString("The administrator role of current domain."))
    val userRole = JsObject("description" -> JsString("The user role of current domain."))
    val profile = JsObject("roles" -> JsArray(JsString("administrator"), JsString("user")))

    val adminName = system.settings.config.getString("domain.administrator.name")
    val password = system.settings.config.getString("domain.administrator.password")
    val adminUser = JsObject("id" -> JsString(adminName), "password" -> JsString(password))

    for {
      r1 <- createCollection(adminName, rootDomain, ".collections", JsObject("title" -> JsString("Collections")), Some(true))
      r2 <- createCollection(adminName, rootDomain, ".domains", JsObject("title" -> JsString("Domains")), Some(true))
      r3 <- createCollection(adminName, rootDomain, ".users", JsObject("title" -> JsString("Users")), Some(true))
      r4 <- createCollection(adminName, rootDomain, ".roles", JsObject("title" -> JsString("roles")), Some(true))
      r5 <- createCollection(adminName, rootDomain, ".profiles", JsObject("title" -> JsString("Profiles")), Some(true))
      r6 <- createCollection(adminName, rootDomain, ".forms", JsObject("title" -> JsString("Forms")), Some(true))
      r7 <- createCollection(adminName, rootDomain, ".views", JsObject("title" -> JsString("Views")), Some(true))

      r8 <- createUser(adminName, rootDomain, adminName, adminUser, Some(true))
      r9 <- createRole(adminName, rootDomain, "administrator", adminRole, Some(true))
      r10 <- createRole(adminName, rootDomain, "user", userRole, Some(true))
      r11 <- createProfile(adminName, rootDomain, adminName, profile, Some(true))

      r12 <- createView(adminName, rootDomain, ".collections", JsObject("title" -> JsString("Collections"), "collections" -> JsArray(JsString(".collections"))), Some(true))
      r13 <- createView(adminName, rootDomain, ".domains", JsObject("title" -> JsString("Domains"), "collections" -> JsArray(JsString(".domains"))), Some(true))
      r14 <- createView(adminName, rootDomain, ".users", JsObject("title" -> JsString("Users"), "collections" -> JsArray(JsString(".users"))), Some(true))
      r15 <- createView(adminName, rootDomain, ".roles", JsObject("title" -> JsString("Roles"), "collections" -> JsArray(JsString(".roles"))), Some(true))
      r16 <- createView(adminName, rootDomain, ".profiles", JsObject("title" -> JsString("Profiles"), "collections" -> JsArray(JsString(".profiles"))), Some(true))
      r17 <- createView(adminName, rootDomain, ".forms", JsObject("title" -> JsString("Forms"), "collections" -> JsArray(JsString(".forms"))), Some(true))
      r18 <- createView(adminName, rootDomain, ".views", JsObject("title" -> JsString("Views"), "collections" -> JsArray(JsString(".views"))), Some(true))
    } yield Done
  }
}