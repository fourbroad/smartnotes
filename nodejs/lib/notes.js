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
  createUser: function(userId, userRaw, callback) {
	const
	  token = this.token;
	  
	userWrapper.create(token, userId, userRaw, function(err, userData) {
	  callback(err, err ? null : User.create(token, userId, userData));		
	});
  },

  getUser: function(userId, callback) {
	const
	  token = this.token;
	
	userWrapper.get(token, userId, function(err, userData) {
	  callback(err, err ? null : User.create(token, userId, userData));		
	});
  },
  
  logout: function(callback){
	domainWrapper.logout(this.token, function(err, result){
	  callback(err, result);
	});
  },

  joinDomain:function(domainName, userId, permission, callback){
	domainWrapper.joinDomain(this.token, domainName, userId, permission, function(err, result){
	  callback(err, result);
	});
  },

  quitDomain:function(domainName, userId, callback){
	domainWrapper.quitDomain(this.token, domainName, userId, function(err, result){
	  callback(err, result);
	});
  },

  createDomain: function(domainName, domainRaw, callback){
	const
	  token = this.token;
	
	domainWrapper.create(token, domainName, domainRaw, function(err, domainData){
	  callback(err, err ? null : Domain.create(token, domainName, domainData));
	});
  },

  getDomain: function(domainName, callback){
	const 
	  token = this.token;

	domainWrapper.get(token, domainName, function(err, domainData){
	  callback(err, err ? null : Domain.create(token, domainName, domainData));
	});
  }
};

// ----------------- END INITIALIZE MODULE SCOPE VARIABLES ------------------


// ---------------- BEGIN PUBLIC METHODS ------------------

login = function(username, password, callback){
  userWrapper.login(username, password, function(err, token){
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
  userWrapper.registerUser(userInfo, function(err, result){
	callback(err, result);
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
