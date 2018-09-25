/*
 * notes.js - module to provide CRUD db capabilities
 */

/*jslint         node    : true, continue : true,
  devel  : true, indent  : 2,    maxerr   : 50,
  newcap : true, nomen   : true, plusplus : true,
  regexp : true, sloppy  : true, vars     : false,
  white  : true
 */

/*------------ BEGIN MODULE SCOPE VARIABLES --------------*/

'use strict';

const
  Domain = require('./domain'),
  User = require('./user'),  
  extend = require('extend'),
  utils = require('./utils'),
  domainWrapper = new __DomainWrapper(),
  userWrapper = new __UserWrapper();

var
  clientProto, _createClient, login, connect;

/*------------- END MODULE SCOPE VARIABLES ---------------*/

/*---------------- BEGIN INITIALIZE MODULE SCOPE VARIABLES -----------------*/

clientProto = {
  registerUser: function(userInfo, callback){
    userWrapper.register(userInfo, function(err, userData){
	  callback(err, userData);
	});
  },
		
  createUser: function(userRaw, callback) {
	const token = this.token;
	userWrapper.create(token, userRaw, function(err, userData) {
	  callback(err, err ? null : User.create(token, userData));		
	});
  },

  getUser: function() {
	const token = this.token;
	var userId, callback;

	if(arguments.length == 1 && typeof arguments[0] == 'function'){
	  callback = arguments[0];
	} else if(arguments.length == 2 && typeof arguments[1] == 'function'){
	  userId = arguments[0];
	  callback = arguments[1];
	} else {
	  throw utils.makeError('Error', 'Number or type of Arguments is not correct!', arguments);
	}
	
	userWrapper.get(token, userId, function(err, userData) {
	  callback(err, err ? null : User.create(token, userData));		
	});
  },

  logout: function(callback){
	userWrapper.logout(this.token, function(err, result){
	  callback(err, result);
	});
  },

  joinDomain:function(domainId, userId, permission, callback){
	domainWrapper.join(this.token, domainId, userId, permission, function(err, result){
	  callback(err, result);
	});
  },

  quitDomain:function(domainId, userId, callback){
	domainWrapper.quit(this.token, domainId, userId, function(err, result){
	  callback(err, result);
	});
  },

  createDomain: function(domainId, domainRaw, callback){
	const
	  token = this.token;
	
	domainWrapper.create(token, domainId, domainRaw, function(err, domainData){
	  callback(err, err ? null : Domain.create(token, domainData));
	});
  },

  getDomain: function(domainId, callback){
	const 
	  token = this.token;

	domainWrapper.get(token, domainId, function(err, domainData){
	  callback(err, err ? null : Domain.create(token, domainData));
	});
  },
  
  isValidToken: function(token, callback){
    userWrapper.isValidToken(token, function(err, result){
	  callback(err, result);
	});
  }  
};

_createClient = function(token, currentUser, clientProto){
  return Object.create(clientProto, {
	token: {
	  value: token,
	  configurable: false,
	  writable: false,
	  enumerable: false
	},
    currentUser: {
	  value: currentUser,
	  configurable: false,
	  writable: false,
	  enumerable: false
	}
  });  
};
  
// ----------------- END INITIALIZE MODULE SCOPE VARIABLES ------------------


// ---------------- BEGIN PUBLIC METHODS ------------------

login = function(userId, password, callback){
  userWrapper.login(userId, password, function(err, token){
	if(err) return callback(err);
	userWrapper.get(token, userId, function(err, userData){
	  callback(null, _createClient(token, User.create(token, userData), clientProto));
	});
  });
};

connect = function(){
  var token, callback;
  if(arguments.length == 1 && typeof arguments[0] == 'function'){
	callback = arguments[0];
  } else if(arguments.lengtih == 2 && typeof arguments[1] == 'function'){
    token = arguments[0];
    callback = arguments[1];
  } else {
    throw utils.makeError('Error', 'Number or type of arguments is not correct!', arguments);
  }

  if(token){
    callback(null, _createClient(token, User.create(token, userData), clientProto));
  } else {
	callback(null, _createClient(token, User.create(token, {id: 'anonymous', name: 'Anonymous'}), clientProto));
  }
};

module.exports = {
  login        : login,
  connect      : connect
};

// ----------------- END PUBLIC METHODS -----------------
