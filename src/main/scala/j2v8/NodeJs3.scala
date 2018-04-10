package j2v8

import java.io.File

import com.eclipsesource.v8.JavaCallback
import com.eclipsesource.v8.NodeJS
import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Object

import posix.Signal
import com.eclipsesource.v8.V8Function

object NodeJs3 extends App {

  object DomainWrapper {
    def register(receiver: V8Object, domainName: String) = {
      val runtime = receiver.getRuntime
      val dw = new DomainWrapper(domainName)
      val domain = runtime.getObject("Domain")
      val prototype = runtime.executeObjectScript("Domain.prototype")
      prototype.registerJavaMethod(dw, "login", "login", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String]), true)
      prototype.registerJavaMethod(dw, "logout", "logout", Array[Class[_]]())
      prototype.registerJavaMethod(dw, "callback", "callback", Array[Class[_]](classOf[V8Object], classOf[V8Object]), true)
      domain.setPrototype(prototype)
      prototype.release
      domain.release
    }
  }
  class DomainWrapper(domainName: String) {
    def login(receiver: V8Object, userName: String, password: String) = {
      System.out.println(s"login Domain: ${domainName}")
    }
    def logout = {
      System.out.println("logout Domain.")
    }
    def callback(receiver: V8Object, callback: V8Object) = callback match {
      case vf: V8Function =>
        val params = new V8Array(receiver.getRuntime)
        vf.call(receiver, params)
        params.release
      case o => System.out.println(s"${o}")
    }
  }

  val callback = new JavaCallback() {
    def invoke(receiver: V8Object, parameters: V8Array): Object = {
      "Hello, JavaWorld!";
    }
  };

  val nodeJS = NodeJS.createNodeJS();
  val runtime = nodeJS.getRuntime
  runtime.registerJavaMethod(DomainWrapper, "register", "Domain", Array[Class[_]](classOf[V8Object], classOf[String]), true)

  runtime.registerJavaMethod(callback, "someJavaMethod");

  import scala.sys.process._
  val pid = Seq("sh", "-c", "echo $PPID").!!.trim.toInt
  val sig = Signal.SIGWINCH

  nodeJS.exec(new File("nodejs/world-server2.js"))

  var start = 0L
  while (nodeJS.isRunning()) {
    start = System.currentTimeMillis()
    nodeJS.handleMessage()
    sig.kill(pid)
    System.out.println(s"---------->${System.currentTimeMillis - start}")
  }
  nodeJS.release();
}