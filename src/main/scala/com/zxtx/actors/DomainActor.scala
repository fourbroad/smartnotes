package com.zxtx.actors

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.roundeights.hasher.Implicits.stringToHasher

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
  case class FindCollections(pid: String, user: String, params: Seq[(String, String)], body: JsObject) extends Command
  case class RegisterUser(pid: String, user: String, userName: String, password: String, userInfo: Option[JsObject] = None) extends Command
  case class JoinDomain(pid: String, user: String, userName: String, permission: Option[JsObject] = None) extends Command
  case class QuitDomain(pid: String, user: String, userName: String) extends Command

  private case class DoCreateDomain(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoGetDomain(user: String, request: Option[Any] = None)
  private case class DoReplaceDomain(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoPatchDomain(user: String, patch: JsonPatch, request: Option[Any] = None)
  private case class DoAuthorizeDomain(user: String, patch: JsonPatch, request: Option[Any] = None)
  private case class DoDeleteDomain(user: String, request: Option[Any] = None)
  private case class DoFindCollections(user: String, params: Seq[(String, String)], body: JsObject, request: Option[Any] = None)
  private case class DoRegisterUser(user: String, userName: String, raw: JsObject, request: Option[Any] = None)
  private case class DoJoinDomain(user: String, userName: String, request: Option[Any] = None)
  private case class DoQuitDomain(user: String, userName: String, request: Option[Any] = None)

  case class DomainCreated(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DomainReplaced(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DomainPatched(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends DocumentEvent
  case class DomainDeleted(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DomainAuthorized(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends DocumentEvent
  case class UserRegistered(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DomainJoined(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class DomainQuited(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent

  case object DomainNotFound extends Exception
  case object DomainAlreadyExists extends Exception
  case object DomainIsCreating extends Exception
  case object DomainSoftDeleted extends Exception
  case object UserAlreadyRegistered extends Exception
  case object UserAlreadyJoined extends Exception
  case object UserNotJoined extends Exception
  case object UserNamePasswordError extends Exception
  case class PatchDomainException(exception: Throwable) extends Exception
  case class AuthorizeDomainException(exception: Throwable) extends Exception

  val idExtractor: ShardRegion.ExtractEntityId = { case cmd: Command => (cmd.pid, cmd) }
  val shardResolver: ShardRegion.ExtractShardId = { case cmd: Command => (math.abs(cmd.pid.hashCode) % 100).toString }
  val shardName: String = "Domain"

  def persistenceId(rootDomain: String, domain: String): String = s"${rootDomain}~.domains~${domain}"

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

    implicit object DomainAuthorizedFormat extends RootJsonFormat[DomainAuthorized] {
      import gnieh.diffson.sprayJson.provider.marshall
      import gnieh.diffson.sprayJson.provider.patchMarshaller
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
        "register_user":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "join_domain":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "quit_domain":{
            "roles":["administrator"],
            "users":["${user}"]
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

  val rootDomain = system.settings.config.getString("domain.root-domain")
  val adminName = system.settings.config.getString("domain.administrator.name")
  val cacheKey = system.settings.config.getString("domain.cache-key")
  val domainRegion = ClusterSharding(system).shardRegion(DomainActor.shardName)
  val collectionRegion = ClusterSharding(system).shardRegion(CollectionActor.shardName)

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
    case _: Command => sender ! DomainNotFound
  }

  def creating: Receive = {
    case DoCreateDomain(user, raw, Some(replyTo: ActorRef)) =>
      persist(DomainCreated(id, user, lastSequenceNr + 1, System.currentTimeMillis, JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> DomainActor.defaultACL(user)))))) { evt =>
        state = state.updated(evt)
        val d = state.domain.toJson.asJsObject
        saveSnapshot(d)
        context.become(created)
        replyTo ! evt.copy(raw = d)
      }
    case CheckPermission(_, _, CreateCollection) => sender ! Granted
    case _: Command                              => sender ! DomainIsCreating
  }

  def created: Receive = {
    case RegisterUser(_, user, userName, password, userInfo) =>
      val replyTo = sender
      registerUser(user, userName, password, userInfo).foreach {
        case dru: DoRegisterUser => self ! dru.copy(request = Some(replyTo))
        case other               => replyTo ! other
      }
    case DoRegisterUser(user, userName, raw, Some(replyTo: ActorRef)) =>
      persist(UserRegistered(id, user, 0, System.currentTimeMillis, JsObject(raw.fields + ("name" -> JsString(userName))))) { evt =>
        replyTo ! evt
      }
    case JoinDomain(_, user, userName, permission) =>
      val replyTo = sender
      joinDomain(user, userName, permission).foreach {
        case djd: DoJoinDomain => self ! djd.copy(request = Some(replyTo))
        case other             => replyTo ! other
      }
    case DoJoinDomain(user, userName, Some(replyTo: ActorRef)) =>
      persist(DomainJoined(id, user, 0, System.currentTimeMillis, JsObject("name" -> JsString(userName)))) { evt =>
        replyTo ! evt
      }
    case QuitDomain(_, user, userName) =>
      val replyTo = sender
      quitDomain(user, userName).foreach {
        case dqd: DoQuitDomain => self ! dqd.copy(request = Some(replyTo))
        case other             => replyTo ! other
      }
    case DoQuitDomain(user, userName, Some(replyTo: ActorRef)) =>
      persist(DomainQuited(id, user, 0, System.currentTimeMillis, JsObject())) { evt =>
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
        state = state.updated(evt)
        val ds = state.domain.toJson.asJsObject
        saveSnapshot(ds)
        deleteSnapshot(lastSequenceNr - 1)
        replyTo ! evt.copy(raw = ds)
      }
    case PatchDomain(_, user, patch) =>
      val replyTo = sender
      patchDomain(user, patch).foreach {
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
    case DeleteDomain(_, user) =>
      val replyTo = sender
      deleteDomain(user).foreach {
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
    case AuthorizeDomain(_, user, patch) =>
      val replyTo = sender
      authorizeDomain(user, patch).foreach {
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

  private def createDomain(user: String, raw: JsObject) = id match {
    case `rootDomain` => initRootDomainCollections(user).map {
      case Done  => DoCreateDomain(user, raw)
      case other => other
    }.recover { case e => e }
    case _ => (domainRegion ? CheckPermission(s"${rootDomain}~.domains~${rootDomain}", user, CreateDomain)).flatMap {
      case Granted => initCollections(user).map {
        case Done  => DoCreateDomain(user, raw)
        case other => other
      }
      case Denied => Future.successful(Denied)
    }.recover { case e => e }
  }

  private def getDomain(user: String) = checkPermission(user, GetDomain).map {
    case Granted => DoGetDomain(user)
    case other   => other
  }.recover { case e => e }

  private def registerUser(user: String, userName: String, password: String, userInfo: Option[JsObject] = None) = checkPermission(user, RegisterUser).flatMap {
    case Granted =>
      val passwordMd5 = password.md5.hex
      val info = userInfo match {
        case Some(jo) => JsObject(jo.fields + ("name" -> JsString(userName)) + ("password" -> JsString(passwordMd5)))
        case None     => JsObject("name" -> JsString(userName), "password" -> JsString(passwordMd5))
      }
      (documentRegion ? CreateDocument(s"${rootDomain}~users~${userName}", user, info)).map {
        case DocumentCreated(_, _, _, _, raw) => DoRegisterUser(user, userName, raw)
        case DocumentAlreadyExists            => UserAlreadyRegistered
      }
    case Denied => Future.successful(Denied)
  }.recover { case e => e }

  private def joinDomain(user: String, userName: String, permission: Option[JsObject] = None) = checkPermission(user, JoinDomain).flatMap {
    case Granted =>
      val profile = permission match {
        case Some(jo) => JsObject(jo.fields + ("roles" -> JsArray(JsString("user"))))
        case None     => JsObject("roles" -> JsArray(JsString("user")))
      }
      (documentRegion ? CreateDocument(DocumentActor.persistenceId(id, "profiles", userName), user, profile)).map {
        case _: DocumentCreated    => DoJoinDomain(user, userName)
        case DocumentAlreadyExists => UserAlreadyJoined
      }
    case Denied => Future.successful(Denied)
  }.recover { case e => e }

  private def quitDomain(user: String, userName: String) = checkPermission(user, QuitDomain).flatMap {
    case Granted =>
      (documentRegion ? DeleteDocument(DocumentActor.persistenceId(id, "profiles", userName), user)).map {
        case _: DocumentDeleted => DoQuitDomain(user, userName)
        case other              => other
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
        case Success(_) => DoPatchDomain(user, patch)
        case Failure(e) => PatchDomainException(e)
      }
    case Denied => Denied
  }.recover { case e => e }

  private def deleteDomain(user: String) = checkPermission(user, DeleteDomain).flatMap {
    case Granted =>
      val source = Source.fromFuture(collectionRegion ? FindDocuments(CollectionActor.persistenceId(id, ".collection"), user, Seq[(String, String)](), JsObject()))
        .mapConcat(extractElements).filter { t => !t._2 }.mapAsync(1) { t =>
          collectionRegion ? DeleteCollection(s"${id}~.collections~${t._1}", user)
        }.map { case _: CollectionDeleted => Done }

      val result = id match {
        case `rootDomain` =>
          val domainRegion = ClusterSharding(system).shardRegion(DomainActor.shardName)
          for {
            r1 <- Source.fromFuture(collectionRegion ? FindDocuments(CollectionActor.persistenceId(rootDomain, ".domains"), user, Seq[(String, String)](), JsObject()))
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
      result.map {
        case Done  => DoDeleteDomain(user)
        case other => other
      }
    case Denied => Future.successful(Denied)
  }.recover { case e => e }

  private def authorizeDomain(user: String, patch: JsonPatch) = checkPermission(user, AuthorizeDomain).map {
    case Granted =>
      Try {
        patch(state.domain.raw.fields("_metadata").asJsObject.fields("acl"))
      } match {
        case Success(_) => DoAuthorizeDomain(user, patch)
        case Failure(e) => AuthorizeDomainException(e)
      }
    case Denied => Denied
  }.recover { case e => e }

  private def garbageCollection(user: String) = checkPermission(user, GarbageCollection).flatMap {
    case Granted =>
      Thread.sleep(1000) // Bugfix: delay for deleting document in ElasticSearch.

      val source = Source.fromFuture(collectionRegion ? FindDocuments(s"${id}~.collections~.collections", user, Seq[(String, String)](), JsObject()))
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
            r1 <- Source.fromFuture(collectionRegion ? FindDocuments(s"${rootDomain}~.collections~.domains", user, Seq[(String, String)](), JsObject()))
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
    case Denied => Future.successful(Denied)
  }.recover { case e => e }

  override def checkPermission(user: String, command: Any) = fetchProfile(id, user).map {
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
        case RegisterUser      => (aclValue(aclObj, "register_user", "roles"), aclValue(aclObj, "register_user", "groups"), aclValue(aclObj, "register_user", "users"))
        case JoinDomain        => (aclValue(aclObj, "join_domain", "roles"), aclValue(aclObj, "join_domain", "groups"), aclValue(aclObj, "join_domain", "users"))
        case QuitDomain        => (aclValue(aclObj, "quit_domain", "roles"), aclValue(aclObj, "quit_domain", "groups"), aclValue(aclObj, "quit_domain", "users"))
        case CreateCollection  => (aclValue(aclObj, "create_document_set", "roles"), aclValue(aclObj, "create_document_set", "groups"), aclValue(aclObj, "create_document_set", "users"))
        case FindCollections   => (aclValue(aclObj, "find_document_sets", "roles"), aclValue(aclObj, "find_document_sets", "groups"), aclValue(aclObj, "find_document_sets", "users"))
        case GarbageCollection => (aclValue(aclObj, "gc", "roles"), aclValue(aclObj, "gc", "groups"), aclValue(aclObj, "gc", "users"))
        case _                 => (Vector[String](), Vector[String](), Vector[String]())
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
      r2 <- collectionRegion ? CreateCollection(CollectionActor.persistenceId(id, "roles"), user, JsObject(), Some(true))
      r3 <- collectionRegion ? CreateCollection(CollectionActor.persistenceId(id, "profiles"), user, JsObject(), Some(true))
      r4 <- documentRegion ? CreateDocument(DocumentActor.persistenceId(id, "roles", "administrator"), user, adminRole, Some(true))
      r5 <- documentRegion ? CreateDocument(DocumentActor.persistenceId(id, "roles", "user"), user, userRole, Some(true))
      r6 <- documentRegion ? CreateDocument(DocumentActor.persistenceId(id, "profiles", user), user, profile, Some(true))
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
      r3 <- collectionRegion ? CreateCollection(CollectionActor.persistenceId(rootDomain, "users"), adminName, JsObject(), Some(true))
      r4 <- collectionRegion ? CreateCollection(CollectionActor.persistenceId(rootDomain, "roles"), adminName, JsObject(), Some(true))
      r5 <- collectionRegion ? CreateCollection(CollectionActor.persistenceId(rootDomain, "profiles"), adminName, JsObject(), Some(true))
      r6 <- documentRegion ? CreateDocument(DocumentActor.persistenceId(rootDomain, "users", adminName), adminName, adminUser, Some(true))
      r7 <- documentRegion ? CreateDocument(DocumentActor.persistenceId(rootDomain, "roles", "administrator"), adminName, adminRole, Some(true))
      r8 <- documentRegion ? CreateDocument(DocumentActor.persistenceId(rootDomain, "roles", "user"), adminName, userRole, Some(true))
      r9 <- documentRegion ? CreateDocument(DocumentActor.persistenceId(rootDomain, "profiles", adminName), adminName, profile, Some(true))
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
