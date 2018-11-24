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

object FormActor {

  def props(): Props = Props[FormActor]

  object Form {
    val empty = new Form("", "", 0L, 0L, 0L, None, spray.json.JsObject())
  }
  case class Form(id: String, author: String = "anonymous", revision: Long, created: Long, updated: Long, removed: Option[Boolean], raw: JsObject)

  case class GetForm(pid: String, user: String) extends Command
  case class CreateForm(pid: String, user: String, raw: JsObject, initFlag: Option[Boolean] = None) extends Command
  case class ReplaceForm(pid: String, user: String, raw: JsObject) extends Command
  case class PatchForm(pid: String, user: String, patch: JsonPatch) extends Command
  case class RemoveForm(pid: String, user: String) extends Command

  private case class DoGetForm(user: String, request: Option[Any] = None)
  private case class DoCreateForm(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoReplaceForm(user: String, raw: JsObject, request: Option[Any] = None)
  private case class DoPatchForm(user: String, patch: JsonPatch, raw: JsObject, request: Option[Any] = None)
  private case class DoRemoveForm(user: String, raw: JsObject, request: Option[Any] = None)

  case class FormCreated(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case class FormReplaced(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event
  case class FormPatched(id: String, author: String, revision: Long, created: Long, patch: JsonPatch, raw: JsObject) extends Event
  case class FormRemoved(id: String, author: String, revision: Long, created: Long, raw: JsObject) extends Event

  case object FormNotFound extends Exception
  case object FormAlreadyExists extends Exception
  case object FormIsCreating extends Exception
  case object FormSoftRemoved extends Exception
  case class PatchFormException(exception: Throwable) extends Exception

  val idExtractor: ShardRegion.ExtractEntityId = { case cmd: Command => (cmd.pid, cmd) }
  val shardResolver: ShardRegion.ExtractShardId = { case cmd: Command => (math.abs(cmd.pid.hashCode) % 100).toString }
  val shardName: String = "Forms"

  def persistenceId(domainId: String, formId: String) = s"${domainId}~.forms~${formId}"

  object JsonProtocol extends DocumentJsonProtocol {
    implicit object FormFormat extends RootJsonFormat[Form] {
      def write(form: Form) = {
        val metaObj = newMetaObject(form.raw.getFields("_metadata"), form.author, form.revision, form.created, form.updated, form.removed)
        JsObject(("id" -> JsString(form.id)) :: form.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, updated, removed, raw) = extractFieldsWithUpdatedRemoved(value, "Form expected!")
        Form(id, author, revision, created, updated, removed, raw)
      }
    }

    implicit object FormCreatedFormat extends RootJsonFormat[FormCreated] {
      def write(dc: FormCreated) = {
        val metaObj = newMetaObject(dc.raw.getFields("_metadata"), dc.author, dc.revision, dc.created)
        JsObject(("id" -> JsString(dc.id)) :: dc.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, raw) = extractFields(value, "FormCreated event expected!")
        FormCreated(id, author, revision, created, raw)
      }
    }

    implicit object FormReplacedFormat extends RootJsonFormat[FormReplaced] {
      def write(dr: FormReplaced) = {
        val metaObj = newMetaObject(dr.raw.getFields("_metadata"), dr.author, dr.revision, dr.created)
        JsObject(("id" -> JsString(dr.id)) :: dr.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, raw) = extractFields(value, "FormReplaced event expected!")
        FormReplaced(id, author, revision, created, raw)
      }
    }

    implicit object FormPatchedFormat extends RootJsonFormat[FormPatched] {
      import gnieh.diffson.sprayJson.provider._
      import gnieh.diffson.sprayJson.provider.marshall

      def write(dp: FormPatched) = {
        val metaObj = newMetaObject(dp.raw.getFields("_metadata"), dp.author, dp.revision, dp.created)
        spray.json.JsObject(("id" -> JsString(dp.id)) :: ("patch" -> patchValueToString(marshall(dp.patch),"FormPatched event expected!")) :: dp.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, patch, raw) = extractFieldsWithPatch(value, "FormPatched event expected!")
        FormPatched(id, author, revision, created, patch, raw)
      }
    }

    implicit object FormRemovedFormat extends RootJsonFormat[FormRemoved] {
      def write(dd: FormRemoved) = {
        val metaObj = newMetaObject(dd.raw.getFields("_metadata"), dd.author, dd.revision, dd.created)
        JsObject(("id" -> JsString(dd.id)) :: dd.raw.fields.toList ::: ("_metadata" -> metaObj) :: Nil)
      }
      def read(value: JsValue) = {
        val (id, author, revision, created, raw) = extractFields(value, "FormRemoved event expected!")
        FormRemoved(id, author, revision, created, raw)
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

  private case class State(form: Form) {
    def updated(evt: Event): State = evt match {
      case FormCreated(id, author, revision, created, raw) =>
        val metaFields = raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(metaFields+("author" -> JsString(author))+("revision" -> JsNumber(revision))+("created" -> JsNumber(created))+("updated" -> JsNumber(created))+("acl" -> acl(author)))
        copy(form = Form(id, author, revision, created, created, None, JsObject(raw.fields + ("_metadata" -> metadata))))
      case FormReplaced(_, _, revision, created, raw) =>
        val oldMetaFields = form.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)))
        copy(form = form.copy(revision = revision, updated = created, raw = JsObject(raw.fields + ("_metadata" -> metadata))))
      case FormPatched(_, _, revision, created, patch, _) =>
        val patchedDoc = patch(form.raw).asJsObject
        val oldMetaFields = patchedDoc.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)))
        copy(form = form.copy(revision = revision, updated = created, raw = JsObject((patchedDoc.fields - "_metadata") + ("_metadata" -> metadata))))
      case FormRemoved(_, _, revision, created, _) =>
        val oldMetaFields = form.raw.fields("_metadata").asJsObject.fields
        val metadata = JsObject(oldMetaFields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("removed" -> JsBoolean(true)))
        copy(form = form.copy(revision = revision, updated = created, removed = Some(true), raw = JsObject(form.raw.fields + ("_metadata" -> metadata))))
      case ACLReplaced(_, _, revision, created, raw) =>
        val oldMetadata = form.raw.fields("_metadata").asJsObject
        val replacedACL = JsObject(raw.fields - "_metadata" - "id")
        val metadata = JsObject(oldMetadata.fields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("acl" -> replacedACL))
        copy(form = form.copy(revision = revision, updated = created, raw = JsObject(form.raw.fields + ("_metadata" -> metadata))))
      case ACLPatched(_, _, revision, created, patch, _) =>
        val oldMetadata = form.raw.fields("_metadata").asJsObject
        val patchedACL = patch(oldMetadata.fields("acl"))
        val metadata = JsObject(oldMetadata.fields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("acl" -> patchedACL))
        copy(form = form.copy(revision = revision, updated = created, raw = JsObject(form.raw.fields + ("_metadata" -> metadata))))
      case PermissionSubjectRemoved(_, _, revision, created, raw) =>
        val oldMetadata = form.raw.fields("_metadata").asJsObject
        val operation = raw.fields("operation").asInstanceOf[JsString].value
        val kind = raw.fields("kind").asInstanceOf[JsString].value
        val subject = raw.fields("subject").asInstanceOf[JsString].value
        val acl = doRemovePermissionSubject(oldMetadata.fields("acl").asJsObject, operation, kind, subject)
        val metadata = JsObject(oldMetadata.fields + ("revision" -> JsNumber(revision)) + ("updated" -> JsNumber(created)) + ("acl" -> acl))
        copy(form = form.copy(revision = revision, updated = created, raw = JsObject(form.raw.fields + ("_metadata" -> metadata))))
      case _ => copy(form = form)
    }

    def updated(form: Form): State = form match {
      case Form(id, author, revision, created, updated, removed, raw) => copy(form = Form(id, author, revision, created, updated, removed, raw))
    }
  }
}

class FormActor extends PersistentActor with ACL with ActorLogging {
  import FormActor._
  import FormActor.JsonProtocol._
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

  private var state = State(Form.empty)

  override def receiveRecover: Receive = {
    case evt: FormCreated =>
      state = state.updated(evt)
      context.become(created)
    case evt: FormRemoved =>
      state = state.updated(evt)
      context.become(removed)
    case evt: Event =>
      state = state.updated(evt)
    case SnapshotOffer(_, jo: JsObject) =>
      val form = jo.convertTo[Form]
      state = state.updated(form)
      form.removed match {
        case Some(true) => context.become(removed)
        case _          => context.become(created)
      }
    case RecoveryCompleted =>
      log.debug("FormActor recovery completed.")
  }

  override def receiveCommand: Receive = initial

  def initial: Receive = {
    case CreateForm(_, user, raw, initFlag) =>
      val replyTo = sender
      val parent = context.parent
      createForm(user, raw, initFlag).foreach {
        case dcd: DoCreateForm => self ! dcd.copy(request = Some(replyTo))
        case other =>
          replyTo ! other
          parent ! Passivate(stopMessage = PoisonPill)
      }
      context.become(creating)
    case _: Command => sender ! FormNotFound
  }

  def creating: Receive = {
    case DoCreateForm(user, raw, Some(replyTo: ActorRef)) =>
      persist(FormCreated(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        state = state.updated(evt)
        val form = state.form.toJson.asJsObject
        saveSnapshot(form)
        context.become(created)
        replyTo ! state.form
      }
    case _: Command => sender ! FormIsCreating
  }

  def created: Receive = {
    case GetForm(pid, user) =>
      val replyTo = sender
      getForm(user).foreach {
        case dgd: DoGetForm => replyTo ! state.form
        case other          => replyTo ! other
      }
    case ReplaceForm(_, user, raw) =>
      val replyTo = sender
      replaceForm(user, raw).foreach {
        case drd: DoReplaceForm => self ! drd.copy(request = Some(replyTo))
        case other              => replyTo ! other
      }
    case DoReplaceForm(user, raw, Some(replyTo: ActorRef)) =>
      persist(FormReplaced(id, user, lastSequenceNr + 1, System.currentTimeMillis, raw)) { evt =>
        replyTo ! updateAndSave(evt)
      }
    case PatchForm(_, user, patch) =>
      val replyTo = sender
      patchForm(user, patch).foreach {
        case dpd: DoPatchForm => self ! dpd.copy(request = Some(replyTo))
        case other            => replyTo ! other
      }
    case DoPatchForm(user, patch, raw, Some(replyTo: ActorRef)) =>
      persist(FormPatched(id, user, lastSequenceNr + 1, System.currentTimeMillis, patch, raw)) { evt =>
        replyTo ! updateAndSave(evt)
      }
    case RemoveForm(_, user) =>
      val replyTo = sender
      removeForm(user).foreach {
        case ddd: DoRemoveForm => self ! ddd.copy(request = Some(replyTo))
        case other             => replyTo ! other
      }
    case DoRemoveForm(user, raw, Some(replyTo: ActorRef)) =>
      persist(FormRemoved(id, user, lastSequenceNr + 1, System.currentTimeMillis(), raw)) { evt =>
        state = state.updated(evt)
        deleteMessages(lastSequenceNr)
        deleteSnapshots(SnapshotSelectionCriteria.Latest)
        val form = state.form.toJson.asJsObject
        saveSnapshot(form)
        context.become(removed)
        replyTo ! evt.copy(raw = form)
        context.parent ! Passivate(stopMessage = PoisonPill)
      }
    case GetACL(_, user) =>
      val replyTo = sender
      getACL(user).foreach {
        case dga: DoGetACL => replyTo ! state.form.raw.fields("_metadata").asJsObject.fields("acl")
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
      patchACL(user, state.form.raw.fields("_metadata").asJsObject.fields("acl"), patch).foreach {
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
    case _: CreateForm => sender ! FormAlreadyExists
    case _: DomainRemoved | _: CollectionActor.CollectionRemoved => context.parent ! Passivate(stopMessage = PoisonPill)
  }

  def removed: Receive = {
    case GetForm(_, user) =>
      val replyTo = sender
      val parent = context.parent
      getForm(user).foreach {
        case dgd: DoGetForm =>
          replyTo ! state.form
          parent ! Passivate(stopMessage = PoisonPill)
        case other =>
          replyTo ! other
          parent ! Passivate(stopMessage = PoisonPill)
      }
    case _: Command =>
      sender ! FormSoftRemoved
      context.parent ! Passivate(stopMessage = PoisonPill)
  }

  override def unhandled(msg: Any): Unit = msg match {
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
    case _              => super.unhandled(msg)
  }

  private def updateAndSave(evt: Event) = {
    state = state.updated(evt)
    deleteSnapshots(SnapshotSelectionCriteria.Latest)
    saveSnapshot(state.form.toJson.asJsObject)
    state.form
  }

  private def createForm(user: String, raw: JsObject, initFlag: Option[Boolean]) = {
    val dcd = DoCreateForm(user, JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> eventACL(user),"type" -> JsString("form")))))
    initFlag match {
      case Some(true) => Future.successful(dcd)
      case other => (domainRegion ? CheckPermission(DomainActor.persistenceId(rootDomain, domain), user, CreateForm)).map {
        case Granted => dcd
        case Denied  => Denied
      }
    }
  }

  private def getForm(user: String) = checkPermission(user, GetForm).map {
    case Granted => DoGetForm(user)
    case other   => other
  }.recover { case e => e }

  private def replaceForm(user: String, raw: JsObject) = checkPermission(user, ReplaceForm).map {
    case Granted => DoReplaceForm(user, JsObject(raw.fields + ("_metadata" -> JsObject("acl" -> eventACL(user)))))
    case other   => other
  }.recover { case e => e }

  private def patchForm(user: String, patch: JsonPatch) = checkPermission(user, PatchForm).map {
    case Granted =>
      Try {
        patch(state.form.raw)
      } match {
        case Success(_) => DoPatchForm(user, patch, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
        case Failure(e) => PatchFormException(e)
      }
    case other => other
  }.recover { case e => e }

  private def removeForm(user: String) = checkPermission(user, RemoveForm).map {
    case Granted => DoRemoveForm(user, JsObject("_metadata" -> JsObject("acl" -> eventACL(user))))
    case other   => other
  }.recover { case e => e }

  val commandPermissionMap = Map[Any, String](
    GetForm -> "get",
    ReplaceForm -> "replace",
    PatchForm -> "patch",
    RemoveForm -> "remove",
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
            val aclObj = state.form.raw.fields("_metadata").asJsObject.fields("acl").asJsObject
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
