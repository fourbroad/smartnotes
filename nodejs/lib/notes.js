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
  extend = require('extend'),
  domainWrapper = new __DomainWrapper(),
  userWrapper = new __UserWrapper();

var
  clientProto, login, isValidToken;

// ------------- END MODULE SCOPE VARIABLES ---------------

// ---------------- BEGIN INITIALIZE MODULE SCOPE VARIABLES -----------------

clientProto = {
  createUser : function(uid, userInfo, callback) {
	userWrapper.createUser(this.token, uid, userInfo, function(err, user) {
	  callback(err, user);
	});
  },
  getUser : function(uid, callback) {
	userWrapper.getUser(this.token, uid, function(err, user) {
	  callback(err, user);
	});
  },
  replaceUser : function(uid, userInfo, callback) {
	userWrapper.replaceUser(this.token, uid, userInfo, function(err, user) {
	  callback(err, user);
	});
  },
  patchUser : function(uid, patch, callback) {
	userWrapper.patchUser(this.token, uid,  patch, function(err, user) {
	  callback(err, user);
	});
  },
  deleteUser : function(uid, callback) {
	userWrapper.deleteUser(this.token, uid, function(err, result) {
	  callback(err, result);
    });
  },
  resetPassword : function(uid, newPassword, callback) {
	userWrapper.resetPassword(this.token, uid, newPassword, function(err, user) {
	  callback(err, user);
	});
  },
  authorizeUser : function(uid, acl, callback) {
	userWrapper.authorizeUser(this.token, uid, acl, function(err, result) {
	  callback(err, result);
	});
  },
  logout: function(callback){
	domainWrapper.logout(this.token, function(err, result){
	  callback(err, result);
	});
  },

  joinDomain:function(domainName, userName, permission, callback){
	domainWrapper.joinDomain(this.token, domainName, userName, permission, function(err, result){
	  callback(err, result);	  
	});
  },
  quitDomain:function(domainName, userName, callback){
	domainWrapper.quitDomain(this.token, domainName, userName, function(err, result){
	  callback(err, result);	  
	});
  },
  createDomain: function(domainName, callback){
	domainWrapper.createDomain(this.token, domainName, function(err, domain){
	  callback(err, domain);	  
	});
  },
  getDomain: function(domainName, callback){
	const token = this.token;
	domainWrapper.getDomain(token, domainName, function(err, domainData){
	  if(err) return callback(err);
	  Domain.newDomain(token, domainName, function(err2, domain){
		callback(err2, extend(true, domain, domainData, {domainWrapper: domainWrapper}));
	  });
	});
  },
  replaceDomain: function(domainName, content, callback){
	domainWrapper.replaceDomain(this.token, domainName, content, function(err, domain){
	  callback(err, domain);	  
	});
  },
  patchDomain: function(domainName, patch, callback){
	domainWrapper.patchDomain(this.token, domainName, patch, function(err, domain){
	  callback(err, domain);	  
	});
  },
  deleteDomain: function(domainName, callback){
	domainWrapper.deleteDomain(this.token, domainName, function(err, result){
	  callback(err, result);	  
	});
  },
  authorizeDomain: function(domainName, acl, callback){
	domainWrapper.authorizeDomain(this.token, domainName, acl, function(err, result){
	  callback(err, result);	  
	});
  },
  garbageCollection: function(domainName, callback){
	domainWrapper.garbageCollection(this.token, domainName, function(err, result){
	  callback(err, result);	  
	});
  }
};

// ----------------- END INITIALIZE MODULE SCOPE VARIABLES ------------------


// ---------------- BEGIN PUBLIC METHODS ------------------

login = function(username, password, callback){
  userWrapper.login(username, password, function(err, token){
	if(err) return callback(err);

	var client = Object.create(clientProto);
	client.token     = token;
	callback(null, client);
  });
};

registerUser = function(userInfo, callback){
  userWrapper.registerUser(userInfo, function(err, result){
	callback(err, result);
  });
};

isValidToken = function(token, callback){
  domainWrapper.isValidToken(token, function(err, result){
	callback(err, result);
  });
};

module.exports = {
  login : login,
  isValidToken : isValidToken
};

// ----------------- END PUBLIC METHODS -----------------
