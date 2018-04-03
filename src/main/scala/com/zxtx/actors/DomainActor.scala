package com.zxtx.actors

import java.util.UUID

import scala.concurrent._
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import akka.actor.ActorSystem
import akka.NotUsed
import akka.Done
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.pattern.ask
import akka.pattern.pipe
import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.LWWMapKey
import akka.cluster.ddata.LWWMap
import akka.cluster.ddata.Replicator._
import akka.cluster.Cluster
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.Passivate
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{ Publish, Subscribe }
import akka.persistence._
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
import ElasticSearchStore._
import scala.concurrent.duration.Duration

import com.roundeights.hasher.Implicits._

object DomainActor {

  def props(): Props = Props[DomainActor]

  object Domain {
    val empty = new Domain("", "", 0L, 0L, 0L, None, JsObject(Map[String, JsValue]()))
  }
  case class Domain(id: String, author: String = "anonymous", revision: Long, created: Long, updated: Long, deleted: Option[Boolean], raw: JsObject)

  case class CreateDomain(pid: String, user: String, raw: JsObject) extends Command
  case class GetDomain(pid: String, user: String) extends Command
  case class ReplaceDomain(pid: String, user: String, raw: JsObject) extends Command
  case class PatchDomain(pid: String, user: String, patch: JsonPatch) extends Command
  case class DeleteDomain(pid: String, user: String) extends Command
  case class AuthorizeDomain(pid: String, user: String, patch: JsonPatch) extends Command
  case class FindDocumentSets(pid: String, user: String, params: Seq[(String, String)], body: JsObject) extends Command
  case class RegisterUser(pid: String, user: String, userName: String, password: String) extends Command
  case class JoinDomain(pid: String, user: String, userName: String) extends Command
  case class LoginDomain(pid: String, userName: String, password: String) extends Command
  case class LogoutDomain(pid: String, user: String) extends Command

  private case class DoCreateDomain(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoGetDomain(user: String, request: Option[Any] = None)
  private case class DoReplaceDomain(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoPatchDomain(user: String, patch: JsonPatch, request: Option[Any] = None)
  private case class DoAuthorizeDomain(user: String, patch: JsonPatch, request: Option[Any] = None)
  private case class DoDeleteDomain(user: String, request: Option[Any] = None)
  private case class DoFindDocumentSets(user: String, params: Seq[(String, String)], body: JsObject, request: Option[Any] = None)
  private case class DoRegisterUser(user: String, userName: String, passwordMd5: String, request: Option[Any] = None)
  private case class DoJoinDomain(user: String, userName: String, request: Option[Any] = None)
  private case class DoLoginDomain(user: String, request: Option[Any] = None)
  private case class DoGarbageCollection(user: String, request: Option[Any] = None)
  private case class UpdateCacheSuccess(user: String, request: Option[Any] = None)
  private case class DeleteDomainSuccess(user: String, request: Option[Any] = None)
  private case class InitializeDocumentSets(user: String, raw: JsObject, request: Option[Any] = None)

  case class DomainCreated(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DomainReplaced(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DomainPatched(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends DocumentEvent
  case class DomainDeleted(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DomainAuthorized(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends DocumentEvent
  case class UserRegistered(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DomainJoined(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class UserLoggedIn(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class UserLoggedOut(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent

  object DomainNotFound extends Exception
  object DomainAlreadyExists extends Exception
  object DomainIsCreating extends Exception
  object DomainSoftDeleted extends Exception
  object UserNotExists extends Exception
  object UserAlreadyRegistered extends Exception
  object UserAlreadyJoined extends Exception
  object UserNamePasswordError extends Exception
  case class PatchDomainException(exception: Throwable) extends Exception
  case class AuthorizeDomainException(exception: Throwable) extends Exception

  val idExtractor: ShardRegion.ExtractEntityId = { case cmd: Command => (cmd.pid, cmd) }
  val shardResolver: ShardRegion.ExtractShardId = { case cmd: Command => (math.abs(cmd.pid.hashCode) % 100).toString }
  val shardName: String = "Domain"

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

    implicit object UserLoggedInFormat extends RootJsonFormat[UserLoggedIn] {
      def write(uli: UserLoggedIn) = {
        val metaObj = newMetaObject(uli.raw.getFields("_metadata"), uli.author, uli.revision, uli.created)
        JsObject(("id" -> JsString(uli.id)) :: uli.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "UserLoggedIn event expected!")
        UserLoggedIn(id, author, revision, created, jo)
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

class DomainActor extends PersistentActor with ActorLogging with ACL {
  import DomainActor._
  import DocumentSetActor._
  import DomainActor.JsonProtocol._
  import spray.json._
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
  val documentSetRegion = ClusterSharding(system).shardRegion(DocumentSetActor.shardName)
  val documentRegion = ClusterSharding(system).shardRegion(DocumentActor.shardName)
  implicit val cluster = Cluster(system)
  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system))
  implicit val executionContext = context.dispatcher

  implicit val duration = 5.seconds
  implicit val timeOut = Timeout(duration)
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
    case cd @ CreateDomain(_, user, raw) =>
      val replyTo = sender
      val parent = context.parent
      id match {
        case `rootDomain` => self ! InitializeDocumentSets(user, raw, Some(replyTo))
        case _ => Source.fromFuture(domainRegion ? CheckPermission(s"${rootDomain}~.domains~${rootDomain}", user, cd)).runWith(Sink.head[Any]).foreach {
          case Granted => self ! InitializeDocumentSets(user, raw, Some(replyTo))
          case Denied =>
            replyTo ! Denied
            parent ! Passivate(stopMessage = PoisonPill)
        }
      }
      context.become(creating)
    case _: Command => sender ! DomainNotFound
  }

  def creating: Receive = {
    case InitializeDocumentSets(user, raw, request) =>
      initDocumentSets(user).foreach(Done => self ! DoCreateDomain(user, raw, request))
    case DoCreateDomain(user, raw, Some(replyTo: ActorRef)) =>
      persist(DomainCreated(id, user, lastSequenceNr + 1, System.currentTimeMillis, JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> DomainActor.defaultACL(user)))))) { evt =>
        state = state.updated(evt)
        val d = state.domain.toJson.asJsObject
        saveSnapshot(d)
        updateCache.foreach { Done => self ! UpdateCacheSuccess(user, Some((replyTo, evt.copy(raw = d)))) }
      }
    case UpdateCacheSuccess(_, Some((replyTo: ActorRef, domainCreated))) =>
      context.become(created)
      replyTo ! domainCreated
    case CheckPermission(_, _, _) => sender ! Granted
    case _: Command               => sender ! DomainIsCreating
  }

  def created: Receive = {
    case ru @ RegisterUser(_, user, userName, password) =>
      val replyTo = sender
      val passwordMd5 = password.md5.hex
      for {
        r1 <- checkPermission(user, ru).map {
          case Granted => Granted
          case Denied  => replyTo ! Denied
        }
        if (r1 == Granted)
        r2 <- registerUser(user, userName, passwordMd5).map {
          case dc: DocumentCreated   => dc
          case DocumentAlreadyExists => replyTo ! UserAlreadyRegistered
        }
        if (r2.isInstanceOf[DocumentCreated])
      } self ! DoRegisterUser(user, userName, passwordMd5, Some(replyTo))
    case DoRegisterUser(user, userName, passwordMd5, Some(replyTo: ActorRef)) =>
      persist(UserRegistered(id, user, 0, System.currentTimeMillis, JsObject("name" -> JsString(userName), "password" -> JsString(passwordMd5)))) { evt =>
        replyTo ! evt
      }
    case jd @ JoinDomain(_, user, userName) =>
      val replyTo = sender
      for {
        r1 <- checkPermission(user, jd).map {
          case Granted => Granted
          case Denied  => replyTo ! Denied
        }
        if (r1 == Granted)
        r2 <- joinDomain(user, userName).map {
          case dc: DocumentCreated   => dc
          case DocumentAlreadyExists => replyTo ! UserAlreadyJoined
        }
        if (r2.isInstanceOf[DocumentCreated])
      } self ! DoJoinDomain(user, userName, Some(replyTo))
    case DoJoinDomain(user, userName, Some(replyTo: ActorRef)) =>
      persist(DomainJoined(id, user, 0, System.currentTimeMillis, JsObject("name"->JsString(userName)))) { evt =>
        replyTo ! evt
      }
    case ld @ LoginDomain(_, userName, password) =>
      val replyTo = sender
      for {
        r1 <- checkPermission(userName, ld).map {
          case Granted => Granted
          case Denied  => replyTo ! UserNotExists
        }
        if (r1 == Granted)
        r2 <- login(userName, password).map {
          case true  => true
          case false => replyTo ! UserNamePasswordError
        }
        if (r2 == true)
      } self ! DoLoginDomain(userName, Some(replyTo))
    case DoLoginDomain(user, Some(replyTo: ActorRef)) =>
      persist(UserLoggedIn(id, user, 0, System.currentTimeMillis(), JsObject())) { evt =>
        //        state = state.updated(evt)
        replyTo ! evt
      }
    case LogoutDomain(_, user) =>
      val replyTo = sender
      persist(UserLoggedOut(id, user, 0, System.currentTimeMillis(), JsObject())) { evt =>
        //        state = state.updated(evt)
        replyTo ! evt
      }
    case gd @ GetDomain(_, user) =>
      val replyTo = sender
      checkPermission(user, gd).foreach {
        case Granted => self ! DoGetDomain(user, Some(replyTo))
        case Denied  => replyTo ! Denied
      }
    case DoGetDomain(_, Some(replyTo: ActorRef)) =>
      replyTo ! state.domain
    case rd @ ReplaceDomain(_, user, raw) =>
      val replyTo = sender
      checkPermission(user, rd).foreach {
        case Granted => self ! DoReplaceDomain(user, raw, Some(replyTo))
        case Denied  => replyTo ! Denied
      }
    case DoReplaceDomain(user, raw, Some(replyTo: ActorRef)) =>
      persist(DomainReplaced(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        state = state.updated(evt)
        val ds = state.domain.toJson.asJsObject
        saveSnapshot(ds)
        deleteSnapshot(lastSequenceNr - 1)
        replyTo ! evt.copy(raw = ds)
      }
    case pd @ PatchDomain(_, user, patch) =>
      val replyTo = sender
      checkPermission(user, pd).foreach {
        case Granted => self ! DoPatchDomain(user, patch, Some(replyTo))
        case Denied  => replyTo ! Denied
      }
    case DoPatchDomain(user, patch, Some(replyTo: ActorRef)) =>
      Try {
        patch(state.domain.raw)
      } match {
        case Success(result) =>
          persist(DomainPatched(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, JsObject())) { evt =>
            state = state.updated(evt)
            val d = state.domain.toJson.asJsObject
            saveSnapshot(d)
            deleteSnapshot(lastSequenceNr - 1)
            replyTo ! evt.copy(raw = d)
          }
        case Failure(e) => replyTo ! PatchDomainException(e)
      }
    case dd @ DeleteDomain(_, user) =>
      val replyTo = sender
      checkPermission(user, dd).foreach {
        case Granted => self ! DoDeleteDomain(user, Some(replyTo))
        case Denied  => replyTo ! Denied
      }
    case DoDeleteDomain(user, Some(replyTo: ActorRef)) =>
      val result = for {
        r1 <- clearCache
        r2 <- deleteDomain(user)
      } yield Done
      result.foreach { Done => self ! DeleteDomainSuccess(user, Some(replyTo)) }
    case DeleteDomainSuccess(user, Some(replyTo: ActorRef)) =>
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
    case ad @ AuthorizeDomain(_, user, patch) =>
      val replyTo = sender
      checkPermission(user, ad).foreach {
        case Granted => self ! DoAuthorizeDomain(user, patch, Some(replyTo))
        case Denied  => replyTo ! Denied
      }
    case DoAuthorizeDomain(user, patch, Some(replyTo: ActorRef)) =>
      Try {
        patch(state.domain.raw.fields("_metadata").asJsObject.fields("acl"))
      } match {
        case Success(result) =>
          persist(DomainAuthorized(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, JsObject())) { evt =>
            state = state.updated(evt)
            val d = state.domain.toJson.asJsObject
            saveSnapshot(d)
            deleteSnapshot(lastSequenceNr - 1)
            replyTo ! evt.copy(raw = d)
          }
        case Failure(e) => replyTo ! AuthorizeDomainException(e)
      }
    case SaveSnapshotSuccess(metadata)         =>
    case SaveSnapshotFailure(metadata, reason) =>
    case gc @ GarbageCollection(_, user, _) =>
      val replyTo = sender
      checkPermission(user, gc).foreach {
        case Granted => self ! DoGarbageCollection(user, Some(replyTo))
        case Denied  => replyTo ! Denied
      }
    case DoGarbageCollection(user, Some(replyTo: ActorRef)) =>
      garbageCollection(user).foreach { Done => replyTo ! GarbageCollectionCompleted }
    case CreateDomain(_, _, _) => sender ! DomainAlreadyExists
    case CheckPermission(_, user, command) =>
      val replyTo = sender
      checkPermission(user, command).foreach { replyTo ! _ }
  }

  def deleted: Receive = {
    case gd @ GetDomain(_, user) =>
      val replyTo = sender
      checkPermission(user, gd).foreach {
        case Granted => self ! DoGetDomain(user, Some(replyTo))
        case Denied  => replyTo ! Denied
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

  private def registerUser(user: String, userName: String, password: String) = {
    val userInfo = JsObject("name" -> JsString(userName), "password" -> JsString(password))
    documentRegion ? CreateDocument(s"${rootDomain}~users~${userName}", user, userInfo)
  }

  private def joinDomain(user: String, userName: String) = {
    val profile = JsObject("roles" -> JsArray(JsString("user")))
    documentRegion ? CreateDocument(s"${id}~profiles~${userName}", user, profile)
  }

  private def login(username: String, password: String) = {
    (documentRegion ? GetDocument(s"${rootDomain}~users~${username}", username)).map {
      case doc: Document => doc.raw.fields("password").asInstanceOf[JsString].value == password.md5.hex
      case _             => false
    }
  }

  private def checkPermission(user: String, command: Command) = fetchProfile(id, user).map {
    case profile: Document =>
      val aclObj = state.domain.raw.fields("_metadata").asJsObject.fields("acl").asJsObject
      val userRoles = profileValue(profile.raw, "roles")
      val userGroups = profileValue(profile.raw, "groups")
      val (aclRoles, aclGroups, aclUsers) = command match {
        case _: CreateDomain      => (aclValue(aclObj, "create_domain", "roles"), aclValue(aclObj, "create_domain", "groups"), aclValue(aclObj, "create_domain", "users"))
        case _: GetDomain         => (aclValue(aclObj, "get", "roles"), aclValue(aclObj, "get", "groups"), aclValue(aclObj, "get", "users"))
        case _: ReplaceDomain     => (aclValue(aclObj, "replace", "roles"), aclValue(aclObj, "replace", "groups"), aclValue(aclObj, "replace", "users"))
        case _: PatchDomain       => (aclValue(aclObj, "patch", "roles"), aclValue(aclObj, "patch", "groups"), aclValue(aclObj, "patch", "users"))
        case _: AuthorizeDomain   => (aclValue(aclObj, "authorize", "roles"), aclValue(aclObj, "authorize", "groups"), aclValue(aclObj, "authorize", "users"))
        case _: DeleteDomain      => (aclValue(aclObj, "delete", "roles"), aclValue(aclObj, "delete", "groups"), aclValue(aclObj, "delete", "users"))
        case _: LoginDomain       => (aclValue(aclObj, "login", "roles"), aclValue(aclObj, "login", "groups"), aclValue(aclObj, "login", "users"))
        case _: CreateDocumentSet => (aclValue(aclObj, "create_document_set", "roles"), aclValue(aclObj, "create_document_set", "groups"), aclValue(aclObj, "create_document_set", "users"))
        case _: FindDocumentSets  => (aclValue(aclObj, "find_document_sets", "roles"), aclValue(aclObj, "find_document_sets", "groups"), aclValue(aclObj, "find_document_sets", "users"))
        case _: GarbageCollection => (aclValue(aclObj, "gc", "roles"), aclValue(aclObj, "gc", "groups"), aclValue(aclObj, "gc", "users"))
        case _                    => (Vector[String](), Vector[String](), Vector[String]())
      }
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
            .mapAsync(1) { ds => (documentSetRegion ? CreateDocumentSet(s"${id}~.documentsets~${ds}", user, JsObject())) }
            .map { case _: DocumentSetCreated => Done }.runWith(Sink.last[Done])
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
            .mapAsync(1) { ds => documentSetRegion ? CreateDocumentSet(s"${id}~.documentsets~${ds}", user, JsObject()) }
            .map { case _: DocumentSetCreated => Done }.runWith(Sink.last[Done])
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

  private def checkCache: Future[Boolean] = {
    Source.fromFuture {
      replicator ? Get(LWWMapKey[String, Any](cacheKey), readMajority)
    }.map {
      case g @ GetSuccess(LWWMapKey(_), _) =>
        g.dataValue match {
          case data: LWWMap[_, _] => data.asInstanceOf[LWWMap[String, Any]].get(id) match {
            case Some(_) => true
            case None    => false
          }
        }
      case NotFound(_, _) => false
    }.runWith(Sink.last[Boolean])
  }

  private def updateCache: Future[Done] = {
    Source.fromFuture {
      replicator ? Update(LWWMapKey[String, Any](cacheKey), LWWMap(), writeMajority)(_ + (id -> state.domain))
    }.map { case UpdateSuccess(LWWMapKey(_), _) => Done }.runWith(Sink.ignore)
  }

  private def clearCache: Future[Done] = {
    Source.fromFuture {
      replicator ? Update(LWWMapKey[String, Any](cacheKey), LWWMap(), writeMajority)(_ - id)
    }.map { case UpdateSuccess(LWWMapKey(_), _) => Done }.runWith(Sink.ignore)
  }

  private def deleteDomain(user: String): Future[Done] = {
    val source = Source.fromFuture(documentSetRegion ? FindDocuments(s"${id}~.documentsets~.documentsets", user, Seq[(String, String)](), JsObject()))
      .mapConcat(extractElements).filter { t => !t._2 }.mapAsync(1) { t =>
        documentSetRegion ? DeleteDocumentSet(s"${id}~.documentsets~${t._1}", user)
      }.map { case _: DocumentSetDeleted => Done }

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
