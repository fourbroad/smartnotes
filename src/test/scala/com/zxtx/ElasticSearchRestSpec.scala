package com.zxtx.httpclient

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.stream.ActorMaterializer
import com.zxtx.httpclient.SimpleHttpRequestBuilder._
import org.scalatest.{ AsyncFlatSpec, Matchers }
import spray.json.DefaultJsonProtocol._
import spray.json._

class ElasticSearchRestSpec extends AsyncFlatSpec with Matchers {

  implicit val system = ActorSystem()
  implicit val mat = ActorMaterializer()
  implicit val http = Http()

  case class Document(name: String, description: Option[String] = None)
  case class DocumentResponse(_index: String, _type: String, _id: String, _version: Int = 0, found: Boolean, _source: Document)
  case class Result(result: String)

  implicit val documentFormat = jsonFormat2(Document)
  implicit val documentReponseFormat = jsonFormat6(DocumentResponse)
  implicit val ResultFormat = jsonFormat1(Result)

  "A ElasticSearchRest" should "GET /_cat/health?v" in {
    get("http://localhost:9200/_cat/health?v")
      .run
      .map {
        case SimpleHttpResponse(status, body, _, _, charset) =>
          assert(status == StatusCodes.OK)
      }
  }

  it should "POST document with name 'John Doe' to /customer/doc" in {
    val doc = Document("John Doe")
    post("http://localhost:9200/customer/doc/1")
      .bodyAsJson(doc.toJson.compactPrint)
      .run.map { response =>
        val actual = response.body.utf8String.parseJson.convertTo[Result]
        assert(response.status == StatusCodes.Created)
        assert(actual.result == "created")
      }
  }

  it should "PUT document with name 'John Doe' to /customer/doc/1" in {
    val doc = Document("John Doe")
    put("http://localhost:9200/customer/doc/1")
      .bodyAsJson(doc.toJson.compactPrint)
      .run.map {
        case r @ SimpleHttpResponse(status, _, _, _, _) =>
          val actual = r.bodyAsString.parseJson.convertTo[Result]
          assert(status == StatusCodes.OK)
          assert(actual.result == "updated")
      }
  }

  it should "GET /customer/doc/1" in {
    val expected = DocumentResponse(_index = "customer", _type = "doc", _id = "1", _version = 2, found = true, _source = Document("John Doe"))
    get("http://localhost:9200/customer/doc/1")
      .params("pretty" -> "")
      .acceptJson
      .run.map {
        case SimpleHttpResponse(status, body, _, _, _) =>
          val actual = body.utf8String.parseJson.convertTo[DocumentResponse]
          assert(status == StatusCodes.OK)
          assert(actual == expected)
      }
  }

  it should "Batch index documents within /customer/doc" in {
    post("http://localhost:9200/customer/doc/_bulk")
      .bodyAsText("""
{"index":{"_id":"1"}}
{"name": "John Doe" }
{"index":{"_id":"2"}}
{"name": "Jane Doe" }
""")
      .run.map { response => assert(response.status == StatusCodes.OK) }
  }

  it should "Batch update documents within /customer/doc" in {
    post("http://localhost:9200/customer/doc/_bulk")
      .bodyAsText("""
{"update":{"_id":"1"}}
{"doc": { "name": "John Doe becomes Jane Doe"  } }
{"delete":{"_id":"2"}}
""")
      .run.map { response => assert(response.status == StatusCodes.OK) }
  }

  it should "Verify document which name equals 'John Doe becomes Jane Doe'" in {
    val expected = DocumentResponse(_index = "customer", _type = "doc", _id = "1", found = true, _source = Document("John Doe becomes Jane Doe"))
    get("http://localhost:9200/customer/doc/1")
      .params("pretty" -> "")
      .acceptJson
      .run.map {
        case SimpleHttpResponse(status, body, _, _, _) =>
          val actual = body.utf8String.parseJson.convertTo[DocumentResponse]
          assert(status == StatusCodes.OK)
          assert(actual == expected.copy(_version = actual._version))
      }
  }

  it should "DELETE /customer" in {
    delete("http://localhost:9200/customer")
      .run.map { response =>
        assert(response.status == StatusCodes.OK)
      }
  }

}
