package j2v8

import com.eclipsesource.v8.JavaCallback
import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Object
import com.eclipsesource.v8.NodeJS
import java.io.PrintWriter
import java.io.File

object GenPdf extends App {
  val NODE_SCRIPT = """
    var PDF = require('pdfkit');            //including the pdfkit module
    var fs = require('fs');
    var text = 'adding the text to be written, more things can be added here including new pages';
    doc = new PDF();                        //creating a new PDF object
    doc.pipe(fs.createWriteStream('gen.pdf'));  //creating a write stream
    
    //to write the content on the file system
    doc.text(text, 100, 100);             //adding the text to be written, more things can be added here including new pages
    doc.end(); //we end the document writing.
  """
  val nodeJS = NodeJS.createNodeJS();
  val callback = new JavaCallback() {
    def invoke(receiver: V8Object, parameters: V8Array): Object = "Hello, JavaWorld!"
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