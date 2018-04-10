package com.zxtx.actors

import spray.json.JsValue
import spray.json.JsObject
import spray.json.JsNumber
import spray.json.DefaultJsonProtocol
import spray.json.JsBoolean
import spray.json.JsString
import spray.json.DeserializationException
import gnieh.diffson.sprayJson.JsonPatch

trait DocumentJsonProtocol extends DefaultJsonProtocol {
  def newMetaObject(jvs: Seq[JsValue], author: String, revision: Long, created: Long, updated: Long, deleted: Option[Boolean]): JsObject = {
    val metaFields = jvs match {
      case Seq(metadata: JsObject) => metadata.fields
      case _                       => Map[String, JsValue]()
    }
    val metaMap = metaFields + ("author" -> JsString(author)) + ("revision" -> JsNumber(revision)) + ("created" -> JsNumber(created)) + ("updated" -> JsNumber(updated))
    JsObject(deleted match {
      case Some(true) => metaMap + ("deleted" -> JsBoolean(true))
      case _          => metaMap
    })
  }

  def newMetaObject(jsValues: Seq[JsValue], author: String, revision: Long, created: Long): JsObject = {
    val metaFields = jsValues match {
      case Seq(metadata: JsObject) => metadata.fields
      case _                       => Map[String, JsValue]()
    }
    JsObject(metaFields + ("author" -> JsString(author)) + ("revision" -> JsNumber(revision)) + ("created" -> JsNumber(created)))
  }

  def extractFields(jv: JsValue, errorMsg: String) = jv match {
    case jo: JsObject =>
      val id = jo.fields("id").asInstanceOf[JsString].value
      val meta = jo.fields("_metadata").asJsObject
      meta.getFields("author", "revision", "created") match {
        case Seq(JsString(author), JsNumber(revision), JsNumber(created)) => (id, author, revision.toLong, created.toLong, jo)
        case _ => throw new DeserializationException(errorMsg)
      }
    case _ => throw new DeserializationException(errorMsg)
  }

  def extractFieldsWithUpdatedDeleted(jv: JsValue, errorMsg: String) = jv match {
    case jo: JsObject =>
      val id = jo.fields("id").asInstanceOf[JsString].value
      val metaObj = jo.fields("_metadata").asJsObject
      val deleted = metaObj.getFields("deleted") match {
        case Seq(JsBoolean(d)) => Some(d)
        case _                 => None
      }
      metaObj.getFields("author", "revision", "created", "updated") match {
        case Seq(JsString(author), JsNumber(revision), JsNumber(created), JsNumber(updated)) =>
          (id, author, revision.toLong, created.toLong, updated.toLong, deleted, jo)
        case _ => throw new DeserializationException(errorMsg)
      }
    case _ => throw new DeserializationException(errorMsg)
  }

  def extractFieldsWithPatch(jv: JsValue, errorMsg: String) = jv match {
    case jo: JsObject =>
      val id = jo.fields("id").asInstanceOf[JsString].value
      val patch = jo.fields("patch")
      val meta = jo.fields("_metadata").asJsObject
      meta.getFields("author", "revision", "created") match {
        case Seq(JsString(author), JsNumber(revision), JsNumber(created)) => (id, author, revision.toLong, created.toLong, JsonPatch(patch), JsObject())
        case _ => throw new DeserializationException(errorMsg)
      }
    case _ => throw new DeserializationException(errorMsg)
  }

  def extractFieldsWithToken(jv: JsValue, errorMsg: String) = jv match {
    case jo: JsObject =>
      val id = jo.fields("id").asInstanceOf[JsString].value
      val token = jo.fields("token").asInstanceOf[JsString].value
      val meta = jo.fields("_metadata").asJsObject
      meta.getFields("author", "revision", "created") match {
        case Seq(JsString(author), JsNumber(revision), JsNumber(created)) => (id, author, revision.toLong, created.toLong, token, JsObject())
        case _ => throw new DeserializationException(errorMsg)
      }
    case _ => throw new DeserializationException(errorMsg)
  }

}