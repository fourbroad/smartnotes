package j2v8

import java.util.ArrayList
import java.util.List

import com.eclipsesource.v8.JavaCallback
import com.eclipsesource.v8.V8
import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Object
import com.eclipsesource.v8.utils.V8ObjectUtils

object ThreadMain extends App {
  val sortAlgorithm = """
	  function merge(left, right){
			  var result  = [],
			  il      = 0, 
			  ir      = 0; 
			  while (il < left.length && ir < right.length){
			    if (left[il] < right[ir]){ 
			      result.push(left[il++]); 
			    } else {
			      result.push(right[ir++]); 
			    } 
			  }
			  return result.concat(left.slice(il)).concat(right.slice(ir)); 
			};
			
			function sort(data) { 
			  if ( data.length === 1 ) { 
			    return [data[0]];
			  } else if (data.length === 2 ) {
			    if ( data[1] < data[0] ) {
			      return [data[1],data[0]]; 
			    } else { 
			      return data;
			    } 
			  }
			  var mid = Math.floor(data.length / 2); 
			  var first = data.slice(0, mid);
			  var second = data.slice(mid); 
			  return merge(_sort( first ), _sort( second ) ); 
			};
	  """
  new ThreadMain().testMultiV8Threads
}

class ThreadMain {
  import ThreadMain._

  var mergeSortResults = new ArrayList[Object]();

  class Sort extends JavaCallback {
    var result: List[_ >: Object] = null;

    def invoke(receiver: V8Object, parameters: V8Array): Object = {
      val data = V8ObjectUtils.toList(parameters);
      val t = new Thread(new Runnable() {
        def run = {
          val runtime = V8.createV8Runtime();
          runtime.registerJavaMethod(new Sort(), "_sort");
          runtime.executeVoidScript(sortAlgorithm);
          val parameters = V8ObjectUtils.toV8Array(runtime, data);
          val _result = runtime.executeArrayFunction("sort", parameters);
          result = V8ObjectUtils.toList(_result);
          _result.release();
          parameters.release();
          runtime.release();
        }
      })
      t.start();
      try {
        t.join();
      } catch {
        case e: InterruptedException => throw new RuntimeException(e);
      }

      V8ObjectUtils.toV8Array(parameters.getRuntime, result);
    }
  }

  def testMultiV8Threads = {
    val totalThreads = 4;
    var threads = Set[Thread]();

    for (i <- 0 until totalThreads) {
      val t = new Thread(new Runnable() {
        def run() = {
          val v8 = V8.createV8Runtime();
          v8.registerJavaMethod(new Sort(), "_sort");
          v8.executeVoidScript(sortAlgorithm);
          val data = new V8Array(v8);
          val max = 100;
          for (i <- 0 until max) {
            data.push(max - i);
          }
          val parameters = new V8Array(v8).push(data);
          val result = v8.executeArrayFunction("sort", parameters);
          threads.synchronized {
            mergeSortResults.add(V8ObjectUtils.toList(result));
          }
          result.release();
          parameters.release();
          data.release();
          v8.release();
        }
      });
      threads += t
    }

    for (thread <- threads) {
      thread.start();
    }

    for (thread <- threads) {
      thread.join();
    }

    for (i <- 0 until totalThreads) {
      val result = mergeSortResults.get(i).asInstanceOf[List[Integer]];
      for (j <- 0 until result.size) {
        System.out.print(result.get(j) + " ");
      }
      System.out.println();
    }
  }

}
