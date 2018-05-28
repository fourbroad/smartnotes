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
  documentWrapper = new __DocumentWrapper();

var
  documentProto, create;

// ------------- END MODULE SCOPE VARIABLES ---------------

// ---------------- BEGIN INITIALIZE MODULE SCOPE VARIABLES -----------------

documentProto = {
  replace: function(docRaw, callback) {
	const
	  self = this;
	
	documentWrapper.replace(this.token, this.domainName, this.collectionName, this.id, docRaw, function(err, docData) {
	  if(err) return callback(err);
		  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) delete self[key];
	  }

	  extend(self, docData);
	  callback(null, true);	  
	});
  },
  
  patch: function(patch, callback) {
	const
	  self = this;
		
	documentWrapper.patch(this.token, this.domainName, this.collectionName, this.id, patch, function(err, docData) {
	  if(err) return callback(err);
			  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) delete self[key];
	  }

	  extend(self, docData);
	  callback(null, true);	  
	});
  },
  
  remove: function(callback) {
	var
	  sef = this;
			
	documentWrapper.remove(this.token, this.domainName, this.collectionName, this.id, function(err, result) {
	  self.removed = result ? true : false;
	  callback(err, result);	  
	});
  },
  
  setACL: function(acl, callback) {
	documentWrapper.setACL(this.token, this.domainName, this.collectionName, this.id, acl, function(err, result) {
	  callback(err, result);
	});
  },
  
  removePermissionSubject: function(acl, callback) {
	documentWrapper.removePermissionSubject(this.token, this.domainName, this.collectionName, this.id, acl, function(err, result) {
	  callback(err, result);
	});
  }
};

// ----------------- END INITIALIZE MODULE SCOPE VARIABLES ------------------

// ---------------- BEGIN PUBLIC METHODS ------------------

create = function(token, domainName, collectionName, docId, docData) {
  var 
    document = Object.create(documentProto, {
      token:{
  	    value: token,
  	    configurable: false,
  	    writable: false,
  	    enumerable: false   	  
      },
      domainName:{
  	    value: domainName,
    	configurable: false,
  	    writable: false,
  	    enumerable: true   	  
      },
      collectionName:{
    	value: collectionName,
      	configurable: false,
    	writable: false,
    	enumerable: true   	  
      },
      id:{
    	value: docId,
    	configurable: false,
    	writable: false,
    	enumerable: true
      }
    });

  return extend(true, document, docData)
};

module.exports = {
  create: create
};

// ----------------- END PUBLIC METHODS -----------------
