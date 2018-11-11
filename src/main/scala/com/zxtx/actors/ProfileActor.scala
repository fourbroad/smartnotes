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

object ProfileActor {

  def props(): Props = Props[ProfileActor]

  object Profile {
    val empty = new Profile("", "", 0L, 0L, 0L, None, spray.json.JsObject())
  }
  case class Profile(id: String, author: String = "anonymous", revision: Long, created: Long, updated: Long, removed: Option[Boolean], raw: JsObject)

  case class GetProfile(pid: String, user: String) extends Command
  case class CreateProfile(pid: String, user: String, raw: JsObject, initFlag: Option[Boolean] = None) extends Command
  case class ReplaceProfile(pid: String, user: String, raw: JsObject) extends Command
  case class PatchProfile(pid: String, user: String, patch: JsonPatch) extends Command
  case class RemoveProfile(pid: String, user: String) extends Command

  private case class DoGetProfile(user: String, request: Option[Any] = None)
  private case class DoCreateProfile(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoReplaceProfile(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoPatchProfile(user: String, patch: JsonPatch, raw: JsObject, request: Option[Any] = None)
  private case class DoRemoveProfile(user: String, raw: JsObject, request: Option[Any] = None)

  case class ProfileCreated(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case class ProfileReplaced(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case class ProfilePatched(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends Event
  case class ProfileRemoved(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event

  case object ProfileNotFound extends Exception
  case object ProfileAlreadyExists extends Exception
  case object ProfileIsCreating extends Exception
  case object ProfileSoftRemoved extends Exception
  case class PatchProfileException(exception: Throwable) extends Exception

  val idExtractor: ShardRegion.ExtractEntityId = { case cmd: Command => (cmd.pid, cmd) }
  val shardResolver: ShardRegion.ExtractShardId = { case cmd: Command => (math.abs(cmd.pid.hashCode) % 100).toString }
  val shardName: String = "Profiles"

  def persistenceId(domainId: String, profileId: String) = s"${domainId}~.profiles~${profileId}"

  object JsonProtocol extends DocumentJsonProtocol {
    implicit object ProfileFormat extends RootJsonFormat[Profile] {
      def write(profile: Profile) = {
        val metaObj = newMetaObject(profile.raw.getFields("_metadata"), profile.author, profile.revision, profile.created, profile.updated, profile.removed)
        JsObject(("id" -> JsString(profile.id)) :: profile.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, updated, removed, raw) = extractFieldsWithUpdatedRemoved(value, "Profile expected!")
        Profile(id, author, revision, created, updated, removed, raw)
      }
    }

    implicit object ProfileCreatedFormat extends RootJsonFormat[ProfileCreated] {
      def write(dc: ProfileCreated) = {
        val metaObj = newMetaObject(dc.raw.getFields("_metadata"), dc.author, dc.revision, dc.created)
        JsObject(("id" -> JsString(dc.id)) :: dc.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, raw) = extractFields(value, "ProfileCreated event expected!")
        ProfileCreated(id, author, revision, created, raw)
      }
    }

    implicit object ProfileReplacedFormat extends RootJsonFormat[ProfileReplaced] {
      def write(dr: ProfileReplaced) = {
        val metaObj = newMetaObject(dr.raw.getFields("_metadata"), dr.author, dr.revision, dr.created)
        JsObject(("id" -> JsString(dr.id)) :: dr.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, raw) = extractFields(value, "ProfileReplaced event expected!")
        ProfileReplaced(id, author, revision, created, raw)
      }
    }

    implicit object ProfilePatchedFormat extends RootJsonFormat[ProfilePatched] {
      import gnieh.diffson.sprayJson.provider._
      import gnieh.diffson.sprayJson.provider.marshall

      def write(dp: ProfilePatched) = {
        val metaObj = newMetaObject(dp.raw.getFields("_metadata"), dp.author, dp.revision, dp.created)
        spray.json.JsObject(("id" -> JsString(dp.id)) :: ("patch" -> patchValueToString(marshall(dp.patch),"ProfilePatched event expected!")) :: dp.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, patch, raw) = extractFieldsWithPatch(value, "ProfilePatched event expected!")
        ProfilePatched(id, author, revision, created, patch, raw)
      }
    }

    implicit object ProfileRemovedFormat extends RootJsonFormat[ProfileRemoved] {
      def write(dd: ProfileRemoved) = {
        val metaObj = newMetaObject(dd.raw.getFields("_metadata"), dd.author, dd.revision, dd.created)
        JsObject(("id" -> JsString(dd.id)) :: dd.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, raw) = extractFields(value, "ProfileRemoved event expected!")
        ProfileRemoved(id, author, revision, created, raw)
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

  private case class State(profile: Profile) {
    def updated(evt: Event): State = evt match {
      case ProfileCreated(id, author, revision, created, raw) =>
        val metaFields = raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(metaFields+("author" -> JsString(author))+("revision" -> JsNumber(revision))+("created" -> JsNumber(created))+("updated" -> JsNumber(created))+("acl" -> acl(author)))
        copy(profile = Profile(id, author, revision, created, created, None, JsObject(raw.fields + ("_metadata" -> metadata))))
      case ProfileReplaced(_, _, revision, created, raw) =>
        val oldMetaFields = profile.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)))
        copy(profile = profile.copy(revision = revision, updated = created, raw = JsObject(raw.fields + ("_metadata" -> metadata))))
      case ProfilePatched(_, _, revision, created, patch, _) =>
        val patchedDoc = patch(profile.raw).asJsObject
        val oldMetaFields = patchedDoc.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)))
        copy(profile = profile.copy(revision = revision, updated = created, raw = JsObject((patchedDoc.fields - "_metadata") + ("_metadata" -> metadata))))
      case ProfileRemoved(_, _, revision, created, _) =>
        val oldMetaFields = profile.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("removed" -> JsBoolean(true)))
        copy(profile = profile.copy(revision = revision, updated = created, removed = Some(true), raw = JsObject(profile.raw.fields + ("_metadata" -> metadata))))
      case ACLReplaced(_, _, revision, created, raw) =>
        val oldMetadata = profile.raw.fields("_metadata").asJsObject
        val replacedACL = JsObject(raw.fields - "_metadata" - "id")
        val metadata = JsObject(oldMetadata.fields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("acl" -> replacedACL))
        copy(profile = profile.copy(revision = revision, updated = created, raw = JsObject(profile.raw.fields + ("_metadata" -> metadata))))
      case ACLPatched(_, _, revision, created, patch, _) =>
        val oldMetadata = profile.raw.fields("_metadata").asJsObject
        val patchedACL = patch(oldMetadata.fields("acl"))
        val metadata = JsObject(oldMetadata.fields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("acl" -> patchedACL))
        copy(profile = profile.copy(revision = revision, updated = created, raw = JsObject(profile.raw.fields + ("_metadata" -> metadata))))
      case PermissionSubjectRemoved(_, _, revision, created, raw) =>
        val oldMetadata = profile.raw.fields("_metadata").asJsObject
        val operation = raw.fields("operation").asInstanceOf[JsString].value
        val kind = raw.fields("kind").asInstanceOf[JsString].value
        val subject = raw.fields("subject").asInstanceOf[JsString].value
        val acl = doRemovePermissionSubject(oldMetadata.fields("acl").asJsObject, operation, kind, subject)
        val metadata = JsObject(oldMetadata.fields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("acl" -> acl))
        copy(profile = profile.copy(revision = revision, updated = created, raw = JsObject(profile.raw.fields + ("_metadata" -> metadata))))
      case _ => copy(profile = profile)
    }

    def updated(profile: Profile): State = profile match {
      case Profile(id, author, revision, created, updated, removed, raw) => copy(profile = Profile(id, author, revision, created, updated, removed, raw))
    }
  }
}

class ProfileActor extends PersistentActor with ACL with ActorLogging {
  import ProfileActor._
  import ProfileActor.JsonProtocol._
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

  private var state = State(Profile.empty)

  override def receiveRecover: Receive = {
    case evt: ProfileCreated =>
      state = state.updated(evt)
      context.become(created)
    case evt: ProfileRemoved =>
      state = state.updated(evt)
      context.become(removed)
    case evt: Event =>
      state = state.updated(evt)
    case SnapshotOffer(_, jo: JsObject) =>
      val profile = jo.convertTo[Profile]
      state = state.updated(profile)
      profile.removed match {
        case Some(true) => context.become(removed)
        case _          => context.become(created)
      }
    case RecoveryCompleted =>
      log.debug("ProfileActor recovery completed.")
  }

  override def receiveCommand: Receive = initial

  def initial: Receive = {
    case CreateProfile(_, user, raw, initFlag) =>
      val replyTo = sender
      val parent = context.parent
      createProfile(user, raw, initFlag).foreach {
        case dcd: DoCreateProfile => self ! dcd.copy(request = Some(replyTo))
        case other =>
          replyTo ! other
          parent ! Passivate(stopMessage = PoisonPill)
      }
      context.become(creating)
    case _: Command => sender ! ProfileNotFound
  }

  def creating: Receive = {
    case DoCreateProfile(user, raw, Some(replyTo: ActorRef)) =>
      persist(ProfileCreated(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        state = state.updated(evt)
        val profile = state.profile.toJson.asJsObject
        saveSnapshot(profile)
        context.become(created)
        replyTo ! state.profile
      }
    case _: Command => sender ! ProfileIsCreating
  }

  def created: Receive = {
    case GetProfile(pid, user) =>
      val replyTo = sender
      getProfile(user).foreach {
        case dgd: DoGetProfile => replyTo ! state.profile
        case other          => replyTo ! other
      }
    case ReplaceProfile(_, user, raw) =>
      val replyTo = sender
      replaceProfile(user, raw).foreach {
        case drd: DoReplaceProfile => self ! drd.copy(request = Some(replyTo))
        case other              => replyTo ! other
      }
    case DoReplaceProfile(user, raw, Some(replyTo: ActorRef)) =>
      persist(ProfileReplaced(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        replyTo ! updateAndSave(evt)
      }
    case PatchProfile(_, user, patch) =>
      val replyTo = sender
      patchProfile(user, patch).foreach {
        case dpd: DoPatchProfile => self ! dpd.copy(request = Some(replyTo))
        case other            => replyTo ! other
      }
    case DoPatchProfile(user, patch, raw, Some(replyTo: ActorRef)) =>
      persist(ProfilePatched(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, raw)) { evt =>
        replyTo ! updateAndSave(evt)
      }
    case RemoveProfile(_, user) =>
      val replyTo = sender
      removeProfile(user).foreach {
        case ddd: DoRemoveProfile => self ! ddd.copy(request = Some(replyTo))
        case other             => replyTo ! other
      }
    case DoRemoveProfile(user, raw, Some(replyTo: ActorRef)) =>
      persist(ProfileRemoved(id, user, lastSequenceNr + 1, System.currentTimeMillis(), raw)) { evt =>
        state = state.updated(evt)
        deleteMessages(lastSequenceNr)
        deleteSnapshots(SnapshotSelectionCriteria.Latest)
        val profile = state.profile.toJson.asJsObject
        saveSnapshot(profile)
        context.become(removed)
        replyTo ! evt.copy(raw = profile)
        context.parent ! Passivate(stopMessage = PoisonPill)
      }
    case GetACL(_, user) =>
      val replyTo = sender
      getACL(user).foreach {
        case dga: DoGetACL => replyTo ! state.profile.raw.fields("_metadata").asJsObject.fields("acl")
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
      patchACL(user, state.profile.raw.fields("_metadata").asJsObject.fields("acl"), patch).foreach {
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
    case _: CreateProfile => sender ! ProfileAlreadyExists
    case _: DomainRemoved | _: CollectionActor.CollectionRemoved => context.parent ! Passivate(stopMessage = PoisonPill)
  }

  def removed: Receive = {
    case GetProfile(_, user) =>
      val replyTo = sender
      val parent = context.parent
      getProfile(user).foreach {
        case dgd: DoGetProfile =>
          replyTo ! state.profile
          parent ! Passivate(stopMessage = PoisonPill)
        case other =>
          replyTo ! other
          parent ! Passivate(stopMessage = PoisonPill)
      }
    case _: Command =>
      sender ! ProfileSoftRemoved
      context.parent ! Passivate(stopMessage = PoisonPill)
  }

  override def unhandled(msg: Any): Unit = msg match {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
    case _              => super.unhandled(msg)
  }

  private def updateAndSave(evt: Event) = {
    state = state.updated(evt)
    deleteSnapshots(SnapshotSelectionCriteria.Latest)
    saveSnapshot(state.profile.toJson.asJsObject)
    state.profile
  }

  private def createProfile(user: String, raw: JsObject, initFlag: Option[Boolean]) = {
    val dcd = DoCreateProfile(user, JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> eventACL(user),"type" -> JsString("profile")))))
    initFlag match {
      case Some(true) => Future.successful(dcd)
      case other => (domainRegion ? CheckPermission(DomainActor.persistenceId(rootDomain, domain), user, CreateProfile)).map {
        case Granted => dcd
        case Denied  => Denied
      }
    }
  }

  private def getProfile(user: String) = checkPermission(user, GetProfile).map {
    case Granted => DoGetProfile(user)
    case other   => other
  }.recover { case e => e }

  private def replaceProfile(user: String, raw: JsObject) = checkPermission(user, ReplaceProfile).map {
    case Granted => DoReplaceProfile(user, JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> eventACL(user)))))
    case other   => other
  }.recover { case e => e }

  private def patchProfile(user: String, patch: JsonPatch) = checkPermission(user, PatchProfile).map {
    case Granted =>
      Try {
        patch(state.profile.raw)
      } match {
        case Success(_) => DoPatchProfile(user, patch, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
        case Failure(e) => PatchProfileException(e)
      }
    case other => other
  }.recover { case e => e }

  private def removeProfile(user: String) = checkPermission(user, RemoveProfile).map {
    case Granted => DoRemoveProfile(user, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
    case other   => other
  }.recover { case e => e }

  val commandPermissionMap = Map[Any, String](
    GetProfile -> "get",
    ReplaceProfile -> "replace",
    PatchProfile -> "patch",
    RemoveProfile -> "remove",
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
            val aclObj = state.profile.raw.fields("_metadata").asJsObject.fields("acl").asJsObject
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
