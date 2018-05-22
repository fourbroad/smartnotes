package com.zxtx.streams

import java.security.KeyStore
import java.security.SecureRandom

import com.typesafe.sslconfig.akka.AkkaSSLConfig

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.TLSClientAuth
import akka.stream.TLSProtocol
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

object StreamIOTLS extends App {

  implicit val system = ActorSystem("StreamGraphs")
  implicit val materializer = ActorMaterializer()
  implicit val ex = system.dispatcher

  val sslConfig = AkkaSSLConfig()

  // Don't hardcode your password in actual code
  val password = "abcdef".toCharArray

  // trust store and keys in one keystore
  val keyStore = KeyStore.getInstance("PKCS12")
  //  keyStore.load(classOf[TcpSpec].getResourceAsStream("/tcp-spec-keystore.p12"), password)

  val tmf = TrustManagerFactory.getInstance("SunX509")
  tmf.init(keyStore)

  val kmf = KeyManagerFactory.getInstance("SunX509")
  kmf.init(keyStore, password)

  // initial ssl context
  val sslContext = SSLContext.getInstance("TLS")
  sslContext.init(kmf.getKeyManagers, tmf.getTrustManagers, new SecureRandom)

  // protocols
  val defaultParams = sslContext.getDefaultSSLParameters
  val defaultProtocols = defaultParams.getProtocols
  val protocols = sslConfig.configureProtocols(defaultProtocols, sslConfig.config)
  defaultParams.setProtocols(protocols)

  // ciphers
  val defaultCiphers = defaultParams.getCipherSuites
  val cipherSuites = sslConfig.configureCipherSuites(defaultCiphers, sslConfig.config)
  defaultParams.setCipherSuites(cipherSuites)

  val negotiateNewSession = TLSProtocol.NegotiateNewSession
    .withCipherSuites(cipherSuites: _*)
    .withProtocols(protocols: _*)
    .withParameters(defaultParams)
    .withClientAuth(TLSClientAuth.None)

}