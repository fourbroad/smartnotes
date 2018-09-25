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
  utils = require('./utils');
// ------------- END MODULE SCOPE VARIABLES ---------------

// ---------------- BEGIN PUBLIC METHODS ------------------
router.get('/', function(request, response){
  response.redirect( '/index.html' );
});

//router.post("/_login", function(req, res){
//	var 
//	  name = req.body.name,
//	  password = req.body.password;
//	
//	req.domain.login(name, password, function(err, result){
//		if(err) return res.status(err.code).json(err);
//		res.json(result);
//	});
//});
//
//router.get("/_logout", function(req,res){
//	var 
//	  hostName = utils.getHostName(req);
//	
//	req.domain.logout(function(err, result){
//		if(err) return res.status(err.code).json(err);			
//		res.json({message:"logout"});	
//	});
//});
//
//router.get("/secret", function(req, res){
//	req.domain.isValidToken(function(err, result){
//		if(err) return res.status(err.code).json(err);
//		res.json(req.domain);	
//	});
//});

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
