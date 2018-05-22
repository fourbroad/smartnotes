package j2v8

import java.io.File

import com.eclipsesource.v8.JavaCallback
import com.eclipsesource.v8.NodeJS
import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Object

import posix.Signal

object NodeJs extends App {

  object DomainWrapper {
    def bind(receiver: V8Object, domainName: String) = {
      val runtime = receiver.getRuntime
      val dw = new DomainWrapper(domainName)
      val domain = runtime.getObject("Domain")
      val prototype = runtime.executeObjectScript("Domain.prototype")
      prototype.registerJavaMethod(dw, "login", "login", Array[Class[_]](classOf[V8Object], classOf[String], classOf[String]), true)
      prototype.registerJavaMethod(dw, "logout", "logout", Array[Class[_]]())
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
  }

  val nodeJS = NodeJS.createNodeJS()

  System.out.println("Node version = " + nodeJS.getNodeVersion())

  val callback = new JavaCallback() {
    def invoke(receiver: V8Object, parameters: V8Array): Object = {
      "Hello, JavaWorld!";
    }
  };

  val runtime = nodeJS.getRuntime

  runtime.registerJavaMethod(DomainWrapper, "bind", "Domain", Array[Class[_]](classOf[V8Object], classOf[String]), true)

  runtime.registerJavaMethod(callback, "someJavaMethod");

  import scala.sys.process._
  val pid = Seq("sh", "-c", "echo $PPID").!!.trim.toInt
  val sig = Signal.SIGUSR2

  nodeJS.exec(new File("nodejs/hello-world.js"))

  while (nodeJS.isRunning()) {
    nodeJS.handleMessage()
  }
  nodeJS.release();
}