package com.zxtx.actors

import spray.json._
import spray.json.DefaultJsonProtocol._
import gnieh.diffson.sprayJson.JsonPatch

trait DocumentJsonProtocol extends DefaultJsonProtocol {
  def newMetaObject(jvs: Seq[JsValue], author: String, revision: Long, created: Long, updated: Long, removed: Option[Boolean]): JsObject = {
    val metaFields = jvs match {
      case Seq(metadata: JsObject) => metadata.fields
      case _                       => Map[String, JsValue]()
    }
    val metaMap = metaFields + ("author" -> JsString(author)) + ("revision" -> JsNumber(revision)) + ("created" -> JsNumber(created)) + ("updated" -> JsNumber(updated))
    JsObject(removed match {
      case Some(true) => metaMap + ("removed" -> JsBoolean(true))
      case _          => metaMap
    })
  }

  def newMetaObject(jvs: Seq[JsValue], author: String, revision: Long, created: Long): JsObject = {
    val metaFields = jvs match {
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

  def extractFieldsWithUpdatedRemoved(jv: JsValue, errorMsg: String) = jv match {
    case jo: JsObject =>
      val id = jo.fields("id").asInstanceOf[JsString].value
      val metaObj = jo.fields("_metadata").asJsObject
      val removed = metaObj.getFields("removed") match {
        case Seq(JsBoolean(d)) => Some(d)
        case _                 => None
      }
      metaObj.getFields("author", "revision", "created", "updated") match {
        case Seq(JsString(author), JsNumber(revision), JsNumber(created), JsNumber(updated)) =>
          (id, author, revision.toLong, created.toLong, updated.toLong, removed, jo)
        case _ => throw new DeserializationException(errorMsg)
      }
    case _ => throw new DeserializationException(errorMsg)
  }

  def patchValueToString(patch: JsValue, errorMsg: String) = patch match {
    case ja: JsArray => JsArray(ja.elements.map {
      case jo: JsObject =>
        val vStr = jo.getFields("value") match {
          case Seq(jv) => jv.compactPrint
          case _       => throw new SerializationException(errorMsg)
        }
        JsObject(jo.fields + ("value" -> JsString(vStr)))
      case _ => throw new SerializationException(errorMsg)
    })
    case _ => throw new SerializationException(errorMsg)
  }

  def patchValueFromString(patch: JsValue, errorMsg: String) = patch match {
    case ja: JsArray => JsArray(ja.elements.map {
      case jo: JsObject =>
        val vObj = jo.getFields("value") match {
          case Seq(JsString(vStr))=> vStr.parseJson
          case _ => throw new DeserializationException(errorMsg)
        }
        JsObject(jo.fields + ("value" -> vObj))
      case _ => throw new DeserializationException(errorMsg)
    })
    case _ => throw new DeserializationException(errorMsg)
  }

  def extractFieldsWithPatch(jv: JsValue, errorMsg: String) = jv match {
    case jo: JsObject =>
      val id = jo.fields("id").asInstanceOf[JsString].value
      val patch = jo.fields("patch")
      val meta = jo.fields("_metadata").asJsObject
      meta.getFields("author", "revision", "created") match {
        case Seq(JsString(author), JsNumber(revision), JsNumber(created)) => (id, author, revision.toLong, created.toLong, JsonPatch(patchValueFromString(patch, errorMsg)), JsObject())
        case _ => throw new DeserializationException(errorMsg)
      }
    case _ => throw new DeserializationException(errorMsg)
  }

  def extractFieldsWithPassword(jv: JsValue, errorMsg: String) = jv match {
    case jo: JsObject =>
      val id = jo.fields("id").asInstanceOf[JsString].value
      val password = jo.fields("password").asInstanceOf[JsString].value
      val meta = jo.fields("_metadata").asJsObject
      meta.getFields("author", "revision", "created") match {
        case Seq(JsString(author), JsNumber(revision), JsNumber(created)) => (id, author, revision.toLong, created.toLong, password, JsObject())
        case _ => throw new DeserializationException(errorMsg)
      }
    case _ => throw new DeserializationException(errorMsg)
  }

}