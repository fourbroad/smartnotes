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

var
  clientProto, login, isValidToken,
  domainWrapper = new __DomainWrapper__();

// ------------- END MODULE SCOPE VARIABLES ---------------

// ---------------- BEGIN INITIALIZE MODULE SCOPE VARIABLES -----------------

clientProto = {
  registerUser: function(userName, password, callback){
	domainWrapper.register(this.token, userName, password, function(err, result){
	  if(err) return callback(err);
	  callback(null, result);
	});
  },
  joinDomain = function(domainName, userName, permission, callback){
	domainWrapper.joinDomain(domainName, this.token, userName, permission, function(err, result){
	  if(err) return callback(err);
	  callback(null, result);	  
	});
  },		
  logout: function(callback){
	domainWrapper.logout(this.token, function(err, result){
	  if(err) return callback(err);
	  callback(null, result);
	});
  },
  createDomain: function(domainName, callback){
	domainWrapper.createDomain(domainName, this.token, function(err, domain){
	  if(err) return callback(err);
	  callback(null, domain);	  
	});
  },
  getDomain = function(domainName, callback){
	domainWrapper.getDomain(domainName, this.token, function(err, domain){
	  if(err) return callback(err);
	  callback(null, domain);	  
	});
  },  
  replaceDomain = function(domainName, content, callback){
	domainWrapper.replaceDomain(domainName, this.token, content, function(err, domain){
	  if(err) return callback(err);
	  callback(null, domain);	  
	});
  },  
  patchDomain = function(domainName, patch, callback){
	domainWrapper.patchDomain(domainName, this.token, patch, function(err, domain){
	  if(err) return callback(err);
	  callback(null, domain);	  
	});
  },  
  deleteDomain = function(domainName, callback){
	domainWrapper.deleteDomain(domainName, this.token, function(err, result){
	  if(err) return callback(err);
	  callback(null, result);	  
	});
  },  
  authorizeDomain = function(domainName, acl, callback){
	domainWrapper.authorizeDomain(domainName, this.token, acl, function(err, result){
	  if(err) return callback(err);
	  callback(null, result);	  
	});
  } 
};

// ----------------- END INITIALIZE MODULE SCOPE VARIABLES ------------------


// ---------------- BEGIN PUBLIC METHODS ------------------

login = function(username, password, callback){
  domainWrapper.login(username, password, function(err, token){
	if(err) return callback(err);
	
	client = Object.create(clientProto);
	client.token     = token;
	callback(null, client);
  });
};

isValidToken = function(token, callback){
  domainWrapper.isValidToken(token, function(err, result){
	if(err) return callback(err);
	
	callback(null, result);
  });
};

module.exports = {
  login : login,
  isValidToken : isValidToken
};

// ----------------- END PUBLIC METHODS -----------------
