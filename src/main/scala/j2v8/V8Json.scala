package j2v8

import spray.json.JsObject
import spray.json.JsString
import com.eclipsesource.v8.NodeJS
import com.zxtx.actors.wrappers.V8SprayJson
import com.eclipsesource.v8.V8Object
import spray.json.JsNumber

object V8Json extends App with V8SprayJson {
  val nodeJs = NodeJS.createNodeJS()
  val v8 = nodeJs.getRuntime

  val start = System.currentTimeMillis()

  val text: JsObject = JsObject("key" -> JsString("value"), "Hello" -> JsNumber(100))
  val v8Obj = toV8Object(text, v8)
  v8.add("test", v8Obj)
  val test = v8.get("test").asInstanceOf[V8Object]
  val t = toJsObject(test)
  System.out.println(s"test = ${t}")

  System.out.println(s"${System.currentTimeMillis() - start}")
}