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
  isAnonymous: function(){
	return this.id === 'anonymous';
  },
		
  replace: function(userRaw, callback) {
	const
	  self = this;
	
	userWrapper.replace(this.token, this.id, userRaw, function(err, userData) {
	  if(err) return callback(err);
			  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  extend(self, userData);
	  callback(null, true);	  
	});
  },
  
  patch: function(patch, callback) {
	const
	  self = this;
			
	userWrapper.patch(this.token, this.id, patch, function(err, userData) {
	  if(err) return callback(err);
				  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  extend(self, userData);
	  callback(null, true);	  
	});
  },
  
  remove: function(callback) {
	var
	  sef = this;

	userWrapper.remove(this.token, this.id, function(err, result) {
	  callback(err, result);	  
	});
  },
  
  resetPassword: function(newPassword, callback) {
	userWrapper.resetPassword(this.token, this.id, newPassword, function(err, result) {
	  callback(err, result);
	});
  },

  getACL: function(callback) {
	userWrapper.getACL(this.token, this.id, function(err, acl) {
	  callback(err, acl);
	});
  },

  replaceACL: function(acl, callback) {
	userWrapper.replaceACL(this.token, this.id, acl, function(err, result) {
	  callback(err, result);
	});
  },

  patchACL: function(aclPatch, callback) {
	userWrapper.patchACL(this.token, this.id, aclPatch, function(err, result) {
	  callback(err, result);
	});
  },
  
  removePermissionSubject: function(acl, callback) {
	userWrapper.removePermissionSubject(this.token, this.id, acl, function(err, result) {
	  callback(err, result);
	});
  }
};

// ----------------- END INITIALIZE MODULE SCOPE VARIABLES ------------------


// ---------------- BEGIN PUBLIC METHODS ------------------

create = function(token, userData) {
  var
    user = Object.create(userProto, {
      token: {
	    value: token,
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
