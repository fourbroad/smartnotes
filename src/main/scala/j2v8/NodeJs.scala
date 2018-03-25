package j2v8

import com.eclipsesource.v8.JavaCallback
import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Object
import com.eclipsesource.v8.NodeJS
import java.io.PrintWriter
import java.io.File

object NodeJs extends App {
  val NODE_SCRIPT = """
    var http = require('http');
    var server = http.createServer(function (request, response) {
      response.writeHead(200, {'Content-Type': 'text/plain'});
      response.end(someJavaMethod());
    });
    server.listen(8000);
    console.log('Server running at http://127.0.0.1:8000/');
    """
  val nodeJS = NodeJS.createNodeJS();
  val callback = new JavaCallback() {
    def invoke(receiver: V8Object, parameters: V8Array): Object = {
      "Hello, JavaWorld!";
    }
  };

  nodeJS.getRuntime().registerJavaMethod(callback, "someJavaMethod");

  def createTemporaryScriptFile(script: String, name: String): File = {
    val tempFile = File.createTempFile(name, ".js.tmp");
    val writer = new PrintWriter(tempFile, "UTF-8");
    try {
      writer.print(script);
    } finally {
      writer.close();
    }
    return tempFile;
  }

  val nodeScript = createTemporaryScriptFile(NODE_SCRIPT, "example");

  nodeJS.exec(nodeScript);

  while (nodeJS.isRunning()) {
    nodeJS.handleMessage();
  }
  nodeJS.release();
}