package com.zxtx.httpservice

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.blocking
import scala.concurrent.duration._
import scala.io.StdIn
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.typesafe.config.ConfigFactory
import com.zxtx.actors._
import com.zxtx.actors.DocumentActor._
import com.zxtx.actors.DocumentSetActor._
import com.zxtx.actors.DomainActor._
import com.zxtx.persistence.ElasticSearchStore

import akka.Done
import akka.actor.ActorSystem
import akka.actor.PoisonPill
import akka.event.Logging
import akka.event.LogSource
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.LWWMapKey
import akka.cluster.ddata.LWWMap
import akka.cluster.ddata.Replicator.Get
import akka.cluster.ddata.Replicator.GetSuccess
import akka.cluster.ddata.Replicator.ReadLocal
import akka.cluster.ddata.Replicator.ReadMajority
import akka.cluster.ddata.Replicator.WriteMajority
import akka.cluster.ddata.Replicator.NotFound
import akka.cluster.ddata.Replicator.DataDeleted
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.singleton._
import akka.cluster.singleton.ClusterSingletonProxySettings
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.util.Tuple
import akka.http.scaladsl.settings.ServerSettings
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.server.{ Route, ValidationRejection }
import akka.pattern.ask
import akka.pattern.pipe
import akka.stream.scaladsl.Source
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.Timeout

import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import spray.json._
import gnieh.diffson.sprayJson.JsonPatch
import com.softwaremill.session.CsrfDirectives._
import com.softwaremill.session.CsrfOptions._
import com.softwaremill.session.SessionDirectives._
import com.softwaremill.session.SessionOptions._
import com.softwaremill.session._

import com.roundeights.hasher.Implicits._

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val userFormat = jsonFormat2(User)
}

object HttpService extends App with Directives with JsonSupport with APIStatusCodes {
  implicit val logSource: LogSource[AnyRef] = new LogSource[AnyRef] {
    def genString(o: AnyRef): String = o.getClass.getName
    override def getClazz(o: AnyRef): Class[_] = o.getClass
  }

  val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + 2551).withFallback(ConfigFactory.load())

  val settings = ServerSettings(config).withVerboseErrorMessages(true)

  implicit val system = ActorSystem("SmartNotes", config)
  val log = Logging(system, this)

  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher
  implicit val timeout = Timeout(30 seconds)
  implicit val cluster = Cluster(system)

  val rootDomain = system.settings.config.getString("domain.root-domain")
  val cacheKey = system.settings.config.getString("domain.cache-key")
  val replicator = DistributedData(system).replicator
  val store = ElasticSearchStore(system)

  val sessionConfig = SessionConfig.default("p45ktqoun126o3o0i3vart85g2iivptmce3i71p9g4bnut3eh3sqfc5oukppp3e778obvg0gs3ucc342a6qk0o6isu70m2eqj2i9bng5i6p18b56nfu3h1umsvbf9q3s")
  implicit val sessionManager = new SessionManager[HttpSession](sessionConfig)
  implicit val refreshTokenStorage = new InMemoryRefreshTokenStorage[HttpSession] {
    def log(msg: String) = HttpService.this.log.info(msg)
  }
  val requiredHttpSession = requiredSession(refreshable, usingCookies)
  val invalidateHttpSession = invalidateSession(refreshable, usingCookies)
  def setHttpSession(s: HttpSession) = setSession(refreshable, usingCookies, s)

  private val readMajority = ReadMajority(5.seconds)
  private val writeMajority = WriteMajority(5.seconds)

  val domainRegion = ClusterSharding(system).start(
    typeName = DomainActor.shardName,
    entityProps = DomainActor.props(),
    settings = ClusterShardingSettings(system),
    extractEntityId = DomainActor.idExtractor,
    extractShardId = DomainActor.shardResolver)

  val documentSetRegion = ClusterSharding(system).start(
    typeName = DocumentSetActor.shardName,
    entityProps = DocumentSetActor.props(),
    settings = ClusterShardingSettings(system),
    extractEntityId = DocumentSetActor.idExtractor,
    extractShardId = DocumentSetActor.shardResolver)

  val documentRegion = ClusterSharding(system).start(
    typeName = DocumentActor.shardName,
    entityProps = DocumentActor.props(),
    settings = ClusterShardingSettings(system),
    extractEntityId = DocumentActor.idExtractor,
    extractShardId = DocumentActor.shardResolver)

  system.actorOf(
    ClusterSingletonManager.props(NameService.props, PoisonPill, ClusterSingletonManagerSettings(system).withRole("nameService")),
    "nameService")

  val nameServiceProxy = system.actorOf(
    ClusterSingletonProxy.props(
      settings = ClusterSingletonProxySettings(system).withRole("nameService"),
      singletonManagerPath = "/user/nameService"),
    name = "nameServiceProxy")

  val serverBinding = new AtomicReference[ServerBinding]()
  /**
   * [[ActorSystem]] used to start this server. Stopping this system will interfere with the proper functioning condition of the server.
   */
  val systemReference = new AtomicReference[ActorSystem](system)

  def routes: Route = cors() {
    //    randomTokenCsrfProtection(checkHeader) {
    extractHost { domainName =>
      checkAsync(checkDomain(domainName)) { result =>
        result match {
          case true  => documentRoutes(domainName) ~ documentSetRoutes(domainName) ~ domainRoutes(domainName)
          case false => reject(AuthorizationFailedRejection)
        }
      }
    }
    //    }
  }

  def documentRoutes(domain: String): Route = requiredHttpSession { session => // http://www.domain.com/documentSet/document
    val username = session.username
    pathPrefix(Segment) { documentSet =>
      documentSet match {
        case ".domains" => reject
        case _ => checkAsync(checkDocumentSet(domain, documentSet)) { result =>
          result match {
            case true =>
              pathPrefix(Segment) { docId =>
                pathEnd {
                  getDocument(domain, documentSet, docId, username) ~
                    createDocument(domain, documentSet, docId, username) ~
                    replaceDocument(domain, documentSet, docId, username) ~
                    patchDocument(domain, documentSet, docId, username) ~
                    deleteDocument(domain, documentSet, docId, username)
                } ~
                  executeDocument(domain, documentSet, docId, username)
              } ~
                pathSingleSlash {
                  findDocuments(domain, documentSet, username) ~ createDocument(domain, documentSet, "", username)
                }
            case false => reject
          }
        }
      }
    }
  }

  def documentSetRoutes(domain: String): Route = requiredHttpSession { session =>
    val username = session.username
    pathPrefix(".documentsets") {
      path(Segment) { documentSet =>
        getDocumentSet(domain, documentSet, username)
      }
    } ~
      pathPrefix(Segment) { documentSet =>
        if (documentSet.startsWith("_")) reject
        else pathEnd { // http://www.domain.com/documentSetName
          checkAsync(checkDocumentSet(domain, documentSet)) { result =>
            result match {
              case true =>
                getDocumentSet(domain, documentSet, username) ~ replaceDocumentSet(domain, documentSet, username) ~ patchDocumentSet(domain, documentSet, username) ~
                  deleteDocumentSet(domain, documentSet, username) ~ post {
                    completeJson(APIStatusCode(StatusCodes.Conflict.intValue, StatusCodes.Conflict.reason, documentSetAlreadyExists))
                  }
              case false =>
                createDocumentSet(domain, documentSet, username)
            }
          }
        }
      } ~ findDocumentSets(domain, username)
  }

  def domainRoutes(domain: String): Route = requiredHttpSession { session =>
    val username = session.username
    registerUser ~ authorizeDomain(domain, username) ~ logout ~ gc(domain, username) ~ {
      domain match {
        case `rootDomain` => pathPrefix(".domains") {
          findDomains(username) ~ pathPrefix(Segment) { name =>
            getDomain(name, username) ~ createDomain(name, username) ~ replaceDomain(name, username) ~ patchDomain(name, username) ~ deleteDomain(name, username)
          }
        }
        case _ => reject(AuthorizationFailedRejection)
      }
    }
  } ~ login

  def findDocuments(domain: String, documentSet: String, user: String) = get { // http://www.domain.com/
    parameterSeq { params =>
      entity(as[String]) { c =>
        val body = if (c.isEmpty) JsObject() else c.parseJson.asJsObject
        onCompleteJson((documentSetRegion ? FindDocuments(s"${domain}~.documentsets~${documentSet}", user, params, body)).map(documentSetStatus))
      }
    }
  }

  def createDocument(domain: String, documentSet: String, docId: String, user: String) = post {
    entity(as[String]) { c =>
      val id = if (docId.isEmpty()) UUID.randomUUID().toString else docId
      val raw = if (c.isEmpty) JsObject() else c.parseJson.asJsObject
      onCompleteJson((documentRegion ? CreateDocument(s"${domain}~${documentSet}~${id}", user, raw)).map(documentStatus))
    }
  }

  def getDocument(domain: String, documentSet: String, docId: String, user: String) = get {
    onCompleteJson((documentRegion ? GetDocument(s"${domain}~${documentSet}~${docId}", user)).map(documentStatus))
  }

  def replaceDocument(domain: String, documentSet: String, docId: String, user: String) = put {
    entity(as[String]) { c =>
      val raw = if (c.isEmpty) JsObject() else c.parseJson.asJsObject
      onCompleteJson((documentRegion ? ReplaceDocument(s"${domain}~${documentSet}~${docId}", user, raw)).map(documentStatus))
    }
  }

  def patchDocument(domain: String, documentSet: String, docId: String, user: String) = patch {
    entity(as[JsValue]) { jv =>
      onCompleteJson((documentRegion ? PatchDocument(s"${domain}~${documentSet}~${docId}", user, JsonPatch(jv))).map(documentStatus))
    }
  }

  def deleteDocument(domain: String, documentSet: String, docId: String, user: String) = delete {
    onCompleteJson((documentRegion ? DeleteDocument(s"${domain}~${documentSet}~${docId}", user)).map(documentStatus))
  }

  def executeDocument(domain: String, documentSet: String, docId: String, user: String) = pathSuffix("_exec") {
    get {
      parameterSeq { params =>
        entity(as[String]) { c =>
          val body = if (c.isEmpty) JsObject() else c.parseJson.asJsObject
          onCompleteJson((documentRegion ? ExecuteDocument(s"${domain}~${documentSet}~${docId}", user, params, body)).map(documentStatus))
        }
      }
    }
  }

  def registerUser = path("_register") {
    post {
      entity(as[User]) { ud =>
        val raw = JsObject("name" -> JsString(ud.name), "password" -> JsString(ud.password.md5.hex))
        val userProfile = JsObject("roles" -> JsArray(JsString("user")))
        onCompleteJson {
          val createUser = documentRegion ? CreateDocument(s"${rootDomain}~users~${ud.name}", adminName, raw)
          createUser.flatMap {
            case _: DocumentCreated =>
              val createProfile = documentRegion ? CreateDocument(s"${rootDomain}~profiles~${ud.name}", adminName, userProfile)
              createProfile.map {
                case _: DocumentCreated => APIStatusCode(StatusCodes.OK.intValue, StatusCodes.OK.reason, JsString(s"User ${ud.name} has been registered successfully!"))
                case _                  => APIStatusCode(StatusCodes.InternalServerError.intValue, StatusCodes.InternalServerError.reason, JsString(s"Register {ud.name} error!"))
              }
            case DocumentAlreadyExists => Future.successful(APIStatusCode(StatusCodes.Conflict.intValue, StatusCodes.Conflict.reason, JsString(s"${ud.name} is already registered!")))
          }
        }
      }
    }
  }

  def authorizeDomain(domain: String, user: String) = pathSuffix("_authorize") {
    patch {
      entity(as[JsValue]) { jv =>
        onCompleteJson((domainRegion ? AuthorizeDomain(s"${rootDomain}~.domains~${domain}", user, JsonPatch(jv))).map(domainStatus))
      }
    }
  }

  def login = path("_login") {
    post {
      entity(as[User]) { user =>
        checkAsync(checkPassword(user)) { result =>
          result match {
            case true =>
              setHttpSession(HttpSession(user.name)) {
                //                  setNewCsrfToken(checkHeader) { ctx => ctx.complete("ok") }
                val ok = StatusCodes.OK
                completeJson(APIStatusCode(ok.intValue, ok.reason, JsString(s"Welcome ${user.name} to visit Smartnotes.")))
              }
            case _ =>
              val notFound = StatusCodes.NotFound
              completeJson(APIStatusCode(notFound.intValue, notFound.reason, JsString(s"User name or password error!")))
          }
        }
      }
    }
  }

  def logout = path("_logout") {
    delete {
      invalidateHttpSession { ctx =>
        ctx.complete("ok")
      }
    }
  }

  def gc(domainName: String, user: String) = path("_gc") {
    delete {
      onCompleteJson(garbageCollection(domainName, user))
    }
  }

  def getDocumentSet(domain: String, name: String, user: String) = get {
    parameters('path ? "/") { path =>
      onCompleteJson((documentSetRegion ? GetDocumentSet(s"${domain}~.documentsets~${name}", user, path)).map(documentSetStatus))
    }
  }

  def replaceDocumentSet(domain: String, name: String, user: String) = put {
    entity(as[String]) { c =>
      val raw = if (c.isEmpty) JsObject() else c.parseJson.asJsObject
      onCompleteJson((documentSetRegion ? ReplaceDocumentSet(s"${domain}~.documentsets~${name}", user, raw)).map(documentSetStatus))
    }
  }

  def patchDocumentSet(domain: String, name: String, user: String) = patch {
    entity(as[JsValue]) { jv =>
      onCompleteJson((documentSetRegion ? PatchDocumentSet(s"${domain}~.documentsets~${name}", user, JsonPatch(jv))).map(documentSetStatus))
    }
  }

  def deleteDocumentSet(domain: String, name: String, user: String) = delete {
    onCompleteJson((documentSetRegion ? DeleteDocumentSet(s"${domain}~.documentsets~${name}", user)).map(documentSetStatus))
  }

  def createDocumentSet(domain: String, name: String, user: String) = post {
    entity(as[String]) { c =>
      val raw = if (c.isEmpty) JsObject() else c.parseJson.asJsObject
      onCompleteJson((documentSetRegion ? CreateDocumentSet(s"${domain}~.documentsets~${name}", user, raw)).map(documentSetStatus))
    }
  }

  def findDocumentSets(domain: String, user: String) = pathSingleSlash { // http://www.domain.com/
    get {
      parameterSeq { params =>
        entity(as[String]) { c =>
          val body = if (c.isEmpty) JsObject() else c.parseJson.asJsObject
          onCompleteJson((documentSetRegion ? FindDocuments(s"${domain}~.documentsets~.documentsets", user, params, body)).map(documentSetStatus))
        }
      }
    }
  }

  def getDomain(domain: String, user: String) = get {
    onCompleteJson((domainRegion ? GetDomain(s"${rootDomain}~.domains~${domain}", user)).map(domainStatus))
  }

  def createDomain(domain: String, user: String) = post {
    entity(as[String]) { c =>
      val raw = if (c.isEmpty) JsObject() else c.parseJson.asJsObject
      onCompleteJson((domainRegion ? CreateDomain(s"${rootDomain}~.domains~${domain}", user, raw)).map(domainStatus))
    }
  }

  def replaceDomain(domain: String, user: String) = put {
    entity(as[String]) { c =>
      val raw = if (c.isEmpty) JsObject() else c.parseJson.asJsObject
      onCompleteJson((domainRegion ? ReplaceDomain(s"${rootDomain}~.domains~${domain}", user, raw)).map(domainStatus))
    }
  }

  def patchDomain(domain: String, user: String) = patch {
    entity(as[JsValue]) { jv =>
      onCompleteJson((domainRegion ? PatchDocument(s"${rootDomain}~.domains~${domain}", user, JsonPatch(jv))).map(domainStatus))
    }
  }

  def deleteDomain(domain: String, user: String) = delete {
    onCompleteJson((domainRegion ? DeleteDomain(s"${rootDomain}~.domains~${domain}", user)).map(domainStatus))
  }

  def findDomains(user: String) = pathSingleSlash {
    pathEnd {
      get {
        parameterSeq { params =>
          entity(as[String]) { c =>
            val body = if (c.isEmpty) JsObject() else c.parseJson.asJsObject
            onCompleteJson((documentSetRegion ? FindDocuments(s"${rootDomain}~.documentsets~.domains", user, params, body)).map(documentSetStatus))
          }
        }
      }
    }
  }

  def garbageCollection(domain: String, user: String) =
    (domainRegion ? GarbageCollection(s"${rootDomain}~.domains~${domain}", user)).map(domainStatus)

  def onCompleteJson(future: ⇒ Future[Any]) = onComplete(future) {
    case Success(code: APIStatusCode) => completeJson(code)
    case Failure(e) =>
      val code = StatusCodes.InternalServerError
      completeJson(APIStatusCode(code.intValue, code.reason, JsString(e.toString)))
  }

  def completeJson(code: APIStatusCode): Route = parameters('pretty.?) { pretty =>
    val printPretty = pretty match {
      case Some("true" | "") => true
      case _                 => false
    }
    val statusCode = StatusCodes.getForKey(code.code) match {
      case Some(c) => c
      case None    => StatusCodes.InternalServerError
    }
    complete(statusCode, HttpEntity(ContentTypes.`application/json`, if (printPretty) code.toJson.prettyPrint else code.toJson.compactPrint))
  }

  def checkAsync(check: => Future[Boolean]): Directive1[Boolean] = checkAsync(ctx => check)
  def checkAsync(check: RequestContext => Future[Boolean]): Directive1[Boolean] =
    extractExecutionContext.flatMap { implicit ec ⇒
      extract(check).flatMap { fa ⇒
        onComplete(fa).flatMap {
          case Success(true)  ⇒ provide(true)
          case Success(false) ⇒ provide(false)
          case Failure(e)     => throw new RuntimeException(e)
        }
      }
    }

  def checkPassword(user: User) = {
    Source.fromFuture {
      documentRegion ? GetDocument(s"${rootDomain}~users~${user.name}", user.name)
    }.map {
      case doc: Document => doc.raw.fields("password").asInstanceOf[JsString].value == user.password.md5.hex
      case _             => false
    }.runWith(Sink.head[Boolean])
  }

  def checkDomain(domainName: String): Future[Boolean] = {
    val queryCache = replicator ? Get(LWWMapKey[String, Any](cacheKey), ReadLocal)
    queryCache.map {
      case g @ GetSuccess(LWWMapKey(_), _) =>
        g.dataValue match {
          case data: LWWMap[_, _] => data.asInstanceOf[LWWMap[String, Any]].get(domainName) match {
            case Some(_) => true
            case None    => false
          }
        }
      case NotFound(_, _) => false
    }
  }

  def checkDocumentSet(domainName: String, documentSetName: String): Future[Boolean] = {
    val key = s"${domainName}~${documentSetName}"
    val queryCache = replicator ? Get(LWWMapKey[String, Any](cacheKey), ReadLocal)
    queryCache.map {
      case g @ GetSuccess(LWWMapKey(_), _) =>
        g.dataValue match {
          case data: LWWMap[_, _] => data.asInstanceOf[LWWMap[String, Any]].get(key) match {
            case Some(_) => true
            case None    => false
          }
        }
      case NotFound(_, _) => false
    }
  }

  val adminName = system.settings.config.getString("domain.administrator.name")
  for {
    r1 <- Source.fromFuture {
      domainRegion ? GetDomain(s"${rootDomain}~.domains~${rootDomain}", adminName)
    }.runWith(Sink.head[Any])
    if !r1.isInstanceOf[Domain]
    r2 <- Source.fromFuture {
      domainRegion ? CreateDomain(s"${rootDomain}~.domains~${rootDomain}", adminName, JsObject())
    }.runWith(Sink.head[Any])
    if r2.isInstanceOf[DomainCreated]
  } System.out.println("System intialize successfully!")

  /**
   * It tries to retrieve the [[ServerBinding]] if the server has been successfully started. It fails otherwise.
   * You can use this method to attempt to retrieve the [[ServerBinding]] at any point in time to, for example, stop the server due to unexpected circumstances.
   */
  def binding(): Try[ServerBinding] = {
    if (serverBinding.get() == null) Failure(new IllegalStateException("Binding not yet stored. Have you called startServer?"))
    else Success(serverBinding.get())
  }

  /**
   * Hook that will be called just after the server termination. Override this method if you want to perform some cleanup actions after the server is stopped.
   * The `attempt` parameter is represented with a [[Try]] type that is successful only if the server was successfully shut down.
   */
  def postServerShutdown(attempt: Try[Done], system: ActorSystem): Unit = {
    systemReference.get().log.info("Shutting down the server")
  }

  /**
   * Hook that will be called just after the Http server binding is done. Override this method if you want to perform some actions after the server is up.
   */
  def postHttpBinding(binding: Http.ServerBinding): Unit = {
    systemReference.get().log.info(s"Server online at http://${binding.localAddress.getHostName}:${binding.localAddress.getPort}/")
  }

  /**
   * Hook that will be called in case the Http server binding fails. Override this method if you want to perform some actions after the server binding failed.
   */
  def postHttpBindingFailure(cause: Throwable): Unit = {
    systemReference.get().log.error(cause, s"Error starting the server ${cause.getMessage}")
  }

  /**
   * Hook that lets the user specify the future that will signal the shutdown of the server whenever completed.
   */
  def waitForShutdownSignal(system: ActorSystem)(implicit ec: ExecutionContext): Future[Done] = {
    val promise = Promise[Done]()
    sys.addShutdownHook {
      promise.trySuccess(Done)
    }

    Future {
      blocking {
        if (StdIn.readLine("Press RETURN to stop...\n") != null)
          promise.trySuccess(Done)
      }
    }
    promise.future
  }

  val bindingFuture = Http().bindAndHandle(handler = routes, interface = "localhost", port = 8080, settings = settings)
  bindingFuture.onComplete {
    case Success(binding) ⇒
      //setting the server binding for possible future uses in the client
      serverBinding.set(binding)
      postHttpBinding(binding)
    case Failure(cause) ⇒
      postHttpBindingFailure(cause)
  }

  Await.ready(
    bindingFuture.flatMap(_ ⇒ waitForShutdownSignal(system)), // chaining both futures to fail fast
    Duration.Inf) // It's waiting forever because maybe there is never a shutdown signal

  bindingFuture.flatMap(_.unbind()).onComplete(attempt ⇒ {
    postServerShutdown(attempt.map(_ ⇒ Done), system)
    system.terminate()
  })
}