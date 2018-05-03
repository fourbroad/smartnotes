package com.zxtx.httpservice

import spray.json._
import spray.json.DefaultJsonProtocol._

case class SessionUser(name: String, password: String)
