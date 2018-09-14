package com.zxtx.actors

import ACL.ACLPatched
import ACL.ACLReplaced
import ACL.EventPermissionSubjectRemoved
import ACL.JsonProtocol.ACLPatchedFormat
import ACL.JsonProtocol.ACLReplacedFormat
import ACL.JsonProtocol.EventPermissionSubjectRemovedFormat
import ACL.JsonProtocol.PermissionSubjectRemovedFormat
import ACL.PermissionSubjectRemoved
import CollectionActor.CollectionCreated
import CollectionActor.CollectionPatched
import CollectionActor.CollectionRemoved
import CollectionActor.CollectionReplaced
import CollectionActor.JsonProtocol.CollectionCreatedFormat
import CollectionActor.JsonProtocol.CollectionPatchedFormat
import CollectionActor.JsonProtocol.CollectionRemovedFormat
import CollectionActor.JsonProtocol.CollectionReplacedFormat
import ViewActor.ViewCreated
import ViewActor.ViewPatched
import ViewActor.ViewRemoved
import ViewActor.ViewReplaced
import ViewActor.JsonProtocol.ViewCreatedFormat
import ViewActor.JsonProtocol.ViewPatchedFormat
import ViewActor.JsonProtocol.ViewRemovedFormat
import ViewActor.JsonProtocol.ViewReplacedFormat
import DocumentActor.DocumentCreated
import DocumentActor.DocumentPatched
import DocumentActor.DocumentRemoved
import DocumentActor.DocumentReplaced
import DocumentActor.JsonProtocol.DocumentCreatedFormat
import DocumentActor.JsonProtocol.DocumentPatchedFormat
import DocumentActor.JsonProtocol.DocumentRemovedFormat
import DocumentActor.JsonProtocol.DocumentReplacedFormat
import DomainActor.DomainCreated
import DomainActor.DomainJoined
import DomainActor.DomainPatched
import DomainActor.DomainQuited
import DomainActor.DomainRemoved
import DomainActor.DomainReplaced
import DomainActor.JsonProtocol.DomainCreatedFormat
import DomainActor.JsonProtocol.DomainJoinedFormat
import DomainActor.JsonProtocol.DomainPatchedFormat
import DomainActor.JsonProtocol.DomainQuitedFormat
import DomainActor.JsonProtocol.DomainRemovedFormat
import DomainActor.JsonProtocol.DomainReplacedFormat
import UserActor.JsonProtocol.PasswordResetedFormat
import UserActor.JsonProtocol.UserCreatedFormat
import UserActor.JsonProtocol.UserPatchedFormat
import UserActor.JsonProtocol.UserRemovedFormat
import UserActor.JsonProtocol.UserReplacedFormat
import UserActor.PasswordReset
import UserActor.UserCreated
import UserActor.UserPatched
import UserActor.UserRemoved
import UserActor.UserReplaced
import akka.persistence.journal.EventAdapter
import akka.persistence.journal.EventSeq
import spray.json.JsArray
import spray.json.JsObject
import spray.json.enrichAny

class DocumentEventAdapter extends EventAdapter {

  final val MANIFEST_DOCUMENT_CREATED = classOf[DocumentCreated].getName
  final val MANIFEST_DOCUMENT_REPLACED = classOf[DocumentReplaced].getName
  final val MANIFEST_DOCUMENT_PATCHED = classOf[DocumentPatched].getName
  final val MANIFEST_DOCUMENT_REMOVED = classOf[DocumentRemoved].getName

  final val MANIFEST_COLLECTION_CREATED = classOf[CollectionCreated].getName
  final val MANIFEST_COLLECTION_REPLACED = classOf[CollectionReplaced].getName
  final val MANIFEST_COLLECTION_PATCHED = classOf[CollectionPatched].getName
  final val MANIFEST_COLLECTION_REMOVED = classOf[CollectionRemoved].getName

  final val MANIFEST_VIEW_CREATED = classOf[ViewCreated].getName
  final val MANIFEST_VIEW_REPLACED = classOf[ViewReplaced].getName
  final val MANIFEST_VIEW_PATCHED = classOf[ViewPatched].getName
  final val MANIFEST_VIEW_REMOVED = classOf[ViewRemoved].getName
  
  final val MANIFEST_DOMAIN_CREATED = classOf[DomainCreated].getName
  final val MANIFEST_DOMAIN_REPLACED = classOf[DomainReplaced].getName
  final val MANIFEST_DOMAIN_PATCHED = classOf[DomainPatched].getName
  final val MANIFEST_DOMAIN_REMOVED = classOf[DomainRemoved].getName
  final val MANIFEST_DOMAIN_JOINED = classOf[DomainJoined].getName
  final val MANIFEST_DOMAIN_QUITED = classOf[DomainQuited].getName

  final val MANIFEST_USER_CREATED = classOf[UserCreated].getName
  final val MANIFEST_USER_REPLACED = classOf[UserReplaced].getName
  final val MANIFEST_USER_PATCHED = classOf[UserPatched].getName
  final val MANIFEST_USER_REMOVED = classOf[UserRemoved].getName
  final val MANIFEST_PASSWORD_RESET = classOf[PasswordReset].getName

  final val MANIFEST_ACL_REPLACED = classOf[ACLReplaced].getName
  final val MANIFEST_ACL_PATCHED = classOf[ACLPatched].getName
  final val MANIFEST_ACL_PERMISSION_SUBJECT_REMOVED = classOf[PermissionSubjectRemoved].getName
  final val MANIFEST_ACL_EVENT_PERMISSION_SUBJECT_REMOVED = classOf[EventPermissionSubjectRemoved].getName

  /**
   * Return the manifest (type hint) that will be provided in the `fromJournal` method.
   * Use `""` if manifest is not needed.
   */
  def manifest(event: Any): String = event.getClass().getName

  /**
   * Convert domain event to journal event type.
   *
   * Some journal may require a specific type to be returned to them,
   * for example if a primary key has to be associated with each event then a journal
   * may require adapters to return `com.example.myjournal.EventWithPrimaryKey(event, key)`.
   *
   * The `toJournal` adaptation must be an 1-to-1 transformation.
   * It is not allowed to drop incoming events during the `toJournal` adaptation.
   *
   * @param event the application-side domain event to be adapted to the journal model
   * @return the adapted event object, possibly the same object if no adaptation was performed
   */
  def toJournal(event: Any): Any = event match {
    case docc: DocumentCreated               => docc.toJson
    case docr: DocumentReplaced              => docr.toJson
    case docp: DocumentPatched               => docp.toJson
    case docd: DocumentRemoved               => docd.toJson
    case cc: CollectionCreated               => cc.toJson
    case cr: CollectionReplaced              => cr.toJson
    case cp: CollectionPatched               => cp.toJson
    case cr: CollectionRemoved               => cr.toJson
    case vc: ViewCreated                     => vc.toJson
    case vr: ViewReplaced                    => vr.toJson
    case vp: ViewPatched                     => vp.toJson
    case vr: ViewRemoved                     => vr.toJson
    case dc: DomainCreated                   => dc.toJson
    case dr: DomainReplaced                  => dr.toJson
    case dp: DomainPatched                   => dp.toJson
    case dd: DomainRemoved                   => dd.toJson
    case dj: DomainJoined                    => dj.toJson
    case dq: DomainQuited                    => dq.toJson
    case uc: UserCreated                     => uc.toJson
    case ur: UserReplaced                    => ur.toJson
    case up: UserPatched                     => up.toJson
    case ud: UserRemoved                     => ud.toJson
    case pr: PasswordReset                   => pr.toJson
    case ar: ACLReplaced                     => ar.toJson
    case as: ACLPatched                      => as.toJson
    case epsr: EventPermissionSubjectRemoved => epsr.toJson
    case psr: PermissionSubjectRemoved       => psr.toJson

    case _                                   => event
  }

  /**
   * Convert a event from its journal model to the applications domain model.
   *
   * One event may be adapter into multiple (or none) events which should be delivered to the [[akka.persistence.PersistentActor]].
   * Use the specialised [[akka.persistence.journal.EventSeq#single]] method to emit exactly one event,
   * or [[akka.persistence.journal.EventSeq#empty]] in case the adapter is not handling this event. Multiple [[EventAdapter]] instances are
   * applied in order as defined in configuration and their emitted event seqs are concatenated and delivered in order
   * to the PersistentActor.
   *
   * @param event event to be adapted before delivering to the PersistentActor
   * @param manifest optionally provided manifest (type hint) in case the Adapter has stored one for this event, `""` if none
   * @return sequence containing the adapted events (possibly zero) which will be delivered to the PersistentActor
   */
  def fromJournal(event: Any, manifest: String): EventSeq = manifest match {
    case MANIFEST_DOCUMENT_CREATED => convertTo(event)(_.convertTo[DocumentCreated])
    case MANIFEST_DOCUMENT_REPLACED => convertTo(event)(_.convertTo[DocumentReplaced])
    case MANIFEST_DOCUMENT_PATCHED => convertTo(event)(_.convertTo[DocumentPatched])
    case MANIFEST_DOCUMENT_REMOVED => convertTo(event)(_.convertTo[DocumentRemoved])
    case MANIFEST_COLLECTION_CREATED => convertTo(event)(_.convertTo[CollectionCreated])
    case MANIFEST_COLLECTION_REPLACED => convertTo(event)(_.convertTo[CollectionReplaced])
    case MANIFEST_COLLECTION_PATCHED => convertTo(event)(_.convertTo[CollectionPatched])
    case MANIFEST_COLLECTION_REMOVED => convertTo(event)(_.convertTo[CollectionRemoved])
    case MANIFEST_VIEW_CREATED => convertTo(event)(_.convertTo[ViewCreated])
    case MANIFEST_VIEW_REPLACED => convertTo(event)(_.convertTo[ViewReplaced])
    case MANIFEST_VIEW_PATCHED => convertTo(event)(_.convertTo[ViewPatched])
    case MANIFEST_VIEW_REMOVED => convertTo(event)(_.convertTo[ViewRemoved])
    case MANIFEST_DOMAIN_CREATED => convertTo(event)(_.convertTo[DomainCreated])
    case MANIFEST_DOMAIN_REPLACED => convertTo(event)(_.convertTo[DomainReplaced])
    case MANIFEST_DOMAIN_PATCHED => convertTo(event)(_.convertTo[DomainPatched])
    case MANIFEST_DOMAIN_REMOVED => convertTo(event)(_.convertTo[DomainRemoved])
    case MANIFEST_DOMAIN_JOINED => convertTo(event)(_.convertTo[DomainJoined])
    case MANIFEST_DOMAIN_QUITED => convertTo(event)(_.convertTo[DomainQuited])
    case MANIFEST_USER_CREATED => convertTo(event)(_.convertTo[UserCreated])
    case MANIFEST_USER_REPLACED => convertTo(event)(_.convertTo[UserReplaced])
    case MANIFEST_USER_PATCHED => convertTo(event)(_.convertTo[UserPatched])
    case MANIFEST_USER_REMOVED => convertTo(event)(_.convertTo[UserRemoved])
    case MANIFEST_PASSWORD_RESET => convertTo(event)(_.convertTo[PasswordReset])
    case MANIFEST_ACL_REPLACED => convertTo(event)(_.convertTo[ACLReplaced])
    case MANIFEST_ACL_PATCHED => convertTo(event)(_.convertTo[ACLPatched])
    case MANIFEST_ACL_PERMISSION_SUBJECT_REMOVED => convertTo(event)(_.convertTo[PermissionSubjectRemoved])
    case MANIFEST_ACL_EVENT_PERMISSION_SUBJECT_REMOVED => convertTo(event)(_.convertTo[EventPermissionSubjectRemoved])

    case _ => throw new IllegalArgumentException(s"Unable to handle manifest $manifest!")
  }

  private def convertTo(event: Any)(converter: (JsObject) => Event): EventSeq = event match {
    case jo: JsObject => EventSeq.single(converter(jo))
    case ja: JsArray =>
      val events = ja.elements.map { e =>
        e match {
          case jo2: JsObject => converter(jo2)
          case _             =>
        }
      }
      EventSeq.create(events)
    case _ => EventSeq.empty
  }

}
