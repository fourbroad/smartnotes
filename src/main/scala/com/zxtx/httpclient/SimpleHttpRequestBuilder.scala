package com.zxtx.httpclient

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import scala.util.control.NonFatal

import akka.actor.ActorSystem
import akka.http.scaladsl.HttpExt
import akka.http.scaladsl.model.ContentType
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.FormData
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.MediaRange
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes.ServerError
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.Materializer
import akka.util.ByteString

case class SimpleHttpResponse(
    status: StatusCode,
    body: ByteString,
    headers: Seq[HttpHeader],
    contentType: ContentType,
    charset: Charset) {
  def bodyAsString: String = body.decodeString(charset)
}

case class UnexpectedResponse(response: SimpleHttpResponse) extends RuntimeException(response.status.toString())

case class SimpleHttpRequestBuilder(request: HttpRequest) {
  def headers(kvs: (String, String)*): SimpleHttpRequestBuilder = {
    SimpleHttpRequestBuilder(request.mapHeaders(_ ++ kvs.map((RawHeader.apply _).tupled)))
  }

  def params(kvs: (String, String)*): SimpleHttpRequestBuilder = {
    val query = kvs.foldLeft(request.uri.query())((query, curr) => curr +: query)
    SimpleHttpRequestBuilder(request.withUri(request.uri.withQuery(query)))
  }

  def accept(mediaRanges: MediaRange*): SimpleHttpRequestBuilder =
    SimpleHttpRequestBuilder(request.addHeader(Accept(mediaRanges: _*)))

  def acceptJson: SimpleHttpRequestBuilder =
    SimpleHttpRequestBuilder(request.addHeader(Accept(MediaRange(MediaTypes.`application/json`))))

  def acceptXml: SimpleHttpRequestBuilder =
    SimpleHttpRequestBuilder(request.addHeader(Accept(MediaRange(MediaTypes.`application/xml`))))

  def bodyAsJson(body: String): SimpleHttpRequestBuilder =
    SimpleHttpRequestBuilder(request.withEntity(HttpEntity(ContentTypes.`application/json`, body)))

  def bodyAsXml(body: String): SimpleHttpRequestBuilder =
    SimpleHttpRequestBuilder(request.withEntity(HttpEntity(ContentTypes.`text/xml(UTF-8)`, body)))

  def bodyAsText(body: String): SimpleHttpRequestBuilder =
    SimpleHttpRequestBuilder(request.withEntity(HttpEntity(ContentTypes.`application/json`, body)))

  def bodyAsBinary(body: Array[Byte]): SimpleHttpRequestBuilder =
    SimpleHttpRequestBuilder(request.withEntity(HttpEntity(body)))

  def bodyAsBinary(body: ByteString): SimpleHttpRequestBuilder =
    SimpleHttpRequestBuilder(request.withEntity(HttpEntity(body)))

  def bodyFromFile(contentType: ContentType, file: Path, chunkSize: Int = -1): SimpleHttpRequestBuilder =
    SimpleHttpRequestBuilder(request.withEntity(HttpEntity.fromPath(contentType, file, chunkSize)))

  def bodyAsForm(fields: Map[String, String]): SimpleHttpRequestBuilder =
    SimpleHttpRequestBuilder(request.withEntity(FormData(fields).toEntity))

  def bodyAsForm(fields: (String, String)*): SimpleHttpRequestBuilder =
    SimpleHttpRequestBuilder(request.withEntity(FormData(fields: _*).toEntity))

  def run()(implicit mat: Materializer, http: HttpExt, ec: ExecutionContext): Future[SimpleHttpResponse] = {
    for {
      response <- http.singleRequest(request)
      contentType = response.entity.contentType
      charset = contentType.charsetOption.map(_.nioCharset()).getOrElse(StandardCharsets.UTF_8)
      body <- response.entity.dataBytes.runFold(ByteString(""))(_ ++ _)
    } yield SimpleHttpResponse(response.status, body, response.headers, contentType, charset)
  }

  def runTry(failWhen: SimpleHttpResponse => Boolean = _.status.isFailure())(implicit mat: Materializer, http: HttpExt, ec: ExecutionContext): Future[Try[SimpleHttpResponse]] = {
    run().map {
      case response if failWhen(response) => util.Failure(UnexpectedResponse(response))
      case response                       => util.Success(response)
    }.recover {
      case NonFatal(cause) => util.Failure(cause)
    }
  }

  def retryDirectly(
    max: Int = 5,
    failWhen: SimpleHttpResponse => Boolean = _.status.isInstanceOf[ServerError])(implicit
    system: ActorSystem,
    mat: Materializer,
    http: HttpExt,
    ec: ExecutionContext): Future[Try[SimpleHttpResponse]] = {
    Directly(max) { () => runTry(failWhen) }
  }

  def retryPause(max: Int = 5, delay: FiniteDuration = 500.milliseconds,
    failWhen: SimpleHttpResponse => Boolean = _.status.isInstanceOf[ServerError])(implicit
    system: ActorSystem,
    mat: Materializer,
    http: HttpExt,
    ec: ExecutionContext): Future[Try[SimpleHttpResponse]] = {
    Pause(max, delay) { () => runTry(failWhen) }
  }

  def retryBackoff(max: Int = 5, delay: FiniteDuration = 500.milliseconds, base: Int = 2,
    failWhen: SimpleHttpResponse => Boolean = _.status.isInstanceOf[ServerError])(implicit
    system: ActorSystem,
    mat: Materializer,
    http: HttpExt,
    ec: ExecutionContext): Future[Try[SimpleHttpResponse]] = {
    Backoff(max, delay, base) { () => runTry(failWhen) }
  }

}

object SimpleHttpRequestBuilder {

  def get(uri: String): SimpleHttpRequestBuilder =
    SimpleHttpRequestBuilder(HttpRequest(uri = uri))

  def head(uri: String): SimpleHttpRequestBuilder =
    SimpleHttpRequestBuilder(HttpRequest(method = HttpMethods.HEAD, uri = uri))

  def post(uri: String): SimpleHttpRequestBuilder =
    SimpleHttpRequestBuilder(HttpRequest(method = HttpMethods.POST, uri = uri))

  def put(uri: String): SimpleHttpRequestBuilder =
    SimpleHttpRequestBuilder(HttpRequest(method = HttpMethods.PUT, uri = uri))

  def patch(uri: String): SimpleHttpRequestBuilder =
    SimpleHttpRequestBuilder(HttpRequest(method = HttpMethods.PATCH, uri = uri))

  def delete(uri: String): SimpleHttpRequestBuilder =
    SimpleHttpRequestBuilder(HttpRequest(method = HttpMethods.DELETE, uri = uri))

}
