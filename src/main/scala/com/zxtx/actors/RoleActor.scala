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

object RoleActor {

  def props(): Props = Props[RoleActor]

  object Role {
    val empty = new Role("", "", 0L, 0L, 0L, None, spray.json.JsObject())
  }
  case class Role(id: String, author: String = "anonymous", revision: Long, created: Long, updated: Long, removed: Option[Boolean], raw: JsObject)

  case class GetRole(pid: String, user: String) extends Command
  case class CreateRole(pid: String, user: String, raw: JsObject, initFlag: Option[Boolean] = None) extends Command
  case class ReplaceRole(pid: String, user: String, raw: JsObject) extends Command
  case class PatchRole(pid: String, user: String, patch: JsonPatch) extends Command
  case class RemoveRole(pid: String, user: String) extends Command

  private case class DoGetRole(user: String, request: Option[Any] = None)
  private case class DoCreateRole(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoReplaceRole(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoPatchRole(user: String, patch: JsonPatch, raw: JsObject, request: Option[Any] = None)
  private case class DoRemoveRole(user: String, raw: JsObject, request: Option[Any] = None)

  case class RoleCreated(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case class RoleReplaced(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case class RolePatched(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends Event
  case class RoleRemoved(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event

  case object RoleNotFound extends Exception
  case object RoleAlreadyExists extends Exception
  case object RoleIsCreating extends Exception
  case object RoleSoftRemoved extends Exception
  case class PatchRoleException(exception: Throwable) extends Exception

  val idExtractor: ShardRegion.ExtractEntityId = { case cmd: Command => (cmd.pid, cmd) }
  val shardResolver: ShardRegion.ExtractShardId = { case cmd: Command => (math.abs(cmd.pid.hashCode) % 100).toString }
  val shardName: String = "Roles"

  def persistenceId(domain: String, roleId: String) = s"${domain}~.roles~${roleId}"

  object JsonProtocol extends DocumentJsonProtocol {
    implicit object RoleFormat extends RootJsonFormat[Role] {
      def write(role: Role) = {
        val metaObj = newMetaObject(role.raw.getFields("_metadata"), role.author, role.revision, role.created, role.updated, role.removed)
        JsObject(("id" -> JsString(role.id)) :: role.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, updated, removed, raw) = extractFieldsWithUpdatedRemoved(value, "Role expected!")
        Role(id, author, revision, created, updated, removed, raw)
      }
    }

    implicit object RoleCreatedFormat extends RootJsonFormat[RoleCreated] {
      def write(dc: RoleCreated) = {
        val metaObj = newMetaObject(dc.raw.getFields("_metadata"), dc.author, dc.revision, dc.created)
        JsObject(("id" -> JsString(dc.id)) :: dc.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, raw) = extractFields(value, "RoleCreated event expected!")
        RoleCreated(id, author, revision, created, raw)
      }
    }

    implicit object RoleReplacedFormat extends RootJsonFormat[RoleReplaced] {
      def write(dr: RoleReplaced) = {
        val metaObj = newMetaObject(dr.raw.getFields("_metadata"), dr.author, dr.revision, dr.created)
        JsObject(("id" -> JsString(dr.id)) :: dr.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, raw) = extractFields(value, "RoleReplaced event expected!")
        RoleReplaced(id, author, revision, created, raw)
      }
    }

    implicit object RolePatchedFormat extends RootJsonFormat[RolePatched] {
      import gnieh.diffson.sprayJson.provider._
      import gnieh.diffson.sprayJson.provider.marshall

      def write(dp: RolePatched) = {
        val metaObj = newMetaObject(dp.raw.getFields("_metadata"), dp.author, dp.revision, dp.created)
        spray.json.JsObject(("id" -> JsString(dp.id)) :: ("patch" -> patchValueToString(marshall(dp.patch),"RolePatched event expected!")) :: dp.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, patch, raw) = extractFieldsWithPatch(value, "RolePatched event expected!")
        RolePatched(id, author, revision, created, patch, raw)
      }
    }

    implicit object RoleRemovedFormat extends RootJsonFormat[RoleRemoved] {
      def write(dd: RoleRemoved) = {
        val metaObj = newMetaObject(dd.raw.getFields("_metadata"), dd.author, dd.revision, dd.created)
        JsObject(("id" -> JsString(dd.id)) :: dd.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, raw) = extractFields(value, "RoleRemoved event expected!")
        RoleRemoved(id, author, revision, created, raw)
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

  private case class State(role: Role) {
    def updated(evt: Event): State = evt match {
      case RoleCreated(id, author, revision, created, raw) =>
        val metaFields = raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(metaFields+("author" -> JsString(author))+("revision" -> JsNumber(revision))+("created" -> JsNumber(created))+("updated" -> JsNumber(created))+("acl" -> acl(author)))
        copy(role = Role(id, author, revision, created, created, None, JsObject(raw.fields + ("_metadata" -> metadata))))
      case RoleReplaced(_, _, revision, created, raw) =>
        val oldMetaFields = role.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)))
        copy(role = role.copy(revision = revision, updated = created, raw = JsObject(raw.fields + ("_metadata" -> metadata))))
      case RolePatched(_, _, revision, created, patch, _) =>
        val patchedDoc = patch(role.raw).asJsObject
        val oldMetaFields = patchedDoc.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)))
        copy(role = role.copy(revision = revision, updated = created, raw = JsObject((patchedDoc.fields - "_metadata") + ("_metadata" -> metadata))))
      case RoleRemoved(_, _, revision, created, _) =>
        val oldMetaFields = role.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("removed" -> JsBoolean(true)))
        copy(role = role.copy(revision = revision, updated = created, removed = Some(true), raw = JsObject(role.raw.fields + ("_metadata" -> metadata))))
      case ACLReplaced(_, _, revision, created, raw) =>
        val oldMetadata = role.raw.fields("_metadata").asJsObject
        val replacedACL = JsObject(raw.fields - "_metadata" - "id")
        val metadata = JsObject(oldMetadata.fields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("acl" -> replacedACL))
        copy(role = role.copy(revision = revision, updated = created, raw = JsObject(role.raw.fields + ("_metadata" -> metadata))))
      case ACLPatched(_, _, revision, created, patch, _) =>
        val oldMetadata = role.raw.fields("_metadata").asJsObject
        val patchedACL = patch(oldMetadata.fields("acl"))
        val metadata = JsObject(oldMetadata.fields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("acl" -> patchedACL))
        copy(role = role.copy(revision = revision, updated = created, raw = JsObject(role.raw.fields + ("_metadata" -> metadata))))
      case PermissionSubjectRemoved(_, _, revision, created, raw) =>
        val oldMetadata = role.raw.fields("_metadata").asJsObject
        val operation = raw.fields("operation").asInstanceOf[JsString].value
        val kind = raw.fields("kind").asInstanceOf[JsString].value
        val subject = raw.fields("subject").asInstanceOf[JsString].value
        val acl = doRemovePermissionSubject(oldMetadata.fields("acl").asJsObject, operation, kind, subject)
        val metadata = JsObject(oldMetadata.fields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("acl" -> acl))
        copy(role = role.copy(revision = revision, updated = created, raw = JsObject(role.raw.fields + ("_metadata" -> metadata))))
      case _ => copy(role = role)
    }

    def updated(role: Role): State = role match {
      case Role(id, author, revision, created, updated, removed, raw) => copy(role = Role(id, author, revision, created, updated, removed, raw))
    }
  }
}

class RoleActor extends PersistentActor with ACL with ActorLogging {
  import RoleActor._
  import RoleActor.JsonProtocol._
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

  private var state = State(Role.empty)

  override def receiveRecover: Receive = {
    case evt: RoleCreated =>
      state = state.updated(evt)
      context.become(created)
    case evt: RoleRemoved =>
      state = state.updated(evt)
      context.become(removed)
    case evt: Event =>
      state = state.updated(evt)
    case SnapshotOffer(_, jo: JsObject) =>
      val role = jo.convertTo[Role]
      state = state.updated(role)
      role.removed match {
        case Some(true) => context.become(removed)
        case _          => context.become(created)
      }
    case RecoveryCompleted =>
      log.debug("RoleActor recovery completed.")
  }

  override def receiveCommand: Receive = initial

  def initial: Receive = {
    case CreateRole(_, user, raw, initFlag) =>
      val replyTo = sender
      val parent = context.parent
      createRole(user, raw, initFlag).foreach {
        case dcd: DoCreateRole => self ! dcd.copy(request = Some(replyTo))
        case other =>
          replyTo ! other
          parent ! Passivate(stopMessage = PoisonPill)
      }
      context.become(creating)
    case _: Command => sender ! RoleNotFound
  }

  def creating: Receive = {
    case DoCreateRole(user, raw, Some(replyTo: ActorRef)) =>
      persist(RoleCreated(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        state = state.updated(evt)
        val role = state.role.toJson.asJsObject
        saveSnapshot(role)
        context.become(created)
        replyTo ! state.role
      }
    case _: Command => sender ! RoleIsCreating
  }

  def created: Receive = {
    case GetRole(pid, user) =>
      val replyTo = sender
      getRole(user).foreach {
        case dgd: DoGetRole => replyTo ! state.role
        case other          => replyTo ! other
      }
    case ReplaceRole(_, user, raw) =>
      val replyTo = sender
      replaceRole(user, raw).foreach {
        case drd: DoReplaceRole => self ! drd.copy(request = Some(replyTo))
        case other              => replyTo ! other
      }
    case DoReplaceRole(user, raw, Some(replyTo: ActorRef)) =>
      persist(RoleReplaced(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        replyTo ! updateAndSave(evt)
      }
    case PatchRole(_, user, patch) =>
      val replyTo = sender
      patchRole(user, patch).foreach {
        case dpd: DoPatchRole => self ! dpd.copy(request = Some(replyTo))
        case other            => replyTo ! other
      }
    case DoPatchRole(user, patch, raw, Some(replyTo: ActorRef)) =>
      persist(RolePatched(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, raw)) { evt =>
        replyTo ! updateAndSave(evt)
      }
    case RemoveRole(_, user) =>
      val replyTo = sender
      removeRole(user).foreach {
        case ddd: DoRemoveRole => self ! ddd.copy(request = Some(replyTo))
        case other             => replyTo ! other
      }
    case DoRemoveRole(user, raw, Some(replyTo: ActorRef)) =>
      persist(RoleRemoved(id, user, lastSequenceNr + 1, System.currentTimeMillis(), raw)) { evt =>
        state = state.updated(evt)
        deleteMessages(lastSequenceNr)
        deleteSnapshots(SnapshotSelectionCriteria.Latest)
        val role = state.role.toJson.asJsObject
        saveSnapshot(role)
        context.become(removed)
        replyTo ! evt.copy(raw = role)
        context.parent ! Passivate(stopMessage = PoisonPill)
      }
    case GetACL(_, user) =>
      val replyTo = sender
      getACL(user).foreach {
        case dga: DoGetACL => replyTo ! state.role.raw.fields("_metadata").asJsObject.fields("acl")
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
      patchACL(user, state.role.raw.fields("_metadata").asJsObject.fields("acl"), patch).foreach {
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
    case _: CreateRole => sender ! RoleAlreadyExists
    case _: DomainRemoved | _: CollectionActor.CollectionRemoved => context.parent ! Passivate(stopMessage = PoisonPill)
  }

  def removed: Receive = {
    case GetRole(_, user) =>
      val replyTo = sender
      val parent = context.parent
      getRole(user).foreach {
        case dgd: DoGetRole =>
          replyTo ! state.role
          parent ! Passivate(stopMessage = PoisonPill)
        case other =>
          replyTo ! other
          parent ! Passivate(stopMessage = PoisonPill)
      }
    case _: Command =>
      sender ! RoleSoftRemoved
      context.parent ! Passivate(stopMessage = PoisonPill)
  }

  override def unhandled(msg: Any): Unit = msg match {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
    case _              => super.unhandled(msg)
  }

  private def updateAndSave(evt: Event) = {
    state = state.updated(evt)
    deleteSnapshots(SnapshotSelectionCriteria.Latest)
    saveSnapshot(state.role.toJson.asJsObject)
    state.role
  }

  private def createRole(user: String, raw: JsObject, initFlag: Option[Boolean]) = {
    val dcd = DoCreateRole(user, JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> eventACL(user),"type" -> JsString("role")))))
    initFlag match {
      case Some(true) => Future.successful(dcd)
      case other => (domainRegion ? CheckPermission(DomainActor.persistenceId(rootDomain, domain), user, CreateRole)).map {
        case Granted => dcd
        case Denied  => Denied
      }
    }
  }

  private def getRole(user: String) = checkPermission(user, GetRole).map {
    case Granted => DoGetRole(user)
    case other   => other
  }.recover { case e => e }

  private def replaceRole(user: String, raw: JsObject) = checkPermission(user, ReplaceRole).map {
    case Granted => DoReplaceRole(user, JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> eventACL(user)))))
    case other   => other
  }.recover { case e => e }

  private def patchRole(user: String, patch: JsonPatch) = checkPermission(user, PatchRole).map {
    case Granted =>
      Try {
        patch(state.role.raw)
      } match {
        case Success(_) => DoPatchRole(user, patch, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
        case Failure(e) => PatchRoleException(e)
      }
    case other => other
  }.recover { case e => e }

  private def removeRole(user: String) = checkPermission(user, RemoveRole).map {
    case Granted => DoRemoveRole(user, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
    case other   => other
  }.recover { case e => e }

  val commandPermissionMap = Map[Any, String](
    GetRole -> "get",
    ReplaceRole -> "replace",
    PatchRole -> "patch",
    RemoveRole -> "remove",
    GetACL -> "getACL",
    ReplaceACL -> "replaceACL",
    PatchACL -> "patchACL",
    PatchEventACL -> "patchEventACL",
    RemovePermissionSubject -> "removePermissionSubject",
    RemoveEventPermissionSubject -> "removeEventPermissionSubject")

  override def checkPermission(user: String, command: Any): Future[Permission] = hitCache(s"${domain}").flatMap {
    case Some(true) => hitCache(s"${domain}~${collection}").flatMap {
      case Some(true) => (collection, id) match {
        case (".profiles", `user`) => Future.successful(Granted)
        case _ => fetchProfile(domain, user).map {
          case Document(_, _, _, _, _, _, profile) =>
            val aclObj = state.role.raw.fields("_metadata").asJsObject.fields("acl").asJsObject
            val userRoles = profileValue(profile, "roles")
            val userGroups = profileValue(profile, "groups")
            val (aclRoles, aclGroups, aclUsers) = commandPermissionMap.get(command) match {
              case Some(permission) => (aclValue(aclObj, permission, "roles"), aclValue(aclObj, permission, "groups"), aclValue(aclObj, permission, "users"))
              case None             => (Vector[String](), Vector[String](), Vector[String]())
            }
            if (aclRoles.intersect(userRoles).isEmpty && aclGroups.intersect(userGroups).isEmpty && aclUsers.intersect(Vector[String](user,"anonymous")).isEmpty) Denied else Granted
          case _ => Denied
        }
      }
      case _ => Future.successful(Granted)
    }
    case _ => Future.successful(Granted)
  }

}
