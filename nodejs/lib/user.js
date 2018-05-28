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
  extend = require('extend'),
  userWrapper = new __UserWrapper();

var
  userProto, create;

// ------------- END MODULE SCOPE VARIABLES ---------------

// ---------------- BEGIN INITIALIZE MODULE SCOPE VARIABLES -----------------

userProto = {
  replace: function(userRaw, callback) {
	const
	  self = this;
		
	userWrapper.replace(this.token, this.domainName, this.collectionName, this.id, userRaw, function(err, userData) {
	  if(err) return callback(err);
			  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) delete self[key];
	  }

	  extend(self, userData);
	  callback(null, true);	  
	});
  },
  
  patch: function(patch, callback) {
	const
	  self = this;
			
	userWrapper.patch(this.token, this.domainName, this.collectionName, this.id, patch, function(err, userData) {
	  if(err) return callback(err);
				  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) delete self[key];
	  }

	  extend(self, userData);
	  callback(null, true);	  
	});
  },
  
  remove: function(callback) {
	var
	  sef = this;

	userWrapper.remove(this.token, this.domainName, this.collectionName, this.id, function(err, result) {
	  self.removed = result ? true : false;
	  callback(err, result);	  
	});
  },
  
  resetPassword: function(uid, newPassword, callback) {
	userWrapper.resetPassword(this.token, this.domainName, this.collectionName, this.id, newPassword, function(err, user) {
	  callback(err, user);
	});
  },
  
  setACL: function(acl, callback) {
	userWrapper.setACL(this.token, this.domainName, this.collectionName, this.id, acl, function(err, result) {
	  callback(err, result);
	});
  },
  
  removePermissionSubject: function(acl, callback) {
	userWrapper.removePermissionSubject(this.token, this.domainName, this.collectionName, this.id, acl, function(err, result) {
	  callback(err, result);
	});
  }
};

// ----------------- END INITIALIZE MODULE SCOPE VARIABLES ------------------


// ---------------- BEGIN PUBLIC METHODS ------------------

create = function(token, userId, userData) {
  var
    user = Object.create(userProto, {
      token: {
	    value: token,
	    configurable: false,
	    writable: false,
	    enumerable: false   	  
      },
      id: {
  	    value: userId,
  	    configurable: false,
  	    writable: false,
  	    enumerable: true
      }
    });

  return extend(true, user, userData)
};

module.exports = {
  create: create
};

// ----------------- END PUBLIC METHODS -----------------
