package com.zxtx

import com.typesafe.config.ConfigFactory

import akka.persistence.CapabilityFlag
import akka.persistence.journal.JournalSpec

class ElasticsearchWriteJournalSpec extends JournalSpec(
  config = ConfigFactory.parseString(
    s"""
      akka {
        loglevel = "DEBUG"
        extensions = [akka.persistence.Persistence]
        persistence.journal{
            plugin = "akka.persistence.journal.elasticsearch"
            
            elasticsearch {
                class = "com.zxtx.persistence.ElasticSearchJournal"
                
                event-adapters {
                    event = "com.zxtx.persistence.ElasticSearchStringEventAdapter"
                }
                
                event-adapter-bindings {
                    "java.lang.String" = event
                    "spray.json.JsValue" = event
                }
            }
        }
      }
    """)) {

  override def supportsAtomicPersistAllOfSeveralEvents: Boolean = false
  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = false

}
