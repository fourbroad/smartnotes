var http = require('http');  

var t1 = new Date().getTime();

// 用于请求的选项  
var options = {  
   host: 'localhost',  
   port: '3000',  
   path: ''    
};  
  
// 处理响应的回调函数  
var callback = function(response){  
   // 不断更新数据  
   var body = '';  
   response.on('data', function(data) {  
      body += data;  
   });  
     
   response.on('end', function() {  
      // 数据接收完成  
      console.log(body);  
   });  
}
// 向服务端发送请求  
var req = http.request(options, callback);  
req.end();

var t2 = new Date().getTime();
console.log(t2-t1);