package com.zxtx.httpservice

import spray.json._
import spray.json.DefaultJsonProtocol._
import akka.http.scaladsl.model.StatusCodes._

import com.zxtx.actors.DocumentActor._
import com.zxtx.actors.DocumentSetActor._
import com.zxtx.actors.DomainActor._
import com.zxtx.actors._
import com.zxtx.actors.ACL._

case class APIStatusCode(code: Int, reason: String, result: JsValue)

trait APIStatusCodes {
  implicit val apiStatusCodeFormat = jsonFormat3(APIStatusCode)

  val documentNotFound = JsString("Document is not found!")
  val documentAlreadyExists = JsString("Document already exists!")
  val documentIsCreating = JsString("Document is creating!")
  val documentDeleted = JsString("Document is deleted successfully!")
  val documentSoftDeleted = JsString("Document has been soft deleted!")

  val documentSetNotFound = JsString("DocumentSet is not found!")
  val documentSetAlreadyExists = JsString("DocumentSet already exists!")
  val documentSetIsCreating = JsString("DocumentSet is creating!")
  val documentSetDeleted = JsString("DocumentSet is deleted successfully!")
  val documentSetSoftDeleted = JsString("DocumentSet has been soft deleted!")

  val domainNotFound = JsString("Domain is not found!")
  val domainAlreadyExists = JsString("Domain already exists!")
  val domainIsCreating = JsString("Domain is creating!")
  val domainDeleted = JsString("Domain is deleted successfully!")
  val domainSoftDeleted = JsString("Domain has been soft deleted!")

  val garbageCollectionCompleted = JsString("Garbage collection is completed")
  val unauthorizedAccess = JsString("Unauthorized access!")

  import com.zxtx.actors.DocumentActor.JsonProtocol._
  def documentStatus: Any => APIStatusCode = {
    case DocumentCreated(_, _, _, _, raw)    => APIStatusCode(Created.intValue, Created.reason, raw)
    case DocumentReplaced(_, _, _, _, raw)   => APIStatusCode(OK.intValue, OK.reason, raw)
    case DocumentPatched(_, _, _, _, _, raw) => APIStatusCode(OK.intValue, OK.reason, raw)
    case DocumentDeleted(_, _, _, _, _)      => APIStatusCode(OK.intValue, OK.reason, documentDeleted)
    case document: Document                  => APIStatusCode(OK.intValue, OK.reason, document.toJson)
    case documents: JsObject                 => APIStatusCode(OK.intValue, OK.reason, documents)
    case Denied                              => APIStatusCode(Unauthorized.intValue, Unauthorized.reason, unauthorizedAccess)
    case DocumentNotFound                    => APIStatusCode(NotFound.intValue, NotFound.reason, documentNotFound)
    case DocumentAlreadyExists               => APIStatusCode(Conflict.intValue, Conflict.reason, documentAlreadyExists)
    case DocumentIsCreating                  => APIStatusCode(Conflict.intValue, Conflict.reason, documentIsCreating)
    case DocumentSoftDeleted                 => APIStatusCode(Conflict.intValue, Conflict.reason, documentSoftDeleted)
    case ExecuteDocumentException(e)         => APIStatusCode(InternalServerError.intValue, InternalServerError.reason, JsString(e.toString))
    case PatchDocumentException(e)           => APIStatusCode(InternalServerError.intValue, InternalServerError.reason, JsString(e.toString))
  }

  import com.zxtx.actors.DocumentSetActor.JsonProtocol._
  def documentSetStatus: Any => APIStatusCode = {
    case DocumentSetCreated(_, _, _, _, raw)    => APIStatusCode(Created.intValue, Created.reason, raw)
    case DocumentSetReplaced(_, _, _, _, raw)   => APIStatusCode(OK.intValue, OK.reason, raw)
    case DocumentSetPatched(_, _, _, _, _, raw) => APIStatusCode(OK.intValue, OK.reason, raw)
    case DocumentSetDeleted(_, _, _, _, _)      => APIStatusCode(OK.intValue, OK.reason, documentSetDeleted)
    case documentSet: DocumentSet               => APIStatusCode(OK.intValue, OK.reason, documentSet.toJson)
    case documentSets: JsObject                 => APIStatusCode(OK.intValue, OK.reason, documentSets)
    case Denied                                 => APIStatusCode(Unauthorized.intValue, Unauthorized.reason, unauthorizedAccess)
    case GarbageCollectionCompleted             => APIStatusCode(OK.intValue, OK.reason, garbageCollectionCompleted)
    case DocumentSetNotFound                    => APIStatusCode(NotFound.intValue, NotFound.reason, documentSetNotFound)
    case DocumentSetAlreadyExists               => APIStatusCode(Conflict.intValue, Conflict.reason, documentSetAlreadyExists)
    case DocumentSetIsCreating                  => APIStatusCode(Conflict.intValue, Conflict.reason, documentSetIsCreating)
    case DocumentSetSoftDeleted                 => APIStatusCode(Conflict.intValue, Conflict.reason, documentSetSoftDeleted)
    case PatchDocumentSetException(e)           => APIStatusCode(InternalServerError.intValue, InternalServerError.reason, JsString(e.toString))
  }

  import com.zxtx.actors.DomainActor.JsonProtocol._
  def domainStatus: Any => APIStatusCode = {
    case DomainCreated(_, _, _, _, raw)       => APIStatusCode(Created.intValue, Created.reason, raw)
    case DomainReplaced(_, _, _, _, raw)      => APIStatusCode(OK.intValue, OK.reason, raw)
    case DomainPatched(_, _, _, _, _, raw)    => APIStatusCode(OK.intValue, OK.reason, raw)
    case DomainAuthorized(_, _, _, _, _, raw) => APIStatusCode(OK.intValue, OK.reason, raw)
    case DomainDeleted(_, _, _, _, _)         => APIStatusCode(OK.intValue, OK.reason, domainDeleted)
    case domain: Domain                       => APIStatusCode(OK.intValue, OK.reason, domain.toJson)
    case domains: JsObject                    => APIStatusCode(OK.intValue, OK.reason, domains)
    case Denied                               => APIStatusCode(Unauthorized.intValue, Unauthorized.reason, unauthorizedAccess)
    case GarbageCollectionCompleted           => APIStatusCode(OK.intValue, OK.reason, garbageCollectionCompleted)
    case DomainNotFound                       => APIStatusCode(NotFound.intValue, NotFound.reason, domainNotFound)
    case DomainAlreadyExists                  => APIStatusCode(Conflict.intValue, Conflict.reason, domainAlreadyExists)
    case DomainIsCreating                     => APIStatusCode(Conflict.intValue, Conflict.reason, domainIsCreating)
    case DomainSoftDeleted                    => APIStatusCode(Conflict.intValue, Conflict.reason, domainSoftDeleted)
    case PatchDomainException(e)              => APIStatusCode(InternalServerError.intValue, InternalServerError.reason, JsString(e.toString))
    case AuthorizeDomainException(e)          => APIStatusCode(InternalServerError.intValue, InternalServerError.reason, JsString(e.toString))
  }

}