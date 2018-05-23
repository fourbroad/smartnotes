package com.zxtx

import spray.json.JsObject
import spray.json.JsValue

package object utils {
  implicit class JsObjectUtils(obj: JsObject) {
    def merge(obj2: JsValue): JsObject = {
      def mergeLoop(val1: JsValue, val2: JsValue): JsValue = (val1, val2) match {
        case (obj1: JsObject, obj2: JsObject) => {
          val map1 = obj1.fields
          val map2 = obj2.fields
          val diff1 = map1.keySet.diff(map2.keySet)
          val diff2 = map2.keySet.diff(map1.keySet)
          val intersect = map1.keySet.intersect(map2.keySet)
          val merged = intersect.map { key =>
            (map1(key), map2(key)) match {
              case (val1: JsObject, val2: JsObject) => (key -> mergeLoop(val1, val2))
              case (_, val2)                        => (key -> val2)
            }
          }.toMap
          val distinct = map1.filter(t => diff1(t._1)) ++ map2.filter(t => diff2(t._1))
          JsObject(distinct ++ merged)
        }
        case (_, val2) => val2
      }
      mergeLoop(obj, obj2).asJsObject
    }
  }
}