package com.zxtx.persistence

import com.typesafe.config.Config

import akka.NotUsed
import akka.actor.ExtendedActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.persistence.query.EventEnvelope
import akka.persistence.query.scaladsl.CurrentEventsByPersistenceIdQuery
import akka.persistence.query.scaladsl.ReadJournal
import akka.stream.scaladsl.Source
import spray.json.JsArray
import spray.json.JsNumber
import spray.json.JsString
import spray.json.JsValue
import java.util.UUID
import akka.persistence.query.TimeBasedUUID

class ElasticSearchReadJournal(system: ExtendedActorSystem, config: Config) extends ReadJournal with IndexMappingQuery with CurrentEventsByPersistenceIdQuery {
  import ElasticSearchStore._

  private val writeJournalPluginId: String = config.getString("write-plugin")
  private val maxBufSize: Int = config.getInt("max-buffer-size")

  implicit val executionContext = system.dispatcher
  val store = ElasticSearchStore(system)

  override def indexMapping(persistenceId: String): Source[JsValue, NotUsed] = {
    val segments = persistenceId.split("%7E")
    val index = s"${segments(0)}~${segments(2)}_*"
    val uri = s"http://localhost:9200/${index},${index}~*"
    //    RestartSource.withBackoff(minBackoff = 3.seconds, maxBackoff = 30.seconds, randomFactor = 0.2) { () ⇒
    // Create a source from a future of a source
    Source.fromFuture {
      // Make a single request with akka-http
      store.get(uri = uri).map {
        case (StatusCodes.OK, jv) => jv
        case (code, _)            => throw new RuntimeException(s"Error get event index mapping:$code")
      }
    }
    //    }
  }

  /**
   * Same type of query as [[EventsByPersistenceIdQuery#eventsByPersistenceId]]
   * but the event stream is completed immediately when it reaches the end of
   * the "result set". Events that are stored after the query is completed are
   * not included in the event stream.
   */
  override def currentEventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): Source[EventEnvelope, NotUsed] = {
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
            {"term":{"_metadata.deleted":true}}
          ],
          "filter":{
            "range":{"_metadata.revision":{"gte":"${fromSequenceNr}","lte":"${toSequenceNr}"}}
          }
        }
      },
      "sort":[{
        "_metadata.revision":{"order":"asc"}
      }]
    }"""

    val uri = s"http://localhost:9200/${alias}/_search"
    Source.fromFuture {
      store.get(uri = uri, entity = search).map {
        case (StatusCodes.OK, jv) =>
          jv.asJsObject.fields("hits").asJsObject.fields("hits").asInstanceOf[JsArray].elements.map { jv =>
            val jo = jv.asJsObject.fields("_source").asJsObject
            val meta = jo.fields("_metadata").asJsObject()
            jo.fields.get("_id") match {
              case Some(JsString(id)) =>
                meta.fields.get("revision") match {
                  case Some(JsNumber(revision)) => EventEnvelope(TimeBasedUUID(UUID.fromString(id)), persistenceId, revision.toLong, jo)
                  case None                     => throw new RuntimeException(s"Event has no revision!")
                }
              case None => throw new RuntimeException(s"Event has no id!")
            }
          }
        case (code, _) => throw new RuntimeException(s"Get current events by persistence id error: $code!")
      }
    }.mapConcat(v=>v)
  }

}

object ElasticSearchReadJournal {
  /**
   * The default identifier for [[ElasticSearchReadJournal]] to be used with
   * [[akka.persistence.query.PersistenceQuery#readJournalFor]].
   *
   * The value is `"akka.persistence.query.journal.elasticsearch"` and corresponds
   * to the absolute path to the read journal configuration entry.
   */
  final val Identifier = "akka.persistence.query.journal.elasticsearch"
}