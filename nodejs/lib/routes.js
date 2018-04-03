/*
 * routes.js - module to provide routing
*/

/*jslint         node    : true, continue : true,
  devel  : true, indent  : 2,    maxerr   : 50,
  newcap : true, nomen   : true, plusplus : true,
  regexp : true, sloppy  : true, vars     : false,
  white  : true
*/
/*global */

// ------------ BEGIN MODULE SCOPE VARIABLES --------------
'use strict';
var
  router = require('express').Router(),
  crud        = require( './crud' ),
  makeMongoId = crud.makeMongoId,
  jwt = require('jsonwebtoken'),
  
  users = [{
	  id: 1,
	  name: 'jonathanmh',
	  password: '%2yx4'
	},{
	  id: 2,
	  name: 'test',
	  password: 'test'
	}];
// ------------- END MODULE SCOPE VARIABLES ---------------

// ---------------- BEGIN PUBLIC METHODS ------------------
router.get('/', function(request, response){
	response.redirect( '/spa.html' );
});

router.post("/_login", function(req, res){
	var 
	  name = req.body.name,
	  password = req.body.password,
	  hostname = req.headers.host.replace(/:.*$/,"");
	
	// usually this would be a database call:
	var user = users.find(u => u.name == name);
	if(!user){
		res.status(401).json({message:"no such user found"});
	}
	
	if(user.password === req.body.password) {
		// from now on we'll identify the user by the id and the id is the only
		// personalized value that goes into our token
	    var payload = {id: user.id};
	    var token = jwt.sign(payload, 'z4bb4z');
	    res.json({message: "ok", token: token});
	} else {
		res.status(401).json({message:"passwords did not match"});
    }
});

router.get("/_logout", function(req,res){
	var hostname = req.headers.host.replace(/:.*$/,"");
	res.writeHead(200, {'Content-Type': 'text/plain'});
	res.json({message:"logout"});
});

router.get("/secret", function(req, res){
	res.json("Success! You can not see this without a token");
});

router.all('/:obj_type/*?', function(request, response, next){
  response.contentType('json');
  next();
});

router.get('/:obj_type/list', function(request, response){
  crud.read(request.params.obj_type,{}, {}, function(map_list) { 
	  response.send( map_list ); 
  });
});

router.post('/:obj_type/create', function(request, response){
  crud.construct(request.params.obj_type, request.body, function(result_map){ 
	  response.send( result_map ); 
  });
});

router.get('/:obj_type/read/:id', function(request, response){
  crud.read(request.params.obj_type, {_id: makeMongoId(request.params.id)}, {}, function(map_list){ 
	  response.send( map_list ); 
  });
});

router.post('/:obj_type/update/:id', function(request, response){
  crud.update(request.params.obj_type, {_id:makeMongoId(request.params.id)}, request.body, function(result_map){
	  response.send( result_map ); 
  });
});

router.get('/:obj_type/delete/:id', function(request, response){
  crud.destroy(request.params.obj_type, {_id:makeMongoId(request.params.id)}, function(result_map){
	  response.send( result_map ); 
  });
});

module.exports = router;
// ----------------- END PUBLIC METHODS -------------------
