package com.zxtx.persistence

import akka.actor.ExtendedActorSystem
import akka.persistence.query.ReadJournalProvider
import com.typesafe.config.Config

class ElasticSearchReadJournalProvider(system: ExtendedActorSystem, config: Config) extends ReadJournalProvider {
  override val scaladslReadJournal: ElasticSearchReadJournal = new ElasticSearchReadJournal(system, config)
  override val javadslReadJournal: ElasticSearchJavaReadJournal = new ElasticSearchJavaReadJournal(scaladslReadJournal)
}