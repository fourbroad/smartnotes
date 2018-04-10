package com.zxtx.httpclient

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

object APIClient {
  private val host = "http://localhost:8080"

  def apply(system: ActorSystem) = new APIClient(system)
  implicit def entityTranform(body: String): RequestEntity = HttpEntity(ContentTypes.`application/json`, body)
  implicit val logSource: LogSource[AnyRef] = new LogSource[AnyRef] {
    def genString(o: AnyRef): String = o.getClass.getName
    override def getClazz(o: AnyRef): Class[_] = o.getClass
  }
}

class APIClient(system: ActorSystem) {
  import APIClient._

  implicit val sys = system
  implicit val executionContext = system.dispatcher
  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(sys))

  val http = Http(system)
  val log = Logging(system, this)

  def get(uri: Uri, headers: immutable.Seq[HttpHeader] = immutable.Seq.empty[HttpHeader]): Future[(StatusCode, immutable.Seq[HttpHeader], JsValue)] =
    request(HttpMethods.GET, uri = uri, headers = headers)
  def post(uri: Uri, headers: immutable.Seq[HttpHeader] = immutable.Seq.empty[HttpHeader], entity: RequestEntity = HttpEntity.empty(ContentTypes.`application/json`)): Future[(StatusCode, immutable.Seq[HttpHeader], JsValue)] =
    request(HttpMethods.POST, uri = uri, headers = headers, entity = entity)
  def put(uri: Uri, headers: immutable.Seq[HttpHeader] = immutable.Seq.empty[HttpHeader], entity: RequestEntity = HttpEntity.empty(ContentTypes.`application/json`)): Future[(StatusCode, immutable.Seq[HttpHeader], JsValue)] =
    request(HttpMethods.PUT, uri = uri, headers = headers, entity = entity)
  def patch(uri: Uri, headers: immutable.Seq[HttpHeader] = immutable.Seq.empty[HttpHeader], entity: RequestEntity): Future[(StatusCode, immutable.Seq[HttpHeader], JsValue)] =
    request(HttpMethods.PATCH, uri = uri, headers = headers, entity = entity)
  def delete(uri: Uri, headers: immutable.Seq[HttpHeader] = immutable.Seq.empty[HttpHeader]): Future[(StatusCode, immutable.Seq[HttpHeader], JsValue)] =
    request(HttpMethods.DELETE, uri = uri, headers = headers)

  def request(method: HttpMethod = HttpMethods.GET, uri: Uri, headers: immutable.Seq[HttpHeader] = immutable.Seq.empty[HttpHeader], entity: RequestEntity = HttpEntity.empty(ContentTypes.`application/json`)): Future[(StatusCode, immutable.Seq[HttpHeader], JsValue)] = {
    val request = HttpRequest(method = method, uri = uri, headers = headers, entity = entity);
    request.addHeader(Accept(MediaRange(MediaTypes.`application/json`)))
    http.singleRequest(request).flatMap {
      case HttpResponse(code, headers, respEntity, _) =>
        respEntity.dataBytes.runFold(ByteString.empty)(_ ++ _).map { body => (code, headers, body.utf8String.parseJson) }
    }
  }

}
