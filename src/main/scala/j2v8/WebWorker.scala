package j2v8

import com.eclipsesource.v8.V8
import com.eclipsesource.v8.V8Object
import com.eclipsesource.v8.utils.V8Executor

object WebWorker extends App {
  new WebWorker().start
}

class WebWorker {
  def start(worker: V8Object, s: String) = {
    val executor = new V8Executor(s, true, "messageHandler") {
      protected override def setup(runtime: V8) = configureWorker(runtime)
    }
    worker.getRuntime.registerV8Executor(worker, executor)
    executor.start
  }

  def postMessage(worker: V8Object, s: String) = {
    val executor = worker.getRuntime.getExecutor(worker)
    if (executor != null) {
      executor.postMessage(s)
    }
  }

  def terminate(worker: V8Object) = {
    val executor = worker.getRuntime.removeExecutor(worker)
    if (executor != null) {
      executor.shutdown
    }
  }

  def print(s: String) = System.out.println(s)

  def start = {
    val mainExecutor = new V8Executor("""
      var messageHandler = function(e) { print(e[0]); };
      var w = new Worker("var messageHandler = function(e) { print(e[0]); };");
      w.postMessage('message to send.');
      w.postMessage('another message to send.');
      w.terminate();
      """, true, "messageHandler") {
      protected override def setup(runtime: V8): Unit = configureWorker(runtime)
    }
    mainExecutor.start
    mainExecutor.join
  }

  private def configureWorker(runtime: V8) = {
    runtime.registerJavaMethod(this, "start", "Worker", Array[Class[_]](classOf[V8Object], classOf[String]), true)
    val worker = runtime.getObject("Worker")
    val prototype = runtime.executeObjectScript("Worker.prototype")
    prototype.registerJavaMethod(this, "terminate", "terminate", Array[Class[_]](classOf[V8Object]), true)
    prototype.registerJavaMethod(this, "postMessage", "postMessage", Array[Class[_]](classOf[V8Object], classOf[String]), true)
    runtime.registerJavaMethod(this, "print", "print", Array[Class[_]](classOf[String]))
    worker.setPrototype(prototype)
    worker.release
    prototype.release
  }
}