package com.zxtx.persistence

import scala.collection._
import scala.concurrent._

import akka.actor.ActorSystem
import akka.event.Logging
import akka.event.LogSource
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.HttpMethod
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpProtocol
import akka.http.scaladsl.model.HttpProtocols
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.MediaRange
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.model.RequestEntity
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers.Accept
import akka.util.ByteString
import akka.stream.ActorMaterializerSettings
import akka.stream.ActorMaterializer
import spray.json._
import spray.json.DefaultJsonProtocol._

object ElasticSearchStore {
  private val host = "http://localhost:9200"

  def apply(system: ActorSystem) = new ElasticSearchStore(system)
  implicit def entityTranform(body: String): RequestEntity = HttpEntity(ContentTypes.`application/json`, body)
  implicit val logSource: LogSource[AnyRef] = new LogSource[AnyRef] {
    def genString(o: AnyRef): String = o.getClass.getName
    override def getClazz(o: AnyRef): Class[_] = o.getClass
  }
}

class ElasticSearchStore(system: ActorSystem) {
  import ElasticSearchStore._

  implicit val sys = system
  implicit val executionContext = system.dispatcher
  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(sys))

  val http = Http(system)
  val log = Logging(system, this)

  def newTemplate(name: String, entity: RequestEntity): Future[(StatusCode, JsValue)] = put(uri = s"${host}/_template/${name}", entity = entity)

  def newIndex(name: String): Future[(StatusCode, JsValue)] = put(uri = s"${host}/${name}", entity = HttpEntity.empty(ContentTypes.`application/json`))
  def newIndex(name: String, entity: RequestEntity): Future[(StatusCode, JsValue)] = put(uri = s"${host}/${name}", entity = entity)
  def indices(pattern: String): Future[(StatusCode, JsValue)] = get(s"${host}/${pattern}")
  def deleteIndices(pattern: String): Future[(StatusCode, JsValue)] = delete(s"${host}/${pattern}")

  def search(index: String, entity: RequestEntity): Future[(StatusCode, JsValue)] = get(s"${host}/${index}/_search", entity)

  def deleteByQuery(index: String, parameterSeq: Seq[(String, String)], entity: RequestEntity): Future[(StatusCode, JsValue)] = {
    val params = parameterSeq.map { case (k, v) => s"$k=$v" }.mkString("&")
    post(s"${host}/${index}/_delete_by_query?refresh&${params}", entity)
  }

  def get(uri: Uri): Future[(StatusCode, JsValue)] = request(HttpMethods.GET, uri = uri)
  def get(uri: Uri, entity: RequestEntity): Future[(StatusCode, JsValue)] = request(HttpMethods.GET, uri = uri, entity = entity)
  def post(uri: Uri, entity: RequestEntity): Future[(StatusCode, JsValue)] = request(HttpMethods.POST, uri = uri, entity = entity)
  def put(uri: Uri): Future[(StatusCode, JsValue)] = put(uri = uri, entity = HttpEntity.empty(ContentTypes.`application/json`))
  def put(uri: Uri, entity: RequestEntity): Future[(StatusCode, JsValue)] = request(HttpMethods.PUT, uri = uri, entity = entity)
  def delete(uri: Uri): Future[(StatusCode, JsValue)] = request(HttpMethods.DELETE, uri = uri)

  def request(method: HttpMethod = HttpMethods.GET, uri: Uri, entity: RequestEntity = HttpEntity.empty(ContentTypes.`application/json`)): Future[(StatusCode, JsValue)] = {
    val request = HttpRequest(method = method, uri = uri, entity = entity)
    request.addHeader(Accept(MediaRange(MediaTypes.`application/json`)))
    http.singleRequest(request).flatMap {
      case resp @ HttpResponse(StatusCodes.OK | StatusCodes.Created, headers, respEntity, _) =>
        respEntity.dataBytes.runFold(ByteString.empty)(_ ++ _).map { body => (resp.status, body.utf8String.parseJson) }
      case HttpResponse(code, _, respEntity, _) =>
        respEntity.dataBytes.runFold(ByteString.empty)(_ ++ _).map { body => (code, body.utf8String.parseJson) }
    }
  }

}
