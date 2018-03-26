package com.zxtx.actors

import akka.persistence.journal.EventAdapter
import akka.persistence.journal.EventSeq
import spray.json.JsArray
import spray.json.JsObject
import spray.json.enrichAny

class DocumentEventAdapter extends EventAdapter {
  import DocumentActor._
  import DocumentActor.JsonProtocol._
  import DocumentSetActor._
  import DocumentSetActor.JsonProtocol._
  import DomainActor._
  import DomainActor.JsonProtocol._

  final val MANIFEST_DOCUMENT_CREATED = classOf[DocumentCreated].getName
  final val MANIFEST_DOCUMENT_REPLACED = classOf[DocumentReplaced].getName
  final val MANIFEST_DOCUMENT_PATCHED = classOf[DocumentPatched].getName
  final val MANIFEST_DOCUMENT_DELETED = classOf[DocumentDeleted].getName
  final val MANIFEST_DOCUMENT_SET_CREATED = classOf[DocumentSetCreated].getName
  final val MANIFEST_DOCUMENT_SET_REPLACED = classOf[DocumentSetReplaced].getName
  final val MANIFEST_DOCUMENT_SET_PATCHED = classOf[DocumentSetPatched].getName
  final val MANIFEST_DOCUMENT_SET_DELETED = classOf[DocumentSetDeleted].getName
  final val MANIFEST_DOMAIN_CREATED = classOf[DomainCreated].getName
  final val MANIFEST_DOMAIN_REPLACED = classOf[DomainReplaced].getName
  final val MANIFEST_DOMAIN_PATCHED = classOf[DomainPatched].getName
  final val MANIFEST_DOMAIN_AUTHORIZED = classOf[DomainAuthorized].getName
  final val MANIFEST_DOMAIN_DELETED = classOf[DomainDeleted].getName

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
    case docc: DocumentCreated    => docc.toJson
    case docr: DocumentReplaced   => docr.toJson
    case docp: DocumentPatched    => docp.toJson
    case docd: DocumentDeleted    => docd.toJson
    case dsc: DocumentSetCreated  => dsc.toJson
    case dsr: DocumentSetReplaced => dsr.toJson
    case dsp: DocumentSetPatched  => dsp.toJson
    case dsd: DocumentSetDeleted  => dsd.toJson
    case dc: DomainCreated        => dc.toJson
    case dr: DomainReplaced       => dr.toJson
    case dp: DomainPatched        => dp.toJson
    case da: DomainAuthorized     => da.toJson
    case dd: DomainDeleted        => dd.toJson
    case _                        => event
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
    case MANIFEST_DOCUMENT_CREATED      => convertTo(event)(_.convertTo[DocumentCreated])
    case MANIFEST_DOCUMENT_REPLACED     => convertTo(event)(_.convertTo[DocumentReplaced])
    case MANIFEST_DOCUMENT_PATCHED      => convertTo(event)(_.convertTo[DocumentPatched])
    case MANIFEST_DOCUMENT_DELETED      => convertTo(event)(_.convertTo[DocumentDeleted])
    case MANIFEST_DOCUMENT_SET_CREATED  => convertTo(event)(_.convertTo[DocumentSetCreated])
    case MANIFEST_DOCUMENT_SET_REPLACED => convertTo(event)(_.convertTo[DocumentSetReplaced])
    case MANIFEST_DOCUMENT_SET_PATCHED  => convertTo(event)(_.convertTo[DocumentSetPatched])
    case MANIFEST_DOCUMENT_SET_DELETED  => convertTo(event)(_.convertTo[DocumentSetDeleted])
    case MANIFEST_DOMAIN_CREATED        => convertTo(event)(_.convertTo[DomainCreated])
    case MANIFEST_DOMAIN_REPLACED       => convertTo(event)(_.convertTo[DomainReplaced])
    case MANIFEST_DOMAIN_PATCHED        => convertTo(event)(_.convertTo[DomainPatched])
    case MANIFEST_DOMAIN_AUTHORIZED     => convertTo(event)(_.convertTo[DomainAuthorized])
    case MANIFEST_DOMAIN_DELETED        => convertTo(event)(_.convertTo[DomainDeleted])
    case _                              => throw new IllegalArgumentException(s"Unable to handle manifest $manifest!")
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
