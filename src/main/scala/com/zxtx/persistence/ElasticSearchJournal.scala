package com.zxtx.persistence

import com.typesafe.config.Config

import scala.collection._
import scala.concurrent._
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import akka.actor._
import akka.persistence.PersistentRepr
import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.journal.AsyncRecovery
import akka.persistence.AtomicWrite
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsNumber
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.ContentTypes
import akka.util.ByteString
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.model.MediaRange
import spray.json._

class ElasticSearchJournal extends AsyncWriteJournal with AsyncRecovery with ActorLogging {

  import ElasticSearchJournal._
  import ElasticSearchStore._

  def prepareConfig: Config = context.system.settings.config.getConfig("akka.persistence.elasticsearch.journal")

  implicit val executionContext = context.system.dispatcher
  val store = ElasticSearchStore(context.system)

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] = {
    val batch = messages.flatMap { m =>
      m.payload.size match {
        case count if count > 1 =>
          log.debug("AtomicWrite not supported for persistAll")
          throw new UnsupportedOperationException("AtomicWrite not supported for persistAll")
        case _ =>
          m.payload.map { pr =>
            val segments = pr.persistenceId.split("%7E")
            val domain = segments(0)
            val collection = segments(1)
            val id = segments(2)
            val alias = s"${domain}~${collection}~hot~events"
            val op = s"""{"index":{"_index":"${alias}","_type":"event","_id":"${id}~${pr.sequenceNr}"}}"""
            val obj = pr.payload.asInstanceOf[JsObject]
            val metaFields = obj.getFields("_metadata") match {
              case Seq(metadata: JsObject) => metadata.fields
              case _                       => Map[String, JsValue]()
            }
            val metaMap = metaFields + ("revision" -> JsNumber(pr.sequenceNr)) + ("manifest" -> JsString(pr.manifest)) + ("writerUuid" -> JsString(pr.writerUuid))
            val metaObj = JsObject((if (pr.deleted) metaMap + ("removed" -> JsBoolean(true)) else metaMap).toList)
            op + "\n" + JsObject(obj.fields + ("id" -> JsString(id)) + ("_metadata" -> metaObj)).compactPrint
          }
      }
    }.mkString("\n") + "\n"

    val uri = "http://localhost:9200/_bulk?refresh"
    store.post(uri = uri, entity = batch) map {
      case (StatusCodes.OK, jv) =>
        jv.asJsObject.fields("items").asInstanceOf[JsArray].elements.map { jv =>
          jv.asJsObject.fields("index").asJsObject.fields("status").asInstanceOf[JsNumber] match {
            case JsNumber(num) if (num == StatusCodes.OK.intValue || num == StatusCodes.Created.intValue) => Success()
            case JsNumber(num) => Failure(new RuntimeException(num.toString))
          }
        }
      case (code, jv) => throw new RuntimeException(s"Error persisting write $jv")
    }
  }

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = {
    val segments = persistenceId.split("%7E")
    val id = segments(2)
    val alias = s"${segments(0)}~${segments(1)}~all~events"
    val delByQeury = s"""{
      "query":{
        "bool":{
          "must":[
            {"term":{"id.keyword":"${id}"}}
          ],
          "filter":{
            "range":{"_metadata.revision":{"lte":${toSequenceNr}}}
          }
        }
      },
      "script":{
        "source": "ctx._source._metadata.removed = true"
      }
    }"""

    val uri = s"http://localhost:9200/${alias}/_update_by_query?refresh"
    store.post(uri = uri, entity = delByQeury).map {
      case (StatusCodes.OK, _) =>
      case (code, _)           => throw new RuntimeException(s"Error delete messages:$code")
    }
  }

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    val segments = persistenceId.split("%7E")
    if (segments.size == 3) {
      val id = segments(2)
      val alias = s"${segments(0)}~${segments(1)}~all~events"
      val search = s"""{
      "query":{
        "bool":{
          "must":[
            {"term":{"id.keyword":"${id}"}}
          ],
          "filter":{
            "range":{"_metadata.revision":{"gte":${fromSequenceNr}}}
          }
        }
      },
      "aggs":{
          "maxSequenceNr":{
              "max":{"field":"_metadata.revision"}
          }
      }
    }"""

      val uri = s"http://localhost:9200/${alias}/_search?size=0"
      store.get(uri = uri, entity = search).map {
        case (StatusCodes.OK, jv) =>
          jv.asJsObject.fields("aggregations").asJsObject
            .fields("maxSequenceNr").asJsObject.fields("value") match {
              case JsNumber(value) => value.toLong
              case _               => 0.toLong
            }
        case (StatusCodes.NotFound, _) => 0.toLong
        case (code, _)                 => throw new RuntimeException(s"Read highest sequenceNr error: ${code}")
      }
    } else {
      Future(0.toLong)
    }
  }

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(replayCallback: (PersistentRepr) => Unit): Future[Unit] = {
    val end = toSequenceNr - fromSequenceNr match {
      case num if num < max => toSequenceNr
      case _                => fromSequenceNr + max - 1
    }

    val segments = persistenceId.split("%7E")
    val id = segments(2)
    val alias = s"${segments(0)}~${segments(1)}~all~events"
    val search = s"""{
      "query":{
        "bool":{
          "must":[
            {"term":{"id.keyword":"${id}"}}
          ],
          "must_not":[
            {"term":{"_metadata.removed":true}}
          ],
          "filter":{
            "range":{"_metadata.revision":{"gte":"${fromSequenceNr}","lte":"${end}"}}
          }
        }
      },
      "sort":[{
        "_metadata.revision":{"order":"asc"}
      }]
    }"""

    val uri = s"http://localhost:9200/${alias}/_search"
    store.get(uri = uri, entity = search).map {
      case (StatusCodes.OK, jv) =>
        jv.asJsObject.fields("hits").asJsObject.fields("hits")
          .asInstanceOf[JsArray].elements.map { jv =>
            val jo = jv.asJsObject.fields("_source").asJsObject
            val meta = jo.fields("_metadata").asJsObject()
            meta.getFields("revision", "manifest", "writerUuid") match {
              case Seq(JsNumber(revision), JsString(manifest), JsString(writerUuid)) =>
                PersistentRepr(jo, revision.toLong, persistenceId, manifest, false, null, writerUuid)
            }
          }.foreach(replayCallback)
      case (code, _) => throw new RuntimeException(s"Error replay messages: $code!")
    }
  }

  override def receivePluginInternal: Receive = {
    case r @ ReplayTaggedMessages(fromSequenceNr, toSequenceNr, max, tag, replyTo) ⇒
      import context.dispatcher
      val readHighestSequenceNrFrom = math.max(0L, fromSequenceNr - 1)
    //      asyncReadHighestSequenceNr(tagAsPersistenceId(tag), readHighestSequenceNrFrom)
    //        .flatMap { highSeqNr ⇒
    //          val toSeqNr = math.min(toSequenceNr, highSeqNr)
    //          if (highSeqNr == 0L || fromSequenceNr > toSeqNr)
    //            Future.successful(highSeqNr)
    //          else {
    //            asyncReplayTaggedMessages(tag, fromSequenceNr, toSeqNr, max) {
    //              case ReplayedTaggedMessage(p, tag, offset) ⇒
    //                adaptFromJournal(p).foreach { adaptedPersistentRepr ⇒
    //                  replyTo.tell(ReplayedTaggedMessage(adaptedPersistentRepr, tag, offset), Actor.noSender)
    //                }
    //            }.map(_ ⇒ highSeqNr)
    //          }
    //        }.map {
    //          highSeqNr ⇒ RecoverySuccess(highSeqNr)
    //        }.recover {
    //          case e ⇒ ReplayMessagesFailure(e)
    //        }.pipeTo(replyTo)

    case SubscribePersistenceId(persistenceId: String) ⇒
      //      addPersistenceIdSubscriber(sender(), persistenceId)
      context.watch(sender())
    case SubscribeAllPersistenceIds ⇒
      //      addAllPersistenceIdsSubscriber(sender())
      context.watch(sender())
    case SubscribeTag(tag: String) ⇒
      //      addTagSubscriber(sender(), tag)
      context.watch(sender())
    case Terminated(ref) ⇒
    //      removeSubscriber(ref)
  }
}

object ElasticSearchJournal {
  sealed trait SubscriptionCommand

  /**
   * Subscribe the `sender` to changes (appended events) for a specific `persistenceId`.
   * Used by query-side. The journal will send [[EventAppended]] messages to
   * the subscriber when `asyncWriteMessages` has been called.
   */
  final case class SubscribePersistenceId(persistenceId: String) extends SubscriptionCommand
  final case class EventAppended(persistenceId: String) extends DeadLetterSuppression

  /**
   * Subscribe the `sender` to current and new persistenceIds.
   * Used by query-side. The journal will send one [[CurrentPersistenceIds]] to the
   * subscriber followed by [[PersistenceIdAdded]] messages when new persistenceIds
   * are created.
   */
  final case object SubscribeAllPersistenceIds extends SubscriptionCommand
  final case class CurrentPersistenceIds(allPersistenceIds: Set[String]) extends DeadLetterSuppression
  final case class PersistenceIdAdded(persistenceId: String) extends DeadLetterSuppression

  /**
   * Subscribe the `sender` to changes (appended events) for a specific `tag`.
   * Used by query-side. The journal will send [[TaggedEventAppended]] messages to
   * the subscriber when `asyncWriteMessages` has been called.
   * Events are tagged by wrapping in [[akka.persistence.journal.Tagged]]
   * via an [[akka.persistence.journal.EventAdapter]].
   */
  final case class SubscribeTag(tag: String) extends SubscriptionCommand
  final case class TaggedEventAppended(tag: String) extends DeadLetterSuppression

  /**
   * `fromSequenceNr` is exclusive
   * `toSequenceNr` is inclusive
   */
  final case class ReplayTaggedMessages(fromSequenceNr: Long, toSequenceNr: Long, max: Long, tag: String, replyTo: ActorRef) extends SubscriptionCommand
  final case class ReplayedTaggedMessage(persistent: PersistentRepr, tag: String, offset: Long) extends DeadLetterSuppression with NoSerializationVerificationNeeded
}