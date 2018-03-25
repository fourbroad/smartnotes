package com.zxtx.persistence

import akka.NotUsed
import akka.persistence.query.{ EventEnvelope, Offset }
import akka.persistence.query.javadsl._
import akka.stream.javadsl.Source

class ElasticSearchJavaReadJournal(scaladslReadJournal: ElasticSearchReadJournal) extends ReadJournal {

}