package com.zxtx.persistence

import com.typesafe.config.Config

import akka.NotUsed
import akka.actor._
import akka.persistence._
import akka.persistence.query.scaladsl.{ ReadJournal, _ }
import akka.stream.scaladsl.Source
import spray.json._
import akka.stream.scaladsl.RestartSource
import scala.concurrent._
import scala.concurrent.duration._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.MediaRange
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.MediaTypes
import akka.util.ByteString
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.http.scaladsl.Http

class ElasticSearchReadJournal(system: ExtendedActorSystem, config: Config) extends ReadJournal with IndexMappingQuery {
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