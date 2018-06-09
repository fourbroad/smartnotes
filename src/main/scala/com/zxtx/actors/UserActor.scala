package com.zxtx.actors

import scala.collection._
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.roundeights.hasher.Implicits.stringToHasher
import com.zxtx.actors.ACL._
import com.zxtx.actors.DocumentActor.Document

import akka.Done
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.cluster.ddata.Replicator.ReadMajority
import akka.cluster.ddata.Replicator.WriteMajority
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
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
import gnieh.diffson.sprayJson.JsonPatch
import spray.json.JsArray
import spray.json.JsBoolean
import spray.json.JsNumber
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.json.enrichAny
import spray.json.enrichString
import akka.stream.ActorMaterializerSettings
import akka.stream.ActorMaterializer
import akka.persistence.SnapshotSelectionCriteria

object UserActor {

  def props(): Props = Props[UserActor]

  object User {
    val empty = new User("", "", 0L, 0L, 0L, None, JsObject())
  }
  case class User(id: String, author: String = "anonymous", revision: Long, created: Long, updated: Long, removed: Option[Boolean], raw: JsObject)

  case class CreateUser(pid: String, user: String, raw: JsObject) extends Command
  case class GetUser(pid: String, user: String) extends Command
  case class ReplaceUser(pid: String, user: String, raw: JsObject) extends Command
  case class PatchUser(pid: String, user: String, patch: JsonPatch) extends Command
  case class RemoveUser(pid: String, user: String) extends Command
  case class ResetPassword(pid: String, user: String, newPassword: String) extends Command

  private case class DoCreateUser(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoGetUser(user: String, request: Option[Any] = None)
  private case class DoReplaceUser(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoPatchUser(user: String, patch: JsonPatch, raw: JsObject, request: Option[Any] = None)
  private case class DoRemoveUser(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoResetPassword(user: String, md5Password: String, raw: JsObject, request: Option[Any] = None)

  case class UserCreated(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case class UserReplaced(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case class UserPatched(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends Event
  case class UserRemoved(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case class PasswordReset(id: String, author: String, revision: Long, created: Long, md5Password: String, raw: JsObject) extends Event

  case object UserNotFound extends Exception
  case object UserAlreadyExists extends Exception
  case object UserIsCreating extends Exception
  case object UserSoftRemoved extends Exception
  case object UserAlreadyRegistered extends Exception
  case object UserIdPasswordError extends Exception
  case object UserIdNotExists extends Exception
  case object PasswordNotExists extends Exception
  case class PatchUserException(exception: Throwable) extends Exception

  val idExtractor: ShardRegion.ExtractEntityId = { case cmd: Command => (cmd.pid, cmd) }
  val shardResolver: ShardRegion.ExtractShardId = { case cmd: Command => (math.abs(cmd.pid.hashCode) % 100).toString }
  val shardName: String = "Users"

  def persistenceId(rootDomain: String, userId: String): String = s"${rootDomain}~.users~${userId}"

  object JsonProtocol extends DocumentJsonProtocol {
    implicit object UserFormat extends RootJsonFormat[User] {
      def write(d: User) = {
        val metaObj = newMetaObject(d.raw.getFields("_metadata"), d.author, d.revision, d.created, d.updated, d.removed)
        JsObject(("id" -> JsString(d.id)) :: d.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, updated, removed, jo) = extractFieldsWithUpdatedRemoved(value, "User expected!")
        User(id, author, revision, created, updated, removed, jo)
      }
    }

    implicit object UserCreatedFormat extends RootJsonFormat[UserCreated] {
      def write(dc: UserCreated) = {
        val metaObj = newMetaObject(dc.raw.getFields("_metadata"), dc.author, dc.revision, dc.created)
        JsObject(("id" -> JsString(dc.id)) :: dc.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "UserCreated event expected!")
        UserCreated(id, author, revision, created, jo)
      }
    }

    implicit object UserReplacedFormat extends RootJsonFormat[UserReplaced] {
      def write(dr: UserReplaced) = {
        val metaObj = newMetaObject(dr.raw.getFields("_metadata"), dr.author, dr.revision, dr.created)
        JsObject(("id" -> JsString(dr.id)) :: dr.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "UserReplaced event expected!")
        UserReplaced(id, author, revision, created, jo)
      }
    }

    implicit object UserPatchedFormat extends RootJsonFormat[UserPatched] {
      import gnieh.diffson.sprayJson.provider.marshall
      import gnieh.diffson.sprayJson.provider.patchMarshaller
      def write(dp: UserPatched) = {
        val metaObj = newMetaObject(dp.raw.getFields("_metadata"), dp.author, dp.revision, dp.created)
        JsObject(("id" -> JsString(dp.id)) :: ("patch" -> marshall(dp.patch)) :: dp.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, patch, jo) = extractFieldsWithPatch(value, "UserPatched event expected!")
        UserPatched(id, author, revision, created, patch, jo)
      }
    }

    implicit object UserRemovedFormat extends RootJsonFormat[UserRemoved] {
      def write(dd: UserRemoved) = {
        val metaObj = newMetaObject(dd.raw.getFields("_metadata"), dd.author, dd.revision, dd.created)
        JsObject(("id" -> JsString(dd.id)) :: dd.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "UserRemoved event expected!")
        UserRemoved(id, author, revision, created, jo)
      }
    }

    implicit object PasswordResetedFormat extends RootJsonFormat[PasswordReset] {
      def write(pr: PasswordReset) = {
        val metaObj = newMetaObject(pr.raw.getFields("_metadata"), pr.author, pr.revision, pr.created)
        JsObject(("id" -> JsString(pr.id)) :: ("password" -> JsString(pr.md5Password)) :: pr.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, password, jo) = extractFieldsWithPassword(value, "PasswordReseted event expected!")
        PasswordReset(id, author, revision, created, password, jo)
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
        "resetPassword":{
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

  private case class State(user: User, removed: Boolean) {
    def updated(evt: Event): State = evt match {
      case UserCreated(id, author, revision, created, raw) =>
        val user = author match {
          case "anonymous" => raw.fields("id").asInstanceOf[JsString].value
          case other       => other
        }
        val metadata = JsObject("author" -> JsString(author), "revision" -> JsNumber(revision), "created" -> JsNumber(created), "updated" -> JsNumber(created), "acl" -> acl(user))
        copy(user = User(id, author, revision, created, created, None, JsObject(raw.fields + ("_metadata" -> metadata))))
      case UserReplaced(_, _, revision, created, raw) =>
        val oldMetaFields = user.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)))
        copy(user = user.copy(revision = revision, updated = created, raw = JsObject(raw.fields + ("_metadata" -> metadata))))
      case UserPatched(_, _, revision, created, patch, _) =>
        val patchedDoc = patch(user.raw).asJsObject
        val oldMetaFields = patchedDoc.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)))
        copy(user = user.copy(revision = revision, updated = created, raw = JsObject(patchedDoc.fields + ("_metadata" -> metadata))))
      case UserRemoved(_, _, revision, created, _) =>
        val oldMetaFields = user.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("removed" -> JsBoolean(true)))
        copy(user = user.copy(revision = revision, updated = created, removed = Some(true), raw = JsObject(user.raw.fields + ("_metadata" -> metadata))))
      case PasswordReset(_, _, revision, created, password, _) =>
        val oldMetaFields = user.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)))
        copy(user = user.copy(revision = revision, updated = created, raw = JsObject(user.raw.fields + ("password" -> JsString(password)) + ("_metadata" -> metadata))))
      case ACLReplaced(_, _, revision, created, raw) =>
        val oldMetadata = user.raw.fields("_metadata").asJsObject
        val replacedACL = JsObject(raw.fields - "_metadata" - "id")
        val metadata = JsObject(oldMetadata.fields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("acl" -> replacedACL))
        copy(user = user.copy(revision = revision, updated = created, raw = JsObject(user.raw.fields + ("_metadata" -> metadata))))
      case ACLPatched(_, _, revision, created, patch, _) =>
        val oldMetadata = user.raw.fields("_metadata").asJsObject
        val patchedACL = patch(oldMetadata.fields("acl"))
        val metadata = JsObject(oldMetadata.fields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("acl" -> patchedACL))
        copy(user = user.copy(revision = revision, updated = created, raw = JsObject(user.raw.fields + ("_metadata" -> metadata))))
      case PermissionSubjectRemoved(_, _, revision, created, raw) =>
        val oldMetadata = user.raw.fields("_metadata").asJsObject
        val operation = raw.fields("operation").asInstanceOf[JsString].value
        val kind = raw.fields("kind").asInstanceOf[JsString].value
        val subject = raw.fields("subject").asInstanceOf[JsString].value
        val acl = doRemovePermissionSubject(oldMetadata.fields("acl").asJsObject, operation, kind, subject)
        val metadata = JsObject(oldMetadata.fields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("acl" -> acl))
        copy(user = user.copy(revision = revision, updated = created, raw = JsObject(user.raw.fields + ("_metadata" -> metadata))))
      case _ => copy(user = user)
    }

    def updated(d: User): State = d match {
      case User(id, author, revision, created, updated, removed, raw) => copy(user = User(id, author, revision, created, updated, removed, raw))
    }
  }

}

class UserActor extends PersistentActor with ACL with ActorLogging {
  import CollectionActor._
  import DocumentActor._
  import UserActor._
  import UserActor.JsonProtocol._

  val rootDomain = system.settings.config.getString("domain.root-domain")
  val adminName = system.settings.config.getString("domain.administrator.name")
  val domainRegion = ClusterSharding(system).shardRegion(UserActor.shardName)
  val collectionRegion = ClusterSharding(system).shardRegion(CollectionActor.shardName)

  val userCollectionId = s"${rootDomain}~.collections~.users"

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

  private var state = State(User.empty, false)

  override def receiveRecover: Receive = {
    case evt: UserCreated =>
      context.become(created)
      state = state.updated(evt)
    case evt: UserRemoved =>
      context.become(removed)
      state = state.updated(evt)
    case evt: Event =>
      state = state.updated(evt)
    case SnapshotOffer(_, jo: JsObject) =>
      val d = jo.convertTo[User]
      state = state.updated(d)
      d.removed match {
        case Some(true) => context.become(removed)
        case _          => context.become(created)
      }
    case RecoveryCompleted =>
      log.debug("UserActor recovery completed.")
  }

  override def receiveCommand: Receive = initial

  def initial: Receive = {
    case CreateUser(_, token, raw) =>
      val replyTo = sender
      val parent = context.parent
      createUser(token, raw).foreach {
        case dcd: DoCreateUser => self ! dcd.copy(request = Some(replyTo))
        case other =>
          replyTo ! other
          parent ! Passivate(stopMessage = PoisonPill)
      }
      context.become(creating)
    case _: Command => sender ! UserNotFound
  }

  def creating: Receive = {
    case DoCreateUser(user, raw, Some(replyTo: ActorRef)) =>
      persist(UserCreated(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        state = state.updated(evt)
        saveSnapshot(state.user.toJson.asJsObject)
        context.become(created)
        replyTo ! state.user
      }
    case _: Command => sender ! UserIsCreating
  }

  def created: Receive = {
    case GetUser(pid, user) =>
      val replyTo = sender
      getUser(user).foreach {
        case dgu: DoGetUser => replyTo ! state.user
        case other          => replyTo ! other
      }
    case ReplaceUser(_, user, raw) =>
      val replyTo = sender
      replaceUser(user, raw).foreach {
        case dru: DoReplaceUser => self ! dru.copy(request = Some(replyTo))
        case other              => replyTo ! other
      }
    case DoReplaceUser(user, raw, Some(replyTo: ActorRef)) =>
      persist(UserReplaced(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        replyTo ! updateAndSave(evt)
      }
    case PatchUser(_, user, patch) =>
      val replyTo = sender
      patchUser(user, patch).foreach {
        case dpu: DoPatchUser => self ! dpu.copy(request = Some(replyTo))
        case other            => replyTo ! other
      }
    case DoPatchUser(user, patch, raw, Some(replyTo: ActorRef)) =>
      persist(UserPatched(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, raw)) { evt =>
        replyTo ! updateAndSave(evt)
      }
    case RemoveUser(_, user) =>
      val replyTo = sender
      removeUser(user).foreach {
        case ddu: DoRemoveUser => self ! ddu.copy(request = Some(replyTo))
        case other             => replyTo ! other
      }
    case DoRemoveUser(user, raw, Some(replyTo: ActorRef)) =>
      persist(UserRemoved(id, user, lastSequenceNr + 1, System.currentTimeMillis(), raw)) { evt =>
        state = state.updated(evt)
        deleteMessages(lastSequenceNr)
        deleteSnapshots(SnapshotSelectionCriteria.Latest)        
        saveSnapshot(state.user.toJson.asJsObject)
        context.become(removed)
        replyTo ! evt
        context.parent ! Passivate(stopMessage = PoisonPill)
      }
    case GetACL(_, user) =>
      val replyTo = sender
      getACL(user).foreach {
        case dga: DoGetACL => replyTo ! state.user.raw.fields("_metadata").asJsObject.fields("acl")
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
      patchACL(user, state.user.raw.fields("_metadata").asJsObject.fields("acl"), patch).foreach {
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
    case ResetPassword(_, user, newPassword) =>
      val replyTo = sender
      resetPassword(user, newPassword).foreach {
        case drp: DoResetPassword => self ! drp.copy(request = Some(replyTo))
        case other                => replyTo ! other
      }
    case DoResetPassword(user, md5Password, raw, Some(replyTo: ActorRef)) =>
      persist(PasswordReset(id, user, lastSequenceNr + 1, System.currentTimeMillis, md5Password, raw)) { evt =>
        updateAndSave(evt)
        replyTo ! evt
      }
    case SaveSnapshotSuccess(metadata)         =>
    case SaveSnapshotFailure(metadata, reason) =>
    case CreateUser(_, _, _)                   => sender ! UserAlreadyExists
  }

  def removed: Receive = {
    case GetUser(_, user) =>
      val replyTo = sender
      val parent = context.parent
      getUser(user).foreach {
        case dgd: DoGetUser =>
          self ! state.user
          parent ! Passivate(stopMessage = PoisonPill)
        case other =>
          replyTo ! other
          parent ! Passivate(stopMessage = PoisonPill)
      }
    case _: Command => sender ! UserSoftRemoved
  }

  override def unhandled(msg: Any): Unit = msg match {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
    case _              => super.unhandled(msg)
  }

  private def updateAndSave(evt: Event) = {
    state = state.updated(evt)
    deleteSnapshots(SnapshotSelectionCriteria.Latest)    
    saveSnapshot(state.user.toJson.asJsObject)
    state.user
  }

  private def createUser(user: String, userInfo: JsObject) = (collectionRegion ? CheckPermission(userCollectionId, user, CreateDocument)).map {
    case Granted =>
      val fields = userInfo.fields
      fields.get("id") match {
        case Some(JsString(id)) =>
          fields.get("password") match {
            case Some(JsString(password)) => DoCreateUser(user, JsObject(fields + ("password" -> JsString(password.md5.hex)) + ("_metadata" -> JsObject("acl" -> eventACL(user)))))
            case None                     => PasswordNotExists
          }
        case None => UserIdNotExists
      }
    case Denied => Denied
  }.recover { case e => e }

  private def getUser(user: String) = checkPermission(user, GetUser).map {
    case Granted => DoGetUser(user)
    case other   => other
  }.recover { case e => e }

  private def replaceUser(user: String, raw: JsObject) = checkPermission(user, ReplaceUser).map {
    case Granted => DoReplaceUser(user, JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> eventACL(user)))))
    case Denied  => Denied
  }.recover { case e => e }

  private def patchUser(user: String, patch: JsonPatch) = checkPermission(user, PatchUser).map {
    case Granted =>
      Try {
        patch(state.user.raw)
      } match {
        case Success(_) => DoPatchUser(user, patch, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
        case Failure(e) => PatchUserException(e)
      }
    case Denied => Denied
  }.recover { case e => e }

  private def removeUser(user: String) = checkPermission(user, RemoveUser).map {
    case Granted => DoRemoveUser(user, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
    case Denied  => Denied
  }.recover { case e => e }

  private def resetPassword(user: String, newPassword: String) = checkPermission(user, ResetPassword).map {
    case Granted => DoResetPassword(user, newPassword.md5.hex, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
    case Denied  => Denied
  }.recover { case e => e }

  val commandPermissionMap = Map[Any, String](
    GetUser -> "get",
    ReplaceUser -> "replace",
    PatchUser -> "patch",
    RemoveUser -> "remove",
    ResetPassword -> "resetPassword",
    GetACL -> "getACL",
    ReplaceACL -> "replaceACL",
    PatchACL -> "patchACL",
    PatchEventACL -> "patchEventACL",
    RemovePermissionSubject -> "removePermissionSubject",
    RemoveEventPermissionSubject -> "removeEventPermissionSubject")

  override def checkPermission(user: String, command: Any) = fetchProfile(rootDomain, user).map {
    case Document(_, _, _, _, _, _, profile) =>
      val aclObj = state.user.raw.fields("_metadata").asJsObject.fields("acl").asJsObject
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
