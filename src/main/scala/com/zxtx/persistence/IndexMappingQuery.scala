package com.zxtx.persistence

import akka.persistence.query.scaladsl.ReadJournal
import spray.json._
import akka.stream.scaladsl.Source
import akka.NotUsed

trait IndexMappingQuery extends ReadJournal {
  def indexMapping(persistenceId: String): Source[JsValue, NotUsed]
}