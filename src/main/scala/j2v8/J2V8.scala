package j2v8

import com.eclipsesource.v8._
import com.eclipsesource.v8.utils.V8Executor
import spray.json.JsObject
import spray.json.JsString

object J2V8 extends App {
  // take a native json object
  val start = System.currentTimeMillis()
    val text: JsObject = JsObject("key" -> JsString("value"))
  // add it's stringified version to the runtime
    val nodeJs = NodeJS.createNodeJS()
    nodeJs.getRuntime.add("text", text.compactPrint)
    System.out.println(s"NodeVersion = ${nodeJs.getNodeVersion}")
    val r = nodeJs.getRuntime.executeIntegerScript("100+100")
    System.out.println(r)

  val runtime = V8.createV8Runtime();
  val result = runtime.executeIntegerScript(""
    + "var hello = 'hello, ';\n"
    + "var world = 'world!';\n"
    + "hello.concat(world).length;\n");
  System.out.println(result);

  val x = runtime.executeScript("var func = x => x * x; func(5);");
  System.out.println(x);
  
  runtime.executeVoidScript("""
    var person = {};
    var hockeyTeam = {
      name : 'WolfPack',
      players   : [],
      addPlayer : function(player) {
        this.players.push(player);
        return this.players.size;
      }
    };
    person.first = 'Ian';
    person['last'] = 'Bull';
    person.hockeyTeam = hockeyTeam;
  """)

  val person = runtime.getObject("person")
  val hockeyTeam = person.getObject("hockeyTeam")
  val player1 = new V8Object(runtime).add("name", "John")
  val player2 = new V8Object(runtime).add("name", "Chris")
  val players = new V8Array(runtime).push(player1).push(player2)
  hockeyTeam.add("players", players)
  player1.release
  player2.release
  players.release

  val executor = new V8Executor("10000+10000");
  executor.start();
  executor.join();
  val result2 = executor.getResult;
  System.out.println(result2)
  executor.shutdown

  System.out.println(hockeyTeam.getString("name"));
  person.release();
  hockeyTeam.release();

  val callback = new JavaVoidCallback() {
    def invoke(receiver: V8Object, parameters: V8Array) = {
      if (parameters.length() > 0) {
        val arg1 = parameters.get(0);
        System.out.println(arg1);
        if (arg1.isInstanceOf[Releasable]) {
          arg1.asInstanceOf[Releasable].release();
        }
      }
    }
  }
  runtime.registerJavaMethod(callback, "print");
  runtime.executeScript("print('您好，Smartnotes！');");

  class Console {
    def log(worker: V8Object, message: String) = System.out.println("[INFO] " + message)
    def error(worker: V8Object, message: String) = System.out.println("[ERROR] " + message)
  }
  val console = new Console();
  val v8Console = new V8Object(runtime);
  runtime.add("console", v8Console);
  v8Console.registerJavaMethod(console, "log", "log", Array[Class[_]](classOf[V8Object], classOf[String]), true)
  v8Console.registerJavaMethod(console, "error", "error", Array[Class[_]](classOf[V8Object], classOf[String]), true)
  v8Console.release();
  runtime.executeScript("console.log('hello, world');");
  runtime.executeScript("console.error('错误是成功的铺路石！');");

  runtime.release();
  System.out.println(System.currentTimeMillis() - start)
}