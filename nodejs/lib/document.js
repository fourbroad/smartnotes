/*
 * notes.js - module to provide CRUD db capabilities
 */

/*jslint         node    : true, continue : true,
 devel  : true, indent  : 2,    maxerr   : 50,
 newcap : true, nomen   : true, plusplus : true,
 regexp : true, sloppy  : true, vars     : false,
 white  : true
 */

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
	
	documentWrapper.replace(this.token, this.domainId, this.collectionId, this.id, docRaw, function(err, docData) {
	  if(err) return callback(err);
		  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  extend(self, docData);
	  callback(null, true);	  
	});
  },
  
  patch: function(patch, callback) {
	const
	  self = this;
		
	documentWrapper.patch(this.token, this.domainId, this.collectionId, this.id, patch, function(err, docData) {
	  if(err) return callback(err);
			  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  extend(self, docData);
	  callback(null, true);	  
	});
  },
  
  remove: function(callback) {
	documentWrapper.remove(this.token, this.domainId, this.collectionId, this.id, function(err, result) {
	  callback(err, result);	  
	});
  },

  getACL: function(callback) {
	documentWrapper.getACL(this.token, this.domainId, this.collectionId, this.id, function(err, acl) {
	  callback(err, acl);
	});
  },
  
  replaceACL: function(acl, callback) {
	documentWrapper.replaceACL(this.token, this.domainId, this.collectionId, this.id, acl, function(err, result) {
	  callback(err, result);
	});
  },
  
  patchACL: function(aclPatch, callback) {
	documentWrapper.patchACL(this.token, this.domainId, this.collectionId, this.id, aclPatch, function(err, result) {
	  callback(err, result);
	});
  },
  
  removePermissionSubject: function(acl, callback) {
	documentWrapper.removePermissionSubject(this.token, this.domainId, this.collectionId, this.id, acl, function(err, result) {
	  callback(err, result);
	});
  }
};

// ----------------- END INITIALIZE MODULE SCOPE VARIABLES ------------------

// ---------------- BEGIN PUBLIC METHODS ------------------

create = function(token, domainId, collectionId, docData) {
  var 
    document = Object.create(documentProto, {
      token:{
  	    value: token,
  	    configurable: false,
  	    writable: false,
  	    enumerable: false   	  
      },
      domainId:{
  	    value: domainId,
    	configurable: false,
  	    writable: false,
  	    enumerable: true   	  
      },
      collectionId:{
    	value: collectionId,
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
