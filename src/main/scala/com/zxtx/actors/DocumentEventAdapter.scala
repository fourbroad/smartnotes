package com.zxtx.actors

import akka.persistence.journal.EventAdapter
import akka.persistence.journal.EventSeq
import spray.json.JsArray
import spray.json.JsObject
import spray.json.enrichAny

import ACL._
import ACL.JsonProtocol._
import DocumentActor._
import DocumentActor.JsonProtocol._
import CollectionActor._
import CollectionActor.JsonProtocol._
import DomainActor._
import DomainActor.JsonProtocol._
import UserActor._
import UserActor.JsonProtocol._

class DocumentEventAdapter extends EventAdapter {

  final val MANIFEST_DOCUMENT_CREATED = classOf[DocumentCreated].getName
  final val MANIFEST_DOCUMENT_REPLACED = classOf[DocumentReplaced].getName
  final val MANIFEST_DOCUMENT_PATCHED = classOf[DocumentPatched].getName
  final val MANIFEST_DOCUMENT_DELETED = classOf[DocumentDeleted].getName

  final val MANIFEST_COLLECTION_CREATED = classOf[CollectionCreated].getName
  final val MANIFEST_COLLECTION_REPLACED = classOf[CollectionReplaced].getName
  final val MANIFEST_COLLECTION_PATCHED = classOf[CollectionPatched].getName
  final val MANIFEST_COLLECTION_DELETED = classOf[CollectionDeleted].getName

  final val MANIFEST_DOMAIN_CREATED = classOf[DomainCreated].getName
  final val MANIFEST_DOMAIN_REPLACED = classOf[DomainReplaced].getName
  final val MANIFEST_DOMAIN_PATCHED = classOf[DomainPatched].getName
  final val MANIFEST_DOMAIN_DELETED = classOf[DomainDeleted].getName
  final val MANIFEST_DOMAIN_JOINED = classOf[DomainJoined].getName
  final val MANIFEST_DOMAIN_QUITED = classOf[DomainQuited].getName

  final val MANIFEST_USER_CREATED = classOf[UserCreated].getName
  final val MANIFEST_USER_REPLACED = classOf[UserReplaced].getName
  final val MANIFEST_USER_PATCHED = classOf[UserPatched].getName
  final val MANIFEST_USER_DELETED = classOf[UserDeleted].getName
  final val MANIFEST_PASSWORD_RESETED = classOf[PasswordReseted].getName

  final val MANIFEST_ACL_SET = classOf[ACLSet].getName

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
    case docc: DocumentCreated   => docc.toJson
    case docr: DocumentReplaced  => docr.toJson
    case docp: DocumentPatched   => docp.toJson
    case docd: DocumentDeleted   => docd.toJson
    case dsc: CollectionCreated  => dsc.toJson
    case dsr: CollectionReplaced => dsr.toJson
    case dsp: CollectionPatched  => dsp.toJson
    case dsd: CollectionDeleted  => dsd.toJson
    case dc: DomainCreated       => dc.toJson
    case dr: DomainReplaced      => dr.toJson
    case dp: DomainPatched       => dp.toJson
    case dd: DomainDeleted       => dd.toJson
    case dj: DomainJoined        => dj.toJson
    case dq: DomainQuited        => dq.toJson
    case uc: UserCreated         => uc.toJson
    case ur: UserReplaced        => ur.toJson
    case up: UserPatched         => up.toJson
    case ud: UserDeleted         => ud.toJson
    case pr: PasswordReseted     => pr.toJson
    case as: ACLSet              => as.toJson
    case _                       => event
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
    case MANIFEST_DOCUMENT_CREATED    => convertTo(event)(_.convertTo[DocumentCreated])
    case MANIFEST_DOCUMENT_REPLACED   => convertTo(event)(_.convertTo[DocumentReplaced])
    case MANIFEST_DOCUMENT_PATCHED    => convertTo(event)(_.convertTo[DocumentPatched])
    case MANIFEST_DOCUMENT_DELETED    => convertTo(event)(_.convertTo[DocumentDeleted])
    case MANIFEST_COLLECTION_CREATED  => convertTo(event)(_.convertTo[CollectionCreated])
    case MANIFEST_COLLECTION_REPLACED => convertTo(event)(_.convertTo[CollectionReplaced])
    case MANIFEST_COLLECTION_PATCHED  => convertTo(event)(_.convertTo[CollectionPatched])
    case MANIFEST_COLLECTION_DELETED  => convertTo(event)(_.convertTo[CollectionDeleted])
    case MANIFEST_DOMAIN_CREATED      => convertTo(event)(_.convertTo[DomainCreated])
    case MANIFEST_DOMAIN_REPLACED     => convertTo(event)(_.convertTo[DomainReplaced])
    case MANIFEST_DOMAIN_PATCHED      => convertTo(event)(_.convertTo[DomainPatched])
    case MANIFEST_DOMAIN_DELETED      => convertTo(event)(_.convertTo[DomainDeleted])
    case MANIFEST_DOMAIN_JOINED       => convertTo(event)(_.convertTo[DomainJoined])
    case MANIFEST_DOMAIN_QUITED       => convertTo(event)(_.convertTo[DomainQuited])
    case MANIFEST_USER_CREATED        => convertTo(event)(_.convertTo[UserCreated])
    case MANIFEST_USER_REPLACED       => convertTo(event)(_.convertTo[UserReplaced])
    case MANIFEST_USER_PATCHED        => convertTo(event)(_.convertTo[UserPatched])
    case MANIFEST_USER_DELETED        => convertTo(event)(_.convertTo[UserDeleted])
    case MANIFEST_PASSWORD_RESETED    => convertTo(event)(_.convertTo[PasswordReseted])
    case MANIFEST_ACL_SET             => convertTo(event)(_.convertTo[ACLSet])

    case _                            => throw new IllegalArgumentException(s"Unable to handle manifest $manifest!")
  }

  private def convertTo(event: Any)(converter: (JsObject) => DocumentEvent): EventSeq = event match {
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
