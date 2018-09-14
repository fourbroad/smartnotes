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
  viewWrapper = new __ViewWrapper();

var
  viewProto, create;

// ------------- END MODULE SCOPE VARIABLES ---------------

// ---------------- BEGIN INITIALIZE MODULE SCOPE VARIABLES -----------------

viewProto = {
  replace: function(viewRaw, callback) {
	const
	  self = this;
	
	viewWrapper.replace(this.token, this.domainId, this.id, viewRaw, function(err, viewData) {
	  if(err) return callback(err);
		  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  extend(self, viewData);
	  callback(null, true);	  
	});
  },
  
  patch: function(patch, callback) {
	const
	  self = this;
		
	viewWrapper.patch(this.token, this.domainId, this.id, patch, function(err, viewData) {
	  if(err) return callback(err);
			  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  extend(self, viewData);
	  callback(null, true);	  
	});
  },
  
  remove: function(callback) {
	viewWrapper.remove(this.token, this.domainId, this.id, function(err, result) {
	  callback(err, result);	  
	});
  },

  getACL: function(callback) {
	viewWrapper.getACL(this.token, this.domainId, this.id, function(err, acl) {
	  callback(err, acl);
	});
  },
  
  replaceACL: function(acl, callback) {
	viewWrapper.replaceACL(this.token, this.domainId, this.id, acl, function(err, result) {
	  callback(err, result);
	});
  },
  
  patchACL: function(aclPatch, callback) {
	viewWrapper.patchACL(this.token, this.domainId, this.id, aclPatch, function(err, result) {
	  callback(err, result);
	});
  },
  
  removePermissionSubject: function(acl, callback) {
	viewWrapper.removePermissionSubject(this.token, this.domainId, this.id, acl, function(err, result) {
	  callback(err, result);
	});
  },
  
  findDocuments: function(query, callback) {
    viewWrapper.findDocuments(this.token, this.domainId, this.id, query, function(err, docsData) {
	  callback(err, docsData);
	});
  },
	  
  refresh: function(callback) {
    viewWrapper.refresh(this.token, this.domainId, this.id, function(err, result){
	  callback(err, result);	  
	});
  }  
};

// ----------------- END INITIALIZE MODULE SCOPE VARIABLES ------------------

// ---------------- BEGIN PUBLIC METHODS ------------------

create = function(token, domainId, viewData) {
  var 
    view = Object.create(viewProto, {
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
      }
    });

  return extend(true, view, viewData)
};

module.exports = {
  create: create
};

// ----------------- END PUBLIC METHODS -----------------
