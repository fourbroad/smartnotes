const 
  express = require('express'),
  notes = require('./lib/notes.js'),
  app = express();

var
  client, localhostDomain;

process.on('SIGWINCH', () => {
    asyncCallback();
});

app.get('/', function (req, res) {
  res.send('Hello World!');
});

app.get('/login', function (req, res) {
  notes.login("administrator","!QAZ)OKM", function(err, c){
	client = c
	res.send(err||c);
  });
});

app.get('/registerUser', function (req, res) {
  client.registerUser('fourbroad', 'z4bb4z', {realName: 'fourbroad'}, function(err, user){
	res.send(err||user);
  });
});

app.get('/joinDomain', function (req, res) {
  client.joinDomain('localhost', 'fourbroad', {}, function(err, user){
	res.send(err||user);
  });
});

app.get('/quitDomain', function (req, res) {
  client.quitDomain('localhost', 'fourbroad', function(err, user){
	res.send(err||user);
  });
});

app.get('/getDomain', function (req, res) {
  client.getDomain('localhost',function(err, domain){
	localhostDomain = domain;
	res.send(err||domain);
  });
});

app.get('/replaceDomain', function (req, res) {
  client.replaceDomain('localhost',{hello:"world"},function(err, result){
	res.send(err||result);
  });
});

app.get('/patchDomain', function (req, res) {
  client.patchDomain('localhost',[{op:"add",path:"/hello2",value:"world2"}],function(err, result){
	res.send(err||result);
  });
});

app.get('/gc', function (req, res) {
  client.garbageCollection('localhost',function(err, result){
	res.send(err||result);
  });
});

app.get('/listCollections', function (req, res) {
  localhostDomain.listCollections(function(err, collections){
	res.send(err||collections);
  });
});

app.get('/getCollection', function (req, res) {
  localhostDomain.getCollection('profiles',function(err, collection){
	res.send(err||collection);
  });
});

app.get('/getDocument', function (req, res) {
  localhostDomain.getCollection('profiles',function(err, collection){
	if(err) return callback(err)
	collection.getDocument("fourbroad", function(err2, doc){
		res.send(err2||doc)
	});
  });
});

app.get('/findDocuments', function (req, res) {
  localhostDomain.getCollection('profiles',function(err, collection){
	if(err) return callback(err)
	collection.findDocuments({}, function(err2, docs){
	  res.send(err2||docs)
	});
  });
});

app.get('/logout', function (req, res) {
  client.logout(function(err, result){
	res.send(err||result);
  });
});

var server = app.listen(8000, function () {
  var host = server.address().address;
  var port = server.address().port;

  console.log('Example app listening at http://%s:%s', host, port);
});