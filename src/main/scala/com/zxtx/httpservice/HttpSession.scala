package com.zxtx.httpservice

import com.softwaremill.session.{ SessionSerializer, SingleValueSessionSerializer }

import scala.util.Try

case class HttpSession(username: String)

object HttpSession {
  implicit def serializer: SessionSerializer[HttpSession, String] =
    new SingleValueSessionSerializer(_.username, (un: String) => Try {
      HttpSession(un)
    })
}