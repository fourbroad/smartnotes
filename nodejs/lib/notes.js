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
  domainWrapper = new __DomainWrapper();

var
  clientProto, login, isValidToken;

// ------------- END MODULE SCOPE VARIABLES ---------------

// ---------------- BEGIN INITIALIZE MODULE SCOPE VARIABLES -----------------

clientProto = {
  registerUser: function(userName, password, {}, callback){
	domainWrapper.registerUser(this.token, userName, password, {}, function(err, result){
	  callback(err, result);
	});
  },
  joinDomain:function(domainName, userName, permission, callback){
	domainWrapper.joinDomain(domainName, this.token, userName, permission, function(err, result){
	  callback(err, result);	  
	});
  },
  quitDomain:function(domainName, userName, callback){
	domainWrapper.quitDomain(domainName, this.token, userName, function(err, result){
	  callback(err, result);	  
	});
  },
  logout: function(callback){
	domainWrapper.logout(this.token, function(err, result){
	  callback(err, result);
	});
  },
  createDomain: function(domainName, callback){
	domainWrapper.createDomain(domainName, this.token, function(err, domain){
	  callback(err, domain);	  
	});
  },
  getDomain: function(domainName, callback){
	const token = this.token;
	domainWrapper.getDomain(domainName, token, function(err, domainData){
	  if(err) return callback(err);
	  Domain.newDomain(domainName, token, function(err2, domain){
		callback(err2, extend(true, domain, domainData, {domainWrapper: domainWrapper}));
	  });
	});
  },
  replaceDomain: function(domainName, content, callback){
	domainWrapper.replaceDomain(domainName, this.token, content, function(err, domain){
	  callback(err, domain);	  
	});
  },
  patchDomain: function(domainName, patch, callback){
	domainWrapper.patchDomain(domainName, this.token, patch, function(err, domain){
	  callback(err, domain);	  
	});
  },
  deleteDomain: function(domainName, callback){
	domainWrapper.deleteDomain(domainName, this.token, function(err, result){
	  callback(err, result);	  
	});
  },
  authorizeDomain: function(domainName, acl, callback){
	domainWrapper.authorizeDomain(domainName, this.token, acl, function(err, result){
	  callback(err, result);	  
	});
  },
  garbageCollection: function(domainName, callback){
	domainWrapper.garbageCollection(domainName, this.token, function(err, result){
	  callback(err, result);	  
	});
  }
};

// ----------------- END INITIALIZE MODULE SCOPE VARIABLES ------------------


// ---------------- BEGIN PUBLIC METHODS ------------------

login = function(username, password, callback){
  domainWrapper.login(username, password, function(err, token){
	if(err) return callback(err);

	var client = Object.create(clientProto);
	client.token     = token;
	callback(null, client);
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
