package com.zxtx.actors

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.roundeights.hasher.Implicits.stringToHasher
import com.zxtx.actors.DocumentActor.CreateDocument
import com.zxtx.actors.DocumentActor.Document
import com.zxtx.actors.DocumentActor.DocumentAlreadyExists
import com.zxtx.actors.DocumentActor.DocumentCreated
import com.zxtx.actors.DocumentActor.GetDocument
import com.zxtx.persistence.ElasticSearchStore

import akka.Done
import akka.pattern.ask
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.LWWMap
import akka.cluster.ddata.LWWMapKey
import akka.cluster.ddata.Replicator.Get
import akka.cluster.ddata.Replicator.GetSuccess
import akka.cluster.ddata.Replicator.NotFound
import akka.cluster.ddata.Replicator.ReadLocal
import akka.cluster.ddata.Replicator.ReadMajority
import akka.cluster.ddata.Replicator.Update
import akka.cluster.ddata.Replicator.UpdateSuccess
import akka.cluster.ddata.Replicator.UpdateFailure
import akka.cluster.ddata.Replicator.WriteLocal
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
import akka.util.Timeout
import gnieh.diffson.sprayJson.JsonPatch
import gnieh.diffson.sprayJson.provider.marshall
import gnieh.diffson.sprayJson.provider.patchMarshaller
import pdi.jwt.Jwt
import pdi.jwt.JwtAlgorithm
import pdi.jwt.JwtClaim
import pdi.jwt.JwtHeader
import spray.json.JsBoolean
import spray.json.JsNumber
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import spray.json.RootJsonFormat
import pdi.jwt.JwtBase64
import akka.cluster.ddata.Replicator.UpdateFailure
import akka.cluster.ddata.Replicator.UpdateFailure

object DomainActor {

  def props(): Props = Props[DomainActor]

  object Domain {
    val empty = new Domain("", "", 0L, 0L, 0L, None, JsObject(Map[String, JsValue]()))
  }
  case class Domain(id: String, author: String = "anonymous", revision: Long, created: Long, updated: Long, deleted: Option[Boolean], raw: JsObject)

  case class CreateDomain(pid: String, token: String, raw: JsObject) extends Command
  case class GetDomain(pid: String, token: String) extends Command
  case class ReplaceDomain(pid: String, token: String, raw: JsObject) extends Command
  case class PatchDomain(pid: String, token: String, patch: JsonPatch) extends Command
  case class DeleteDomain(pid: String, token: String) extends Command
  case class AuthorizeDomain(pid: String, token: String, patch: JsonPatch) extends Command
  case class FindDocumentSets(pid: String, token: String, params: Seq[(String, String)], body: JsObject) extends Command
  case class RegisterUser(pid: String, token: String, userName: String, password: String) extends Command
  case class JoinDomain(pid: String, token: String, userName: String) extends Command
  case class LoginDomain(pid: String, userName: String, password: String) extends Command
  case class LogoutDomain(pid: String, token: String) extends Command
  case class ValidateToken(pid: String, token: String) extends Command

  private case class DoCreateDomain(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoGetDomain(user: String, request: Option[Any] = None)
  private case class DoReplaceDomain(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoPatchDomain(user: String, patch: JsonPatch, request: Option[Any] = None)
  private case class DoAuthorizeDomain(user: String, patch: JsonPatch, request: Option[Any] = None)
  private case class DoDeleteDomain(user: String, request: Option[Any] = None)
  private case class DoFindDocumentSets(user: String, params: Seq[(String, String)], body: JsObject, request: Option[Any] = None)
  private case class DoRegisterUser(user: String, userName: String, passwordMd5: String, request: Option[Any] = None)
  private case class DoJoinDomain(user: String, userName: String, request: Option[Any] = None)
  private case class DoLoginDomain(user: String, token: String, request: Option[Any] = None)
  private case class DoLogoutDomain(user: String, request: Option[Any] = None)
  private case class DoGarbageCollection(user: String, request: Option[Any] = None)
  private case class InitializeDocumentSets(user: String, raw: JsObject, request: Option[Any] = None)

  case class DomainCreated(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DomainReplaced(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DomainPatched(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends DocumentEvent
  case class DomainDeleted(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DomainAuthorized(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends DocumentEvent
  case class UserRegistered(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DomainJoined(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class UserLoggedIn(id: String, author: String, revision: Long, created: Long, token: String, raw: JsObject) extends DocumentEvent
  case class UserLoggedOut(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent

  object DomainNotExists extends Exception
  object DomainAlreadyExists extends Exception
  object DomainIsCreating extends Exception
  object DomainSoftDeleted extends Exception
  object UserNotExists extends Exception
  object UserAlreadyRegistered extends Exception
  object UserAlreadyJoined extends Exception
  object UserNamePasswordError extends Exception
  object UpdateSecretKeyError extends Exception
  object DomainLogoutError extends Exception
  object ClearCacheError extends Exception
  object UpdateCacheError extends Exception
  case class PatchDomainException(exception: Throwable) extends Exception
  case class AuthorizeDomainException(exception: Throwable) extends Exception

  val idExtractor: ShardRegion.ExtractEntityId = { case cmd: Command => (cmd.pid, cmd) }
  val shardResolver: ShardRegion.ExtractShardId = { case cmd: Command => (math.abs(cmd.pid.hashCode) % 100).toString }
  val shardName: String = "Domain"

  val secretCacheKey: String = "SecretCacheKey"

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
      def write(dp: DomainPatched) = {
        val metaObj = newMetaObject(dp.raw.getFields("_metadata"), dp.author, dp.revision, dp.created)
        JsObject(("id" -> JsString(dp.id)) :: ("patch" -> marshall(dp.patch)) :: dp.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, patch, jo) = extractFieldsWithPatch(value, "DomainPatched event expected!")
        DomainPatched(id, author, revision, created, patch, jo)
      }
    }

    implicit object DomainAuthorizedFormat extends RootJsonFormat[DomainAuthorized] {
      def write(da: DomainAuthorized) = {
        val metaObj = newMetaObject(da.raw.getFields("_metadata"), da.author, da.revision, da.created)
        JsObject(("id" -> JsString(da.id)) :: ("patch" -> marshall(da.patch)) :: da.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, patch, jo) = extractFieldsWithPatch(value, "DomainAuthorized event expected!")
        DomainAuthorized(id, author, revision, created, patch, jo)
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

    implicit object UserRegisteredFormat extends RootJsonFormat[UserRegistered] {
      def write(dj: UserRegistered) = {
        val metaObj = newMetaObject(dj.raw.getFields("_metadata"), dj.author, dj.revision, dj.created)
        JsObject(("id" -> JsString(dj.id)) :: dj.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "UserRegistered event expected!")
        UserRegistered(id, author, revision, created, jo)
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

    implicit object UserLoggedInFormat extends RootJsonFormat[UserLoggedIn] {
      def write(uli: UserLoggedIn) = {
        val metaObj = newMetaObject(uli.raw.getFields("_metadata"), uli.author, uli.revision, uli.created)
        JsObject(("id" -> JsString(uli.id)) :: ("token" -> JsString(uli.token)) :: uli.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, token, jo) = extractFieldsWithToken(value, "UserLoggedIn event expected!")
        UserLoggedIn(id, author, revision, created, token, jo)
      }
    }

    implicit object UserLoggedOutFormat extends RootJsonFormat[UserLoggedOut] {
      def write(ulo: UserLoggedOut) = {
        val metaObj = newMetaObject(ulo.raw.getFields("_metadata"), ulo.author, ulo.revision, ulo.created)
        JsObject(("id" -> JsString(ulo.id)) :: ulo.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "UserLoggedOut event expected!")
        UserLoggedOut(id, author, revision, created, jo)
      }
    }
  }

  private case class State(domain: Domain, deleted: Boolean) {
    def updated(evt: DocumentEvent): State = evt match {
      case DomainCreated(id, author, revision, created, raw) =>
        val metadata = JsObject(raw.fields("_metadata").asJsObject.fields + ("updated" -> JsNumber(created)))
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
      case DomainAuthorized(_, _, revision, created, patch, _) =>
        val oldMetadata = domain.raw.fields("_metadata").asJsObject
        val patchedAuth = patch(oldMetadata.fields("acl"))
        val metadata = JsObject(oldMetadata.fields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("acl" -> patchedAuth))
        copy(domain = domain.copy(revision = revision, updated = created, raw = JsObject(domain.raw.fields + ("_metadata" -> metadata))))
      case DomainDeleted(_, _, revision, created, _) =>
        val oldMetaFields = domain.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("deleted" -> JsBoolean(true)))
        copy(domain = domain.copy(revision = revision, updated = created, deleted = Some(true), raw = JsObject(domain.raw.fields + ("_metadata" -> metadata))))
      case _ => copy(domain = domain)
    }

    def updated(d: Domain): State = d match {
      case Domain(id, author, revision, created, updated, deleted, raw) => copy(domain = Domain(id, author, revision, created, updated, deleted, raw))
    }
  }

  import spray.json._
  def defaultACL(user: String) = s"""{
        "login":{
            "roles":["administrator","user"],
            "users":["${user}"]
        },
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
        "create_document_set":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "find_document_sets":{
            "roles":["administrator","user"],
            "users":["${user}"]
        },
        "create_domain":{
            "roles":["administrator"]
        },
        "authorize":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "gc":{
            "roles":["administrator"],
            "users":["${user}"]
        }
      }""".parseJson.asJsObject
}

class DomainActor extends PersistentActor with ACL with ActorLogging {
  import ACL._
  import CollectionActor._
  import DomainActor._
  import DomainActor.JsonProtocol._
  import spray.json._

  override def persistenceId: String = self.path.name

  override def journalPluginId = "akka.persistence.elasticsearch.journal"
  override def snapshotPluginId = "akka.persistence.elasticsearch-snapshot-store"

  val rootDomain = system.settings.config.getString("domain.root-domain")
  val adminName = system.settings.config.getString("domain.administrator.name")
  val cacheKey = system.settings.config.getString("domain.cache-key")
  val domainRegion = ClusterSharding(system).shardRegion(DomainActor.shardName)
  val documentSetRegion = ClusterSharding(system).shardRegion(CollectionActor.shardName)
  val documentRegion = ClusterSharding(system).shardRegion(DocumentActor.shardName)

  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system))

  private val readMajority = ReadMajority(duration)
  private val writeMajority = WriteMajority(duration)

  // passivate the entity when no activity
  context.setReceiveTimeout(2.minutes)

  private def id = persistenceId.split("%7E")(2)

  val mediator = DistributedPubSub(context.system).mediator

  private var state = State(Domain.empty, false)
  private val store = ElasticSearchStore(system)

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
    case _: Command => sender ! DomainNotExists
  }

  def creating: Receive = {
    case DoCreateDomain(user, raw, Some(replyTo: ActorRef)) =>
      persist(DomainCreated(id, user, lastSequenceNr + 1, System.currentTimeMillis, JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> DomainActor.defaultACL(user)))))) { evt =>
        state = state.updated(evt)
        val d = state.domain.toJson.asJsObject
        saveSnapshot(d)
        replyTo ! evt.copy(raw = d)
      }
    case CheckPermission(_, _, _) => sender ! Granted
    case _: Command               => sender ! DomainIsCreating
  }

  def created: Receive = {
    case RegisterUser(_, token, userName, password) =>
      val replyTo = sender
      registerUser(token, userName, password).foreach {
        case dru: DoRegisterUser => self ! dru.copy(request = Some(replyTo))
        case other               => replyTo ! other
      }
    case DoRegisterUser(user, userName, passwordMd5, Some(replyTo: ActorRef)) =>
      persist(UserRegistered(id, user, 0, System.currentTimeMillis, JsObject("name" -> JsString(userName), "password" -> JsString(passwordMd5)))) { evt =>
        replyTo ! evt
      }
    case JoinDomain(_, token, userName) =>
      val replyTo = sender
      joinDomain(token, userName).foreach {
        case djd: DoJoinDomain => self ! djd.copy(request = Some(replyTo))
        case other             => replyTo ! other
      }
    case DoJoinDomain(user, userName, Some(replyTo: ActorRef)) =>
      persist(DomainJoined(id, user, 0, System.currentTimeMillis, JsObject("name" -> JsString(userName)))) { evt =>
        replyTo ! evt
      }
    case LoginDomain(_, userName, password) =>
      val replyTo = sender
      loginDomain(userName, password).foreach {
        case dld: DoLoginDomain => self ! dld.copy(request = Some(replyTo))
        case other              => replyTo ! other
      }
    case DoLoginDomain(user, token, Some(replyTo: ActorRef)) =>
      persist(UserLoggedIn(id, user, 0, System.currentTimeMillis(), token, JsObject())) { evt =>
        replyTo ! evt
      }
    case ValidateToken(_, token) =>
      val replyTo = sender
      validateToken(token).foreach {
        case Some(_) => replyTo ! TokenValid
        case None    => replyTo ! TokenInvalid
      }
    case LogoutDomain(_, token) =>
      val replyTo = sender
      logoutDomain(token).foreach {
        case dld: DoLogoutDomain => self ! dld.copy(request = Some(replyTo))
        case other               => replyTo ! other
      }
    case DoLogoutDomain(user, Some(replyTo: ActorRef)) =>
      persist(UserLoggedOut(id, user, 0, System.currentTimeMillis(), JsObject())) { evt =>
        replyTo ! evt
      }
    case GetDomain(_, token) =>
      val replyTo = sender
      check(token, GetDomain) { user => Future.successful(DoGetDomain(user)) }.foreach {
        case dgd: DoGetDomain => self ! dgd.copy(request = Some(replyTo))
        case other            => replyTo ! other
      }
    case DoGetDomain(_, Some(replyTo: ActorRef)) =>
      replyTo ! state.domain
    case ReplaceDomain(_, token, raw) =>
      val replyTo = sender
      check(token, ReplaceDomain) { user => Future.successful(DoReplaceDomain(user, raw)) }.foreach {
        case drd: DoReplaceDomain => self ! drd.copy(request = Some(replyTo))
        case other                => replyTo ! other
      }
    case DoReplaceDomain(user, raw, Some(replyTo: ActorRef)) =>
      persist(DomainReplaced(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        state = state.updated(evt)
        val ds = state.domain.toJson.asJsObject
        saveSnapshot(ds)
        deleteSnapshot(lastSequenceNr - 1)
        replyTo ! evt.copy(raw = ds)
      }
    case PatchDomain(_, token, patch) =>
      val replyTo = sender
      patchDomain(token, patch).foreach {
        case dpd: DoPatchDomain => self ! dpd.copy(request = Some(replyTo))
        case other              => replyTo ! other
      }
    case DoPatchDomain(user, patch, Some(replyTo: ActorRef)) =>
      persist(DomainPatched(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, JsObject())) { evt =>
        state = state.updated(evt)
        val d = state.domain.toJson.asJsObject
        saveSnapshot(d)
        deleteSnapshot(lastSequenceNr - 1)
        replyTo ! evt.copy(raw = d)
      }
    case DeleteDomain(_, token) =>
      val replyTo = sender
      deleteDomain(token).foreach {
        case ddd: DoDeleteDomain => self ! ddd.copy(request = Some(replyTo))
        case other               => replyTo ! other
      }
    case DoDeleteDomain(user, Some(replyTo: ActorRef)) =>
      persist(DomainDeleted(id, user, lastSequenceNr + 1, System.currentTimeMillis(), JsObject())) { evt =>
        state = state.updated(evt)
        deleteMessages(lastSequenceNr)
        deleteSnapshot(lastSequenceNr - 1)
        val d = state.domain.toJson.asJsObject
        saveSnapshot(d)
        id match {
          case `rootDomain` => store.deleteIndices(s"${rootDomain}*").foreach {
            case (StatusCodes.OK, _) => replyTo ! evt.copy(raw = d)
            case (code, jv)          => throw new RuntimeException(jv.compactPrint)
          }
          case _ => replyTo ! evt.copy(raw = d)
        }
        context.become(deleted)
        mediator ! Publish(id, evt.copy(raw = d))
        context.parent ! Passivate(stopMessage = PoisonPill)
      }
    case AuthorizeDomain(_, token, patch) =>
      val replyTo = sender
      authorizeDomain(token, patch).foreach {
        case dad: DoAuthorizeDomain => self ! dad.copy(request = Some(replyTo))
        case other                  => replyTo ! other
      }
    case DoAuthorizeDomain(user, patch, Some(replyTo: ActorRef)) =>
      persist(DomainAuthorized(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, JsObject())) { evt =>
        state = state.updated(evt)
        val d = state.domain.toJson.asJsObject
        saveSnapshot(d)
        deleteSnapshot(lastSequenceNr - 1)
        replyTo ! evt.copy(raw = d)
      }
    case SaveSnapshotSuccess(metadata)         =>
    case SaveSnapshotFailure(metadata, reason) =>
    case GarbageCollection(_, token, _) =>
      val replyTo = sender
      check(token, GarbageCollection) { user => Future.successful(DoGarbageCollection(user)) }.foreach {
        case dgc: DoGarbageCollection => self ! dgc.copy(request = Some(replyTo))
        case other                    => replyTo ! other
      }
    case DoGarbageCollection(user, Some(replyTo: ActorRef)) =>
      garbageCollection(user).foreach { Done => replyTo ! GarbageCollectionCompleted }
    case CreateDomain(_, _, _) => sender ! DomainAlreadyExists
    case CheckPermission(_, user, command) =>
      val replyTo = sender
      checkPermission(user, command).foreach { replyTo ! _ }
  }

  def deleted: Receive = {
    case GetDomain(_, token) =>
      val replyTo = sender
      check(token, GetDomain) { user => Future.successful(DoGetDomain(user)) }.foreach {
        case dgd: DoGetDomain => self ! dgd.copy(request = Some(replyTo))
        case other            => replyTo ! other
      }
    case DoGetDomain(_, Some(replyTo: ActorRef)) =>
      replyTo ! state.domain
      context.parent ! Passivate(stopMessage = PoisonPill)
    case _: Command => sender ! DomainSoftDeleted
  }

  override def unhandled(msg: Any): Unit = msg match {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
    case _              => super.unhandled(msg)
  }

  private def createDomain(token: String, raw: JsObject) = validateToken(token).flatMap {
    case Some(user) => id match {
      case `rootDomain` => initDocumentSets(user).recover { case e => e }.flatMap {
        case Done => updateCache.map {
          case true  => DoCreateDomain(user, raw)
          case false => UpdateCacheError
        }
        case other => Future.successful(other)
      }
      case _ => (domainRegion ? CheckPermission(s"${rootDomain}~.domains~${rootDomain}", user, CreateDomain)).flatMap {
        case Granted => initDocumentSets(user).recover { case e => e }.flatMap {
          case Done => updateCache.map {
            case true  => DoCreateDomain(user, raw)
            case false => UpdateCacheError
          }
          case other => Future.successful(other)
        }
        case Denied => Future.successful(Denied)
      }
    }
    case None => Future.successful(TokenInvalid)
  }

  private def registerUser(token: String, userName: String, password: String) = check(token, RegisterUser) { user =>
    val passwordMd5 = password.md5.hex
    val userInfo = JsObject("name" -> JsString(userName), "password" -> JsString(passwordMd5))
    (documentRegion ? CreateDocument(s"${rootDomain}~users~${userName}", user, userInfo)).map {
      case _: DocumentCreated    => DoRegisterUser(user, userName, passwordMd5)
      case DocumentAlreadyExists => DocumentAlreadyExists
    }
  }

  private def joinDomain(token: String, userName: String) = check(token, JoinDomain) { user =>
    val profile = JsObject("roles" -> JsArray(JsString("user")))
    (documentRegion ? CreateDocument(s"${id}~profiles~${userName}", user, profile)).map {
      case _: DocumentCreated    => DoJoinDomain(user, userName)
      case DocumentAlreadyExists => UserAlreadyJoined
    }
  }

  private def loginDomain(user: String, password: String) = checkPermission(user, LoginDomain).flatMap {
    case Granted => (documentRegion ? GetDocument(s"${rootDomain}~users~${user}", user)).flatMap {
      case doc: Document =>
        val hexMd5 = doc.raw.fields("password").asInstanceOf[JsString].value
        if (hexMd5 == password.md5.hex) {
          val secretKey = (hexMd5 + System.currentTimeMillis).md5.hex
          val token = Jwt.encode(JwtHeader(JwtAlgorithm.HS256), JwtClaim(s"""{"id":"${user}"}"""), secretKey)
          updateSecretKey(user, secretKey).map {
            case true  => DoLoginDomain(user, token)
            case false => UpdateSecretKeyError
          }
        } else Future.successful(UserNamePasswordError)
      case _ => Future.successful(UserNotExists)
    }
    case Denied => Future.successful(Denied)
  }

  private def logoutDomain(token: String) = validateToken(token).flatMap {
    case Some(user) => clearSecretKey(user).map {
      case true  => DoLogoutDomain(user)
      case false => DomainLogoutError
    }
    case None => Future.successful(TokenInvalid)
  }

  private def patchDomain(token: String, patch: JsonPatch) = check(token, PatchDomain) { user =>
    Try {
      patch(state.domain.raw)
    } match {
      case Success(_) => Future.successful(DoPatchDomain(user, patch))
      case Failure(e) => Future.successful(PatchDomainException(e))
    }
  }

  private def deleteDomain(token: String) = check(token, DeleteDomain) { user =>
    clearCache.flatMap {
      case true =>
        val source = Source.fromFuture(documentSetRegion ? FindDocuments(s"${id}~.documentsets~.documentsets", user, Seq[(String, String)](), JsObject()))
          .mapConcat(extractElements).filter { t => !t._2 }.mapAsync(1) { t =>
            documentSetRegion ? DeleteCollection(s"${id}~.documentsets~${t._1}", user)
          }.map { case _: CollectionDeleted => Done }

        id match {
          case `rootDomain` =>
            val domainRegion = ClusterSharding(system).shardRegion(DomainActor.shardName)
            for {
              r1 <- Source.fromFuture(documentSetRegion ? FindDocuments(s"${rootDomain}~.documentsets~.domains", user, Seq[(String, String)](), JsObject()))
                .mapConcat(extractElements).filter {
                  case (`rootDomain`, _) => false
                  case (_, false)        => true
                  case (_, true)         => false
                }.mapAsync(1) { t =>
                  domainRegion ? DeleteDomain(s"${rootDomain}~.domains~${t._1}", user)
                }.map { case _: DomainDeleted => Done }.runWith(Sink.ignore)
              r2 <- { Thread.sleep(1000); garbageCollection(user) }
              r3 <- source.runWith(Sink.ignore)
            } yield Done
          case _ =>
            source.runWith(Sink.ignore)
        }
      case false => Future.successful(ClearCacheError)
    }.map {
      case Done  => DoDeleteDomain(user)
      case other => other
    }
  }

  private def authorizeDomain(token: String, patch: JsonPatch) = check(token, AuthorizeDomain) { user =>
    Try {
      patch(state.domain.raw.fields("_metadata").asJsObject.fields("acl"))
    } match {
      case Success(_) => Future.successful(DoAuthorizeDomain(user, patch))
      case Failure(e) => Future.successful(AuthorizeDomainException(e))
    }
  }

  override def checkPermission(user: String, command: Any): Future[Permission] = fetchProfile(id, user).map {
    case profile: Document =>
      val aclObj = state.domain.raw.fields("_metadata").asJsObject.fields("acl").asJsObject
      val userRoles = profileValue(profile.raw, "roles")
      val userGroups = profileValue(profile.raw, "groups")
      val (aclRoles, aclGroups, aclUsers) = command match {
        case CreateDomain      => (aclValue(aclObj, "create_domain", "roles"), aclValue(aclObj, "create_domain", "groups"), aclValue(aclObj, "create_domain", "users"))
        case GetDomain         => (aclValue(aclObj, "get", "roles"), aclValue(aclObj, "get", "groups"), aclValue(aclObj, "get", "users"))
        case ReplaceDomain     => (aclValue(aclObj, "replace", "roles"), aclValue(aclObj, "replace", "groups"), aclValue(aclObj, "replace", "users"))
        case PatchDomain       => (aclValue(aclObj, "patch", "roles"), aclValue(aclObj, "patch", "groups"), aclValue(aclObj, "patch", "users"))
        case AuthorizeDomain   => (aclValue(aclObj, "authorize", "roles"), aclValue(aclObj, "authorize", "groups"), aclValue(aclObj, "authorize", "users"))
        case DeleteDomain      => (aclValue(aclObj, "delete", "roles"), aclValue(aclObj, "delete", "groups"), aclValue(aclObj, "delete", "users"))
        case LoginDomain       => (aclValue(aclObj, "login", "roles"), aclValue(aclObj, "login", "groups"), aclValue(aclObj, "login", "users"))
        case CreateCollection  => (aclValue(aclObj, "create_document_set", "roles"), aclValue(aclObj, "create_document_set", "groups"), aclValue(aclObj, "create_document_set", "users"))
        case FindDocumentSets  => (aclValue(aclObj, "find_document_sets", "roles"), aclValue(aclObj, "find_document_sets", "groups"), aclValue(aclObj, "find_document_sets", "users"))
        case GarbageCollection => (aclValue(aclObj, "gc", "roles"), aclValue(aclObj, "gc", "groups"), aclValue(aclObj, "gc", "users"))
        case _                 => (Vector[String](), Vector[String](), Vector[String]())
      }
      //      System.out.println(s"~~~~~~~~~~~~aclRoles~~~~~${aclRoles}")
      //      System.out.println(s"~~~~~~~~~~~~userRoles~~~~~${userRoles}")
      //      System.out.println(s"~~~~~~~~~~~~aclGroups~~~~~${aclGroups}")
      //      System.out.println(s"~~~~~~~~~~~~userGroups~~~~~${userGroups}")
      //      System.out.println(s"~~~~~~~~~~~~aclUsers~~~~~${aclUsers}")
      //      System.out.println(s"~~~~~~~~~~~~user~~~~~${user}")
      if (aclRoles.intersect(userRoles).isEmpty && aclGroups.intersect(userGroups).isEmpty && !aclUsers.contains(user)) Denied else Granted
    case _ => Denied
  }

  private def initDocumentSets(user: String): Future[Done] = {
    val adminRole = JsObject("description" -> JsString("The administrator role of current domain."))
    val userRole = JsObject("description" -> JsString("The user role of current domain."))
    val profile = JsObject("roles" -> JsArray(JsString("administrator"), JsString("user")))
    id match {
      case `rootDomain` =>
        import com.roundeights.hasher.Implicits._
        val adminName = system.settings.config.getString("domain.administrator.name")
        val password = system.settings.config.getString("domain.administrator.password")
        val adminUser = JsObject("password" -> JsString(password.md5.hex))
        for {
          r1 <- Source(".documentsets" :: ".domains" :: "users" :: "roles" :: "profiles" :: Nil)
            .mapAsync(1) { ds => (documentSetRegion ? CreateCollection(s"${id}~.documentsets~${ds}", user, JsObject())) }
            .map { case _: CollectionCreated => Done }.runWith(Sink.last[Done])
          r2 <- Source.fromFuture {
            documentRegion ? CreateDocument(s"${rootDomain}~users~${adminName}", adminName, adminUser)
          }.map { case _: DocumentCreated => Done }.runWith(Sink.ignore)
          r3 <- Source.fromFuture {
            documentRegion ? CreateDocument(s"${rootDomain}~roles~administrator", adminName, adminRole)
          }.map { case _: DocumentCreated => Done }.runWith(Sink.ignore)
          r4 <- Source.fromFuture {
            documentRegion ? CreateDocument(s"${rootDomain}~roles~user", adminName, userRole)
          }.map { case _: DocumentCreated => Done }.runWith(Sink.ignore)
          r5 <- Source.fromFuture {
            documentRegion ? CreateDocument(s"${rootDomain}~profiles~${adminName}", adminName, profile)
          }.map { case _: DocumentCreated => Done }.runWith(Sink.ignore)
        } yield Done
      case _ =>
        for {
          r1 <- Source(".documentsets" :: "roles" :: "profiles" :: Nil)
            .mapAsync(1) { ds => documentSetRegion ? CreateCollection(s"${id}~.documentsets~${ds}", user, JsObject()) }
            .map { case _: CollectionCreated => Done }.runWith(Sink.last[Done])
          r2 <- Source.fromFuture {
            documentRegion ? CreateDocument(s"${id}~roles~administrator", user, adminRole)
          }.map { case _: DocumentCreated => Done }.runWith(Sink.ignore)
          r3 <- Source.fromFuture {
            documentRegion ? CreateDocument(s"${id}~roles~user", user, userRole)
          }.map { case _: DocumentCreated => Done }.runWith(Sink.ignore)
          r4 <- Source.fromFuture {
            documentRegion ? CreateDocument(s"${id}~profiles~${user}", user, profile)
          }.map { case _: DocumentCreated => Done }.runWith(Sink.ignore)
        } yield Done
    }
  }

  private def checkCache: Future[Boolean] =
    (replicator ? Get(LWWMapKey[String, Any](cacheKey), readMajority)).map {
      case g @ GetSuccess(LWWMapKey(_), _) =>
        g.dataValue match {
          case data: LWWMap[_, _] => data.asInstanceOf[LWWMap[String, Any]].get(id) match {
            case Some(_) => true
            case None    => false
          }
        }
      case NotFound(_, _) => false
    }

  private def updateCache = (replicator ? Update(LWWMapKey[String, Any](cacheKey), LWWMap(), writeMajority)(_ + (id -> state.domain))).map {
    case UpdateSuccess(LWWMapKey(_), _)    => true
    case _: UpdateFailure[LWWMapKey[_, _]] => false
  }

  private def clearCache = (replicator ? Update(LWWMapKey[String, Any](cacheKey), LWWMap(), writeMajority)(_ - id)).map {
    case UpdateSuccess(LWWMapKey(_), _)    => true
    case _: UpdateFailure[LWWMapKey[_, _]] => false
  }

  private def garbageCollection(user: String): Future[Done] = {
    Thread.sleep(1000) // Bugfix: delay for deleting document in ElasticSearch.

    val source = Source.fromFuture(documentSetRegion ? FindDocuments(s"${id}~.documentsets~.documentsets", user, Seq[(String, String)](), JsObject()))
      .mapConcat(extractElements).mapAsync(1) {
        case (dsId: String, true)  => store.deleteIndices(s"${id}~${dsId}*")
        case (dsId: String, false) => documentSetRegion ? GarbageCollection(s"${id}~.documentsets~${dsId}", user)
      }.map {
        case (StatusCodes.OK, _)          => Done
        case (_: StatusCode, jv: JsValue) => throw new RuntimeException(jv.compactPrint)
        case GarbageCollectionCompleted   => Done
      }
    id match {
      case `rootDomain` =>
        for {
          r1 <- Source.fromFuture(documentSetRegion ? FindDocuments(s"${rootDomain}~.documentsets~.domains", user, Seq[(String, String)](), JsObject()))
            .mapConcat(extractElements).filter { t =>
              t._1 != rootDomain
            }.mapAsync(1) {
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
