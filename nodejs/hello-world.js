var express = require('express');
var app = express();

app.get('/', function (req, res) {
	new Domain("www.hello.com").login("fourbroad", "z4bb4z");
  res.send('Hello World!');
});

var server = app.listen(8000, function () {
  var host = server.address().address;
  var port = server.address().port;

  console.log('Example app listening at http://%s:%s', host, port);
});