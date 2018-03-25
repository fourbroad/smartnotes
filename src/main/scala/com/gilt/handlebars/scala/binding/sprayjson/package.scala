package com.gilt.handlebars.scala.binding

import spray.json._

package object sprayjson {
  implicit def jsValueToPlayJsonBinding(jsonValue: JsValue) = new SprayJsonBinding(jsonValue)
  implicit val bindingFactory = SprayJsonBindingFactory
}
