package com.zxtx.serializer

import akka.serialization.SerializerWithStringManifest
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.DefaultJsonProtocol.jsonFormat1
import spray.json.enrichAny
import spray.json.enrichString

class JsonSerializer extends SerializerWithStringManifest {

  case class Event(str: String);
  implicit val eventFormat = jsonFormat1(Event)

  /**
   * Completely unique value to identify this implementation of Serializer, used to optimize network traffic.
   * Values from 0 to 40 are reserved for Akka internal usage.
   */
  override def identifier: Int = Int.MaxValue

  /**
   * Return the manifest (type hint) that will be provided in the fromBinary method.
   * Use `""` if manifest is not needed.
   */
  def manifest(o: AnyRef): String = o.getClass.getName

  /**
   * Serializes the given object into an Array of Byte
   */
  override def toBinary(o: AnyRef): Array[Byte] = o.asInstanceOf[Event].toJson.compactPrint.getBytes

  /**
   * Produces an object from an array of bytes, with an optional type-hint;
   * the class should be loaded using ActorSystem.dynamicAccess.
   *
   * It's recommended to throw `java.io.NotSerializableException` in `fromBinary`
   * if the manifest is unknown. This makes it possible to introduce new message
   * types and send them to nodes that don't know about them. This is typically
   * needed when performing rolling upgrades, i.e. running a cluster with mixed
   * versions for while. `NotSerializableException` is treated as a transient
   * problem in the TCP based remoting layer. The problem will be logged
   * and message is dropped. Other exceptions will tear down the TCP connection
   * because it can be an indication of corrupt bytes from the underlying transport.
   */
  def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    if (manifest == classOf[Event].getName) {
      new String(bytes).parseJson.convertTo[Event]
    } else {
      throw new IllegalArgumentException(s"Unable to handle manifest $manifest, required $Manifest")
    }
  }

}