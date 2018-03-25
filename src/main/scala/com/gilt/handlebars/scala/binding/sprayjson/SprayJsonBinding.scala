package com.gilt.handlebars.scala.binding.sprayjson

import com.gilt.handlebars.scala.binding.{ BindingFactory, FullBinding, Binding, VoidBinding }
import com.gilt.handlebars.scala.binding.sprayjson.SprayJsonBinding;
import com.gilt.handlebars.scala.binding.sprayjson.SprayJsonBindingFactory;
import com.gilt.handlebars.scala.helper.Helper
import com.gilt.handlebars.scala.logging.Loggable
import java.lang.reflect.Method
import spray.json._

class SprayJsonBinding(val data: JsValue) extends FullBinding[JsValue] with Loggable {
  override def toString = s"SprayJsonBinding(${data})"
  lazy val factory = SprayJsonBindingFactory

  lazy val render = if (isTruthy)
    data match {
      case JsString(s)  => s
      case JsNumber(n)  => n.toString
      case JsBoolean(b) => b.toString
      case _            => data.toString
    }
  else
    ""

  lazy val isTruthy = data match {
    case JsBoolean(t) => t
    case JsString(s)  => s != ""
    case JsNumber(n)  => n != 0
    case JsNull       => false
    case _            => true
  }

  lazy val isDictionary = data.isInstanceOf[JsObject]
  lazy val isCollection = data.isInstanceOf[JsArray] && !isDictionary

  lazy val isDefined = data match {
    case JsNull => false
    case _      => true
  }

  def traverse(key: String, args: Seq[Binding[JsValue]] = Seq()): Binding[JsValue] =
    data match {
      case m: JsObject => m.getFields(key) match {
        case Seq(jv) => new SprayJsonBinding(jv)
        case _ =>
          info(s"Could not traverse key ${key} in ${m}")
          VoidBinding[JsValue]
      }
      case _ => VoidBinding[JsValue]
    }

  protected def collectionToIterable = data match {
    case JsArray(m) =>
      m.toIterable
    case _ =>
      throw new Exception("I shouldn't be here")
  }

  protected def dictionaryToIterable = data match {
    case JsObject(m) => m.toIterable
    case _           => throw new Exception("I shouldn't be here")
  }
}

object SprayJsonBindingFactory extends BindingFactory[JsValue] {
  def apply(_model: JsValue): SprayJsonBinding = new SprayJsonBinding(_model)
  def bindPrimitive(v: String) = apply(JsString(v))
  def bindPrimitive(b: Boolean) = apply(JsBoolean(b))
  def bindPrimitive(model: Int) = apply(JsNumber(model))
}
