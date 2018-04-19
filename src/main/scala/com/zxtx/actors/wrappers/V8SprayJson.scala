package com.zxtx.actors.wrappers

import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Object
import spray.json.JsObject
import spray.json.JsArray
import spray.json.JsNull
import spray.json.JsString
import com.eclipsesource.v8.V8
import spray.json.JsNumber
import spray.json.JsValue
import spray.json.JsBoolean
import spray.json.JsFalse
import spray.json.JsTrue
import com.eclipsesource.v8.Releasable
import com.eclipsesource.v8.V8Function

trait V8SprayJson {
  def toV8Array(jsArray: JsArray, runtime: V8): V8Array = {
    val v8Array = new V8Array(runtime)
    jsArray.elements.foreach { jv =>
      jv match {
        case JsString(str) => v8Array.push(str)
        case JsNumber(bigDecimal) =>
          if (bigDecimal.isBinaryDouble || bigDecimal.isDecimalDouble || bigDecimal.isExactDouble) v8Array.push(bigDecimal.toDouble)
          else if (bigDecimal.isBinaryFloat || bigDecimal.isDecimalFloat || bigDecimal.isExactFloat) v8Array.push(bigDecimal.toFloat)
          else if (bigDecimal.isValidByte) v8Array.push(bigDecimal.toByte)
          else if (bigDecimal.isValidChar) v8Array.push(bigDecimal.toChar)
          else if (bigDecimal.isValidInt) v8Array.push(bigDecimal.toInt)
          else if (bigDecimal.isValidLong) v8Array.push(bigDecimal.toLong)
          else if (bigDecimal.isValidShort) v8Array.push(bigDecimal.toShort)
        case JsTrue       => v8Array.push(true)
        case JsFalse      => v8Array.push(false)
        case JsNull       => v8Array.pushNull()
        case jo: JsObject => 
          val v8Obj = toV8Object(jo, runtime)
          v8Array.push(v8Obj)
          v8Obj.release()
        case ja: JsArray  =>
          val v8arr = toV8Array(ja, runtime)
          v8Array.push(v8arr)
          v8arr.release()
      }
    }
    v8Array
  }

  def toV8Object(jsObject: JsObject, runtime: V8): V8Object = {
    val v8Object = new V8Object(runtime)
    jsObject.fields.foreach { t =>
      t._2 match {
        case JsString(str) => v8Object.add(t._1, str)
        case JsNumber(bigDecimal) =>
          if (bigDecimal.isBinaryDouble || bigDecimal.isDecimalDouble || bigDecimal.isExactDouble) v8Object.add(t._1, bigDecimal.toDouble)
          else if (bigDecimal.isBinaryFloat || bigDecimal.isDecimalFloat || bigDecimal.isExactFloat) v8Object.add(t._1, bigDecimal.toFloat)
          else if (bigDecimal.isValidByte) v8Object.add(t._1, bigDecimal.toByte)
          else if (bigDecimal.isValidChar) v8Object.add(t._1, bigDecimal.toChar)
          else if (bigDecimal.isValidInt) v8Object.add(t._1, bigDecimal.toInt)
          else if (bigDecimal.isValidLong) v8Object.add(t._1, bigDecimal.toLong)
          else if (bigDecimal.isValidShort) v8Object.add(t._1, bigDecimal.toShort)
        case JsTrue       => v8Object.add(t._1, true)
        case JsFalse      => v8Object.add(t._1, false)
        case JsNull       => v8Object.addNull(t._1)
        case jo: JsObject =>
          val v8Obj = toV8Object(jo, runtime)
          v8Object.add(t._1, v8Obj)
          v8Obj.release()
        case ja: JsArray  => 
          val v8Array = toV8Array(ja, runtime)
          v8Object.add(t._1, v8Array)
          v8Array.release()
      }
    }
    v8Object
  }

  def toJsArray(v8Array: V8Array): JsArray = {
    import java.lang._
    var list: List[JsValue] = Nil
    for (i <- 0 until v8Array.length) {
      val obj = v8Array.get(i)
      obj match {
        case str: String                                  => list = JsString(str) :: list
        case i: Integer                                   => list = JsNumber(i) :: list
        case b: Boolean                                   => list = JsBoolean(b) :: list
        case sh: Short                                    => list = JsNumber(sh.intValue()) :: list
        case l: Long                                      => list = JsNumber(l) :: list
        case f: Float                                     => list = JsNumber(f.floatValue()) :: list
        case d: Double                                    => list = JsNumber(d) :: list
        case a: V8Array                                   => list = toJsArray(a) :: list
        case u: V8Object if (u.toString() == "undefined") => list = JsNull :: list
        case v: V8Object                                  => list = toJsObject(v) :: list
        case null                                         => list = JsNull :: list
      }
      if (obj.isInstanceOf[Releasable]) obj.asInstanceOf[Releasable].release()
    }
    new JsArray(list)
  }

  def toJsObject(v8Object: V8Object): JsObject = {
    import java.lang._
    var fields = Map[String, JsValue]()
    v8Object.getKeys.foreach { k =>
      val obj = v8Object.get(k)
      obj match {
        case str: String                                  => fields += (k -> JsString(str))
        case i: Integer                                   => fields += (k -> JsNumber(i))
        case b: Boolean                                   => fields += (k -> JsBoolean(b))
        case sh: Short                                    => fields += (k -> JsNumber(sh.intValue()))
        case l: Long                                      => fields += (k -> JsNumber(l))
        case f: Float                                     => fields += (k -> JsNumber(f.floatValue()))
        case d: Double                                    => fields += (k -> JsNumber(d))
        case a: V8Array                                   => fields += (k -> toJsArray(a))
        case u: V8Object if (u.toString() == "undefined") => fields += (k -> JsNull)
        case v: V8Object                                  => fields += (k -> toJsObject(v))
        case null                                         => fields += (k -> JsNull)
      }
      if (obj.isInstanceOf[Releasable]) obj.asInstanceOf[Releasable].release()
    }
    new JsObject(fields)
  }
}