/*
 * notes.js - module to provide CRUD db capabilities
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

const
  Domain = require('./domain'),
  User = require('./user'),  
  extend = require('extend'),
  domainWrapper = new __DomainWrapper(),
  userWrapper = new __UserWrapper();

var
  clientProto, login, registerUser, isValidToken;

// ------------- END MODULE SCOPE VARIABLES ---------------

// ---------------- BEGIN INITIALIZE MODULE SCOPE VARIABLES -----------------

clientProto = {
		
  createUser: function(userRaw, callback) {
	const
	  token = this.token;
	  
	userWrapper.create(token, userRaw, function(err, userData) {
	  callback(err, err ? null : User.create(token, userData));		
	});
  },

  getUser: function(userId, callback) {
	const
	  token = this.token;
	
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
  }
};

// ----------------- END INITIALIZE MODULE SCOPE VARIABLES ------------------


// ---------------- BEGIN PUBLIC METHODS ------------------

login = function(userId, password, callback){
  userWrapper.login(userId, password, function(err, token){
	var
	  client;
	
	if(err) return callback(err);

	client = Object.create(clientProto, {
	  token:{
	    value: token,
	    configurable: false,
	    writable: false,
	    enumerable: false
	  }
	});
	callback(null, client);
  });
};

registerUser = function(userInfo, callback){
  userWrapper.register(userInfo, function(err, userData){
	callback(err, userData);
  });
};

isValidToken = function(token, callback){
  userWrapper.isValidToken(token, function(err, result){
	callback(err, result);
  });
};

module.exports = {
  login : login,
  registerUser: registerUser,
  isValidToken : isValidToken
};

// ----------------- END PUBLIC METHODS -----------------
