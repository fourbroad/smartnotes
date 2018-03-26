package com.zxtx

import scala.concurrent._
import scala.collection._
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import com.zxtx.httpclient.SimpleHttpRequestBuilder._
import org.scalatest.{ AsyncFlatSpec, Matchers }
import spray.json.DefaultJsonProtocol._
import spray.json._
import com.zxtx.httpclient.SimpleHttpResponse
import com.zxtx.httpclient.APIClient
import com.zxtx.httpclient.APIClient._
import com.typesafe.config.ConfigFactory
import akka.http.javadsl.model.headers.SetCookie
import akka.http.scaladsl.model.headers.HttpCookiePair
import akka.http.scaladsl.model.headers.Cookie

class DomainAPISpec extends AsyncFlatSpec with Matchers {
  val conf = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + 2552).withFallback(ConfigFactory.load("worker"))
  val system = ActorSystem("SmartnotesClient", conf)
  val apiClient = APIClient(system)
  var adminCookies: immutable.Seq[HttpCookiePair] = _
  var cookies: immutable.Seq[HttpCookiePair] = _

  "Smartnotes" should "login in with administrator" in {
    val admin = """{"name":"administrator","password":"!QAZ)OKM"}"""
    apiClient.post(uri = "http://localhost:8080/_login", entity = admin).map {
      case (code, headers, jv) =>
        adminCookies = headers.filter(_.isInstanceOf[SetCookie]).map { v =>
          val cookie = v.asInstanceOf[SetCookie].cookie()
          HttpCookiePair(cookie.name(), cookie.value())
        }
        assert(code == StatusCodes.OK)
    }
  }

  it should "register new user" in {
    val user = """{"name":"fourbroad","password":"z4bb4z"}"""
    apiClient.post("http://localhost:8080/_register", headers = List(Cookie(adminCookies)), entity = user).map {
      case (code, _, jv) =>
        assert(code == StatusCodes.OK || code == StatusCodes.Conflict)
    }
  }
  
  it should "authorize new user" in {
    apiClient.patch(uri = "http://localhost:8080/_authorize", headers = List(Cookie(adminCookies)), 
        entity = """[{"op":"add","path":"/create_domain/users","value":["fourbroad"]}]""").map {
      case (code, _, jv) =>
        assert(code == StatusCodes.OK)
    }
  }

  it should "login in with new user" in {
    val user = """{"name":"fourbroad","password":"z4bb4z"}"""
    apiClient.post(uri = "http://localhost:8080/_login", entity = user).map {
      case (code, headers, jv) =>
        cookies = headers.filter(_.isInstanceOf[SetCookie]).map { v =>
          val cookie = v.asInstanceOf[SetCookie].cookie()
          HttpCookiePair(cookie.name(), cookie.value())
        }
        assert(code == StatusCodes.OK)
    }
  }

  it should "create new domain with null content" in {
    apiClient.post(uri = "http://localhost:8080/.domains/www.hello.com", headers = List(Cookie(cookies))).map {
      case (code, _, jv) =>
        assert(code == StatusCodes.Created)
    }
  }

  it should "login in www.hello.com" in {
    val user = """{"name":"fourbroad","password":"z4bb4z"}"""
    apiClient.post(uri = "http://www.hello.com:8080/_login", entity = user).map {
      case (code, headers, jv) =>
        cookies = headers.filter(_.isInstanceOf[SetCookie]).map { v =>
          val cookie = v.asInstanceOf[SetCookie].cookie()
          HttpCookiePair(cookie.name(), cookie.value())
        }
        assert(code == StatusCodes.OK)
    }
  }

  it should "create a document set with null content" in {
    apiClient.post(uri = "http://www.hello.com:8080/document_set", headers = List(Cookie(cookies))).map {
      case (code, _, jv) =>
        assert(code == StatusCodes.Created)
        assert("document_set" == jv.asJsObject.fields("result").asJsObject.fields("id").asInstanceOf[JsString].value)
    }
  }

  it should "create a document set with content" in {
    apiClient.post(uri = "http://www.hello.com:8080/document_set2", headers = List(Cookie(cookies)), entity = """{"name":"documentSet"}""").map {
      case (code, _, jv) =>
        assert(code == StatusCodes.Created)
        assert("document_set2" == jv.asJsObject.fields("result").asJsObject.fields("id").asInstanceOf[JsString].value)
    }
  }

  it should "delete a document set" in {
    apiClient.delete(uri = "http://www.hello.com:8080/document_set2", headers = List(Cookie(cookies))).map {
      case (code, _, jv) =>
        assert(code == StatusCodes.OK)
    }
  }

  it should "create a document with null id and null content" in {
    apiClient.post(uri = "http://www.hello.com:8080/document_set/", headers = List(Cookie(cookies))).map {
      case (code, _, jv) =>
        assert(code == StatusCodes.Created)
    }
  }

  it should "create a document with id and null content" in {
    apiClient.post(uri = "http://www.hello.com:8080/document_set/hello_world", headers = List(Cookie(cookies))).map {
      case (code, _, jv) =>
        assert(code == StatusCodes.Created)
        assert("hello_world" == jv.asJsObject.fields("result").asJsObject.fields("id").asInstanceOf[JsString].value)
    }
  }

  it should "create a document with id and content" in {
    apiClient.post(uri = "http://www.hello.com:8080/document_set/hello_world2", headers = List(Cookie(cookies))).map {
      case (code, _, jv) =>
        assert(code == StatusCodes.Created)
        assert("hello_world2" == jv.asJsObject.fields("result").asJsObject.fields("id").asInstanceOf[JsString].value)
    }
  }

  it should "replace a document with content" in {
    apiClient.put(uri = "http://www.hello.com:8080/document_set/hello_world", headers = List(Cookie(cookies)), entity = """{"name":"document"}""").map {
      case (code, _, jv) =>
        assert(code == StatusCodes.OK)
        val result = jv.asJsObject.fields("result").asJsObject
        assert("hello_world" == result.fields("id").asInstanceOf[JsString].value)
        assert("document" == result.fields("name").asInstanceOf[JsString].value)
    }
  }

  it should "patch a document" in {
    apiClient.patch(uri = "http://www.hello.com:8080/document_set/hello_world", headers = List(Cookie(cookies)), entity = """[{"op":"add","path":"/hello","value":"world"}]""").map {
      case (code, _, jv) =>
        assert(code == StatusCodes.OK)
        val result = jv.asJsObject.fields("result").asJsObject
        assert("hello_world" == result.fields("id").asInstanceOf[JsString].value)
        assert("document" == result.fields("name").asInstanceOf[JsString].value)
        assert("world" == result.fields("hello").asInstanceOf[JsString].value)
    }
  }

  it should "create a search template" in {
    val template = """{
      "targets":["document_set"],
      "search":{
          "query":{
              "match_all":{}
          }
      }
    }"""
    apiClient.post(uri = "http://www.hello.com:8080/document_set/search001", headers = List(Cookie(cookies)), entity = template).map {
      case (code, _, jv) =>
        assert(code == StatusCodes.Created)
        val result = jv.asJsObject.fields("result").asJsObject
        assert("search001" == result.fields("id").asInstanceOf[JsString].value)
    }
  }

  it should "search documents using search template" in {
    apiClient.get(uri = "http://www.hello.com:8080/document_set/search001/_exec", headers = List(Cookie(cookies))).map {
      case (code, _, jv) =>
        assert(code == StatusCodes.OK)
    }
  }

  it should "delete a document" in {
    apiClient.delete(uri = "http://www.hello.com:8080/document_set/hello_world2", headers = List(Cookie(cookies))).map {
      case (code, _, jv) =>
        assert(code == StatusCodes.OK)
    }
  }

  it should "delete a document set which have some documents" in {
    apiClient.delete(uri = "http://www.hello.com:8080/document_set", headers = List(Cookie(cookies))).map {
      case (code, _, jv) =>
        assert(code == StatusCodes.OK)
    }
  }

  it should "delete a domain with id" in {
    apiClient.delete(uri = "http://localhost:8080/.domains/www.hello.com", headers = List(Cookie(cookies))).map {
      case (code, _, jv) =>
        assert(code == StatusCodes.OK)
    }
  }

  it should "complete garbage collection" in {
    apiClient.delete(uri = "http://localhost:8080/_gc", headers = List(Cookie(adminCookies))).map {
      case (code, _, jv) =>
        assert(code == StatusCodes.OK)
    }
  }

}
