package com.zxtx.actors.wrappers

import com.eclipsesource.v8.V8Object
import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Function
import com.eclipsesource.v8.V8
import com.eclipsesource.v8.V8Value
import com.eclipsesource.v8.Releasable

object CallbackWrapper {

  abstract class ParametersGenerator(val runtime: V8) {
    var toBeReleased = Set[V8Value]()
    def prepare(params: V8Array)
    def release() = toBeReleased.foreach { v8Value => if (v8Value.isInstanceOf[Releasable]) v8Value.release() }
  }
}

case class CallbackWrapper(receiver: V8Object, callback: V8Function) {
  import CallbackWrapper._

  val runtime = receiver.getRuntime()

  private val receiverTwin = receiver.twin()
  private val callbackTwin = callback.twin()
  private val params = new V8Array(runtime)
  private var pg: ParametersGenerator = _

  def setParametersGenerator(parametersGenerator: ParametersGenerator) = {
    pg = parametersGenerator
  }

  def call() = {
    pg.prepare(params)
    callbackTwin.call(receiverTwin, params)
    pg.release()
    params.release()
    receiverTwin.release()
    callbackTwin.release()
  }
}
