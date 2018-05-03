package com.zxtx.actors

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import scala.util.Try

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

object UserActor {

  def props(): Props = Props[UserActor]

  object User {
    val empty = new User("", "", 0L, 0L, 0L, None, JsObject())
  }
  case class User(id: String, author: String = "anonymous", revision: Long, created: Long, updated: Long, deleted: Option[Boolean], raw: JsObject)

  case class CreateUser(pid: String, user: String, raw: JsObject) extends Command
  case class GetUser(pid: String, user: String) extends Command
  case class ReplaceUser(pid: String, user: String, raw: JsObject) extends Command
  case class PatchUser(pid: String, user: String, patch: JsonPatch) extends Command
  case class DeleteUser(pid: String, user: String) extends Command
  case class ResetPassword(pid: String, user: String, newPassword: String) extends Command

  private case class DoCreateUser(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoGetUser(user: String, request: Option[Any] = None)
  private case class DoReplaceUser(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoPatchUser(user: String, patch: JsonPatch, raw: JsObject, request: Option[Any] = None)
  private case class DoDeleteUser(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoResetPassword(user: String, patch: JsonPatch, raw: JsObject, request: Option[Any] = None)

  case class UserCreated(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class UserReplaced(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class UserPatched(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends DocumentEvent
  case class UserDeleted(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends DocumentEvent
  case class PasswordReseted(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends DocumentEvent

  case object UserNotFound extends Exception
  case object UserAlreadyExists extends Exception
  case object UserIsCreating extends Exception
  case object UserSoftDeleted extends Exception
  case object UserAlreadyRegistered extends Exception
  case object UserNamePasswordError extends Exception
  case object UserNameNotExists extends Exception
  case object PasswordNotExists extends Exception
  case class PatchUserException(exception: Throwable) extends Exception
  case class AuthorizeUserException(exception: Throwable) extends Exception
  case class ResetPasswordException(exception: Throwable) extends Exception

  val idExtractor: ShardRegion.ExtractEntityId = { case cmd: Command => (cmd.pid, cmd) }
  val shardResolver: ShardRegion.ExtractShardId = { case cmd: Command => (math.abs(cmd.pid.hashCode) % 100).toString }
  val shardName: String = "User"

  def persistenceId(rootDomain: String, userId: String): String = s"${rootDomain}~users~${userId}"

  object JsonProtocol extends DocumentJsonProtocol {
    implicit object UserFormat extends RootJsonFormat[User] {
      def write(d: User) = {
        val metaObj = newMetaObject(d.raw.getFields("_metadata"), d.author, d.revision, d.created, d.updated, d.deleted)
        JsObject(("id" -> JsString(d.id)) :: d.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, updated, deleted, jo) = extractFieldsWithUpdatedDeleted(value, "User expected!")
        User(id, author, revision, created, updated, deleted, jo)
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

    implicit object UserDeletedFormat extends RootJsonFormat[UserDeleted] {
      def write(dd: UserDeleted) = {
        val metaObj = newMetaObject(dd.raw.getFields("_metadata"), dd.author, dd.revision, dd.created)
        JsObject(("id" -> JsString(dd.id)) :: dd.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, jo) = extractFields(value, "UserDeleted event expected!")
        UserDeleted(id, author, revision, created, jo)
      }
    }

    implicit object PasswordResetedFormat extends RootJsonFormat[PasswordReseted] {
      import gnieh.diffson.sprayJson.provider.marshall
      import gnieh.diffson.sprayJson.provider.patchMarshaller
      def write(pr: PasswordReseted) = {
        val metaObj = newMetaObject(pr.raw.getFields("_metadata"), pr.author, pr.revision, pr.created)
        JsObject(("id" -> JsString(pr.id)) :: ("patch" -> marshall(pr.patch)) :: pr.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, patch, jo) = extractFieldsWithPatch(value, "PasswordReseted event expected!")
        PasswordReseted(id, author, revision, created, patch, jo)
      }
    }

  }

  private case class State(user: User, deleted: Boolean) {
    def updated(evt: DocumentEvent): State = evt match {
      case UserCreated(id, author, revision, created, raw) =>
        val metadata = JsObject(raw.fields("_metadata").asJsObject.fields + ("updated" -> JsNumber(created)))
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
      case UserDeleted(_, _, revision, created, _) =>
        val oldMetaFields = user.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("deleted" -> JsBoolean(true)))
        copy(user = user.copy(revision = revision, updated = created, deleted = Some(true), raw = JsObject(user.raw.fields + ("_metadata" -> metadata))))
      case PasswordReseted(_, _, revision, created, patch, _) =>
        val patchedDoc = patch(user.raw).asJsObject
        val oldMetaFields = patchedDoc.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)))
        copy(user = user.copy(revision = revision, updated = created, raw = JsObject(patchedDoc.fields + ("_metadata" -> metadata))))
      case ACLSet(_, _, revision, created, patch, _) =>
        val oldMetadata = user.raw.fields("_metadata").asJsObject
        val patchedAuth = patch(oldMetadata.fields("acl"))
        val metadata = JsObject(oldMetadata.fields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("acl" -> patchedAuth))
        copy(user = user.copy(revision = revision, updated = created, raw = JsObject(user.raw.fields + ("_metadata" -> metadata))))
      case _ => copy(user = user)
    }

    def updated(d: User): State = d match {
      case User(id, author, revision, created, updated, deleted, raw) => copy(user = User(id, author, revision, created, updated, deleted, raw))
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
        "reset_password":{
            "roles":["administrator"],
            "users":["${user}"]
        },
        "set_acl":{
            "roles":["administrator"],
            "users":["${user}"]
        }
      }""".parseJson.asJsObject
}

class UserActor extends PersistentActor with ACL with ActorLogging {
  import CollectionActor._
  import UserActor._
  import UserActor.JsonProtocol._

  val rootDomain = system.settings.config.getString("domain.root-domain")
  val adminName = system.settings.config.getString("domain.administrator.name")
  val cacheKey = system.settings.config.getString("domain.cache-key")
  val domainRegion = ClusterSharding(system).shardRegion(UserActor.shardName)
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

  private var state = State(User.empty, false)

  override def receiveRecover: Receive = {
    case evt: UserCreated =>
      context.become(created)
      state = state.updated(evt)
    case evt: UserDeleted =>
      context.become(deleted)
      state = state.updated(evt)
    case evt: DocumentEvent =>
      state = state.updated(evt)
    case SnapshotOffer(_, jo: JsObject) =>
      val d = jo.convertTo[User]
      state = state.updated(d)
      d.deleted match {
        case Some(true) => context.become(deleted)
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
        val d = state.user.toJson.asJsObject
        saveSnapshot(d)
        context.become(created)
        replyTo ! evt.copy(raw = d)
      }
    case _: Command => sender ! UserIsCreating
  }

  def created: Receive = {
    case GetUser(_, user) =>
      val replyTo = sender
      getUser(user).foreach {
        case dgd: DoGetUser => replyTo ! state.user
        case other          => replyTo ! other
      }
    case ReplaceUser(_, user, raw) =>
      val replyTo = sender
      replaceUser(user, raw).foreach {
        case drd: DoReplaceUser => self ! drd.copy(request = Some(replyTo))
        case other              => replyTo ! other
      }
    case DoReplaceUser(user, raw, Some(replyTo: ActorRef)) =>
      persist(UserReplaced(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        state = state.updated(evt)
        val ds = state.user.toJson.asJsObject
        saveSnapshot(ds)
        deleteSnapshot(lastSequenceNr - 1)
        replyTo ! evt.copy(raw = ds)
      }
    case PatchUser(_, user, patch) =>
      val replyTo = sender
      patchUser(user, patch).foreach {
        case dpd: DoPatchUser => self ! dpd.copy(request = Some(replyTo))
        case other            => replyTo ! other
      }
    case DoPatchUser(user, patch, raw, Some(replyTo: ActorRef)) =>
      persist(UserPatched(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, raw)) { evt =>
        state = state.updated(evt)
        val d = state.user.toJson.asJsObject
        saveSnapshot(d)
        deleteSnapshot(lastSequenceNr - 1)
        replyTo ! evt.copy(raw = d)
      }
    case DeleteUser(_, user) =>
      val replyTo = sender
      deleteUser(user).foreach {
        case ddd: DoDeleteUser => self ! ddd.copy(request = Some(replyTo))
        case other             => replyTo ! other
      }
    case DoDeleteUser(user, raw, Some(replyTo: ActorRef)) =>
      persist(UserDeleted(id, user, lastSequenceNr + 1, System.currentTimeMillis(), raw)) { evt =>
        state = state.updated(evt)
        deleteMessages(lastSequenceNr)
        deleteSnapshot(lastSequenceNr - 1)
        val d = state.user.toJson.asJsObject
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
    case SetACL(_, user, patch) =>
      val replyTo = sender
      setACL(user, state.user.raw.fields("_metadata").asJsObject.fields("acl"), patch).foreach {
        case dsa: DoSetACL => self ! dsa.copy(request = Some(replyTo))
        case other         => replyTo ! other
      }
    case DoSetACL(user, patch, raw, Some(replyTo: ActorRef)) =>
      persist(ACLSet(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, raw)) { evt =>
        state = state.updated(evt)
        val d = state.user.toJson.asJsObject
        saveSnapshot(d)
        deleteSnapshot(lastSequenceNr - 1)
        replyTo ! evt.copy(raw = d)
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
    case ResetPassword(_, user, newPassword) =>
      val replyTo = sender
      resetPassword(user, newPassword).foreach {
        case drp: DoResetPassword => self ! drp.copy(request = Some(replyTo))
        case other                => replyTo ! other

      }
    case DoResetPassword(user, patch, raw, Some(replyTo: ActorRef)) =>
      persist(PasswordReseted(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, raw)) { evt =>
        state = state.updated(evt)
        val d = state.user.toJson.asJsObject
        saveSnapshot(d)
        deleteSnapshot(lastSequenceNr - 1)
        replyTo ! evt.copy(raw = d)
      }
    case SaveSnapshotSuccess(metadata)         =>
    case SaveSnapshotFailure(metadata, reason) =>
    case CreateUser(_, _, _)                   => sender ! UserAlreadyExists
  }

  def deleted: Receive = {
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
    case _: Command => sender ! UserSoftDeleted
  }

  override def unhandled(msg: Any): Unit = msg match {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
    case _              => super.unhandled(msg)
  }

  private def createUser(user: String, userInfo: JsObject) =
    (collectionRegion ? CheckPermission(s"${rootDomain}~.collections~users", user, CreateUser)).map {
      case Granted =>
        val fields = userInfo.fields
        fields.get("userName") match {
          case Some(JsString(userName)) =>
            fields.get("password") match {
              case Some(JsString(password)) => DoCreateUser(user, JsObject(fields + ("password" -> JsString(password.md5.hex)) + ("_metadata" -> JsObject("acl" -> eventACL(user)))))
              case None                     => PasswordNotExists
            }
          case None => UserNameNotExists
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

  private def deleteUser(user: String) = checkPermission(user, DeleteUser).map {
    case Granted => DoDeleteUser(user, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
    case Denied  => Denied
  }.recover { case e => e }

  private def deleteProfiles(user: String) = {

  }

  private def clearACLs(user: String) = {
    //{
    //  "script": "item_to_remove = nil; foreach (item : ctx._source.list) { if (item['tweet_id'] == tweet_id) { item_to_remove=item; } } if (item_to_remove != nil) ctx._source.list.remove(item_to_remove);",
    //  "params": {"tweet_id": "123"}
    //}

    //{
    //  "script": "items_to_remove = []; foreach (item : ctx._source.list) { if (item['tweet_id'] == tweet_id) { items_to_remove.add(item); } } foreach (item : items_to_remove) {ctx._source.list.remove(item);}",
    //  "params": {"tweet_id": "123"}
    //}

    val search = s"""{
      "query":{
        "bool":{
          "should":[
            {"term":{"_metadata.acl.create_domain.users":"${user}"}},
            {"term":{"_metadata.acl.create_collection.users":"${user}"}},
            {"term":{"_metadata.acl.create_document.users":"${user}"}},
            {"term":{"_metadata.acl.get.users":"${user}"}},
            {"term":{"_metadata.acl.replace.users":"${user}"}},
            {"term":{"_metadata.acl.patch.users":"${user}"}},
            {"term":{"_metadata.acl.delete.users":"${user}"}},
            {"term":{"_metadata.acl.reset_password.users":"${user}"}},
            {"term":{"_metadata.acl.list_collections.users":"${user}"}},
            {"term":{"_metadata.acl.find_documents.users":"${user}"}},
            {"term":{"_metadata.acl.execute.users":"${user}"}}            
          ]
        }
      }
    }"""

    val uri = s"http://localhost:9200/*~snapshots-*/_search"
    store.get(uri = uri, entity = search).map {
      case (StatusCodes.OK, jv) => jv.asJsObject.fields("hits").asJsObject.fields("hits")
        .asInstanceOf[JsArray].elements.map { jv => jv.asJsObject.fields("_source").asJsObject }
      case (code, _) => throw new RuntimeException(s"Error delete messages:$code")
    }
  }

  private def resetPassword(user: String, newPassword: String) = checkPermission(user, ResetPassword).map {
    case Granted =>
      val patch = JsonPatch(s"""[{
        "op":"replace",
        "path":"/password",
        "value":"${newPassword.md5.hex}"
        }]""".parseJson)
      Try {
        patch(state.user.raw)
      } match {
        case Success(_) => DoResetPassword(user, patch, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
        case Failure(e) => ResetPasswordException(e)
      }
    case Denied => Denied
  }.recover { case e => e }

  override def checkPermission(user: String, command: Any) = fetchProfile(id, user).map {
    case Document(_, _, _, _, _, _, profile) =>
      val aclObj = state.user.raw.fields("_metadata").asJsObject.fields("acl").asJsObject
      val userRoles = profileValue(profile, "roles")
      val userGroups = profileValue(profile, "groups")
      val (aclRoles, aclGroups, aclUsers) = command match {
        case CreateUser  => (aclValue(aclObj, "create_domain", "roles"), aclValue(aclObj, "create_domain", "groups"), aclValue(aclObj, "create_domain", "users"))
        case GetUser     => (aclValue(aclObj, "get", "roles"), aclValue(aclObj, "get", "groups"), aclValue(aclObj, "get", "users"))
        case ReplaceUser => (aclValue(aclObj, "replace", "roles"), aclValue(aclObj, "replace", "groups"), aclValue(aclObj, "replace", "users"))
        case PatchUser   => (aclValue(aclObj, "patch", "roles"), aclValue(aclObj, "patch", "groups"), aclValue(aclObj, "patch", "users"))
        case SetACL      => (aclValue(aclObj, "set_acl", "roles"), aclValue(aclObj, "set_acl", "groups"), aclValue(aclObj, "set_acl", "users"))
        case SetEventACL => (aclValue(aclObj, "set_event_acl", "roles"), aclValue(aclObj, "set_event_acl", "groups"), aclValue(aclObj, "set_event_acl", "users"))
        case DeleteUser  => (aclValue(aclObj, "delete", "roles"), aclValue(aclObj, "delete", "groups"), aclValue(aclObj, "delete", "users"))
        case _           => (Vector[String](), Vector[String](), Vector[String]())
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
