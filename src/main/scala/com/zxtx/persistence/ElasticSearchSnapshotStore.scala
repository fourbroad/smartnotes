package com.zxtx.persistence

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.math

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.MediaRange
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Accept
import akka.persistence.SelectedSnapshot
import akka.persistence.SnapshotMetadata
import akka.persistence.SnapshotSelectionCriteria
import akka.persistence.snapshot.SnapshotStore
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.util.ByteString
import spray.json.JsNumber
import java.util.Calendar

class ElasticSearchSnapshotStore extends SnapshotStore {
  import spray.json._
  import ElasticSearchStore._

  val calendar = Calendar.getInstance
  implicit val executionContext = context.system.dispatcher
  val store = ElasticSearchStore(context.system)

  /**
   * Plugin API: asynchronously loads a snapshot.
   *
   * If the future `Option` is `None` then all events will be replayed,
   * i.e. there was no snapshot. If snapshot could not be loaded the `Future`
   * should be completed with failure. That is important because events may
   * have been deleted and just replaying the events might not result in a valid
   * state.
   *
   * This call is protected with a circuit-breaker.
   *
   * @param persistenceId id of the persistent actor.
   * @param criteria selection criteria for loading.
   */
  def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = {
    val segments = persistenceId.split("%7E")
    val id = segments(2)
    val alias = s"${segments(0)}~${segments(1)}~all~snapshots"
    calendar.setTimeInMillis(criteria.minTimestamp)
    val minTimestamp = calendar.getTime.toLocaleString()
    calendar.setTimeInMillis(math.min(criteria.maxTimestamp, System.currentTimeMillis))
    val maxTimestamp = calendar.getTime.toLocaleString()
    val search = s"""{
      "query":{
        "bool":{
          "must":[
            {"term":{"id.keyword":"${id}"}}
          ],
          "filter":[
            {"range":{"_metadata.revision":{"gte":${criteria.minSequenceNr},"lte":${criteria.maxSequenceNr}}}},
            {"range":{"_metadata.updated":{"gte":"${minTimestamp}","lte":"${maxTimestamp}"}}}
          ]
        }
      },
      "sort":[{
        "_metadata.updated":{"order":"desc"}
      }]
    }"""

    val uri = s"http://localhost:9200/${alias}/_search?size=1"
    store.get(uri = uri, entity = search).map {
      case (StatusCodes.OK, jv) =>
        jv.asJsObject.fields("hits").asJsObject.fields("hits")
          .asInstanceOf[JsArray].elements.map { jv =>
            val jo = jv.asJsObject.fields("_source").asJsObject
            val meta = jo.fields("_metadata").asJsObject
            val Seq(JsNumber(revision), JsNumber(updated)) = meta.getFields("revision", "updated")
            val id = jo.fields("id").asInstanceOf[JsString].value
            val sm = SnapshotMetadata(persistenceId = persistenceId, sequenceNr = revision.toLong, timestamp = updated.toLong)
            SelectedSnapshot(sm, jo)
          }.headOption
      case (StatusCodes.NotFound, _) => None
      case (code, jv)                => throw new RuntimeException(s"Load snapshot error: $jv")
    }
  }

  /**
   * Plugin API: asynchronously saves a snapshot.
   *
   * This call is protected with a circuit-breaker.
   *
   * @param metadata snapshot metadata.
   * @param snapshot snapshot.
   */
  def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = {
    val segments = metadata.persistenceId.split("%7E")
    val id = segments(2)
    val alias = s"${segments(0)}~${segments(1)}~hot~snapshots"
    val uri = s"http://localhost:9200/${alias}/snapshot/${id}~${metadata.sequenceNr}?refresh"
    store.put(uri = uri, entity = snapshot.asInstanceOf[JsObject].compactPrint).map {
      case (StatusCodes.OK | StatusCodes.Created, jv) =>
        jv.asJsObject.fields("result") match {
          case JsString(str) if str == "created" => Success
          case _                                 => Failure(new RuntimeException(jv.compactPrint))
        }
      case (code, jv) => throw new RuntimeException(s"Save snapshot error: $jv")

    }
  }

  /**
   * Plugin API: deletes the snapshot identified by `metadata`.
   *
   * This call is protected with a circuit-breaker.
   *
   * @param metadata snapshot metadata.
   */
  def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = {
    val segments = metadata.persistenceId.split("%7E")
    val id = segments(2)
    val alias = s"${segments(0)}~${segments(1)}~all~snapshots"
    val uri = s"http://localhost:9200/${alias}/snapshot/${id}~${metadata.sequenceNr}?refresh"
    store.delete(uri = uri).map {
      case (StatusCodes.OK, jv) =>
        jv.asJsObject.fields("result") match {
          case JsString(str) if str == "deleted" => Success
          case _                                 => Failure(new RuntimeException(jv.compactPrint))
        }
      case (code, jv) => throw new RuntimeException(s"Error delete snapshot: $jv")
    }
  }

  /**
   * Plugin API: deletes all snapshots matching `criteria`.
   *
   * This call is protected with a circuit-breaker.
   *
   * @param persistenceId id of the persistent actor.
   * @param criteria selection criteria for deleting.
   */
  def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] = {
    val segments = persistenceId.split("%7E")
    val id = segments(2)
    val alias = s"${segments(0)}~${segments(1)}~all~snapshots"
    calendar.setTimeInMillis(criteria.minTimestamp)
    val minTimestamp = calendar.getTime.toLocaleString()
    calendar.setTimeInMillis(math.min(criteria.maxTimestamp, System.currentTimeMillis))
    val maxTimestamp = calendar.getTime.toLocaleString()
    val query = s"""{
      "query":{
        "bool":{
          "must":[
            {"term":{"id.keyword":"${id}"}}
          ],
          "filter":[
            {"range":{"_metadata.revision":{"gte":${criteria.minSequenceNr},"lte":${criteria.maxSequenceNr}}}},
            {"range":{"_metadata.updated":{"gte":"${minTimestamp}","lte":"${maxTimestamp}"}}}
          ]
        }
      }
    }"""

    val uri = s"http://localhost:9200/${alias}/snapshot/_delete_by_query?refresh"
    store.post(uri = uri, entity = query).map {
      case (StatusCodes.OK, jv) =>
        jv.asJsObject.fields("result") match {
          case JsString(str) if str == "deleted" => Success
          case _                                 => Failure(new RuntimeException(jv.compactPrint))
        }
      case (code, _) => throw new RuntimeException(s"Error delete snapshots: $code")
    }
  }

  /**
   * Plugin API
   * Allows plugin implementers to use `f pipeTo self` and
   * handle additional messages for implementing advanced features
   */
  //  def receivePluginInternal: Actor.Receive = Actor.emptyBehavior
}