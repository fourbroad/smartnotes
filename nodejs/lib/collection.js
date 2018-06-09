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
  Document = require('./document'),
  documentWrapper = new __DocumentWrapper(),
  collectionWrapper = new __CollectionWrapper();

var
  collectionProto, create;

// ------------- END MODULE SCOPE VARIABLES ---------------

// ---------------- BEGIN INITIALIZE MODULE SCOPE VARIABLES -----------------

collectionProto = {
  createDocument: function(docId, docRaw, callback) {
	const
	  token = this.token,
	  domainId = this.domainId,
	  collectionId = this.id;
	  
	documentWrapper.create(token, domainId, collectionId, docId, docRaw, function(err, docData) {
	  callback(err, err ? null : Document.create(token, domainId, collectionId, docData));
	});
  },
  
  getDocument: function(docId, callback) {
	const
	  token = this.token,
	  domainId = this.domainId,
	  collectionId = this.id;

	documentWrapper.get(token, domainId, collectionId, docId, function(err, docData) {
	  callback(err, err ? null : Document.create(token, domainId, collectionId, docData));
	});
  },

  replace: function(collectionRaw, callback) {
	const
	  self = this,
	  token = this.token,
	  domainId = this.domainId,
	  collectionId = this.id;

	collectionWrapper.replace(token, domainId, collectionId, collectionRaw, function(err, collectionData) {
	  if(err) return callback(err);
		  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  extend(self, collectionData);
	  callback(null, true);	  
	});
  },
  
  patch: function(patch, callback) {
	const
	  self = this,
	  token = this.token,
	  domainId = this.domainId,
	  collectionId = this.id;
		
	collectionWrapper.patch(token, domainId, collectionId, patch, function(err, collectionData) {
	  if(err) return callback(err);
			  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  extend(self, collectionData);
	  callback(null, true);	  
    });
  },
  
  remove: function(callback) {
	collectionWrapper.remove(this.token, this.domainId, this.id, function(err, result){
	  callback(err, result);	  
	});
  },

  getACL: function(callback) {
	collectionWrapper.getACL(this.token, this.domainId, this.id, function(err, acl) {
	  callback(err, acl);
	});
  },
  
  replaceACL: function(acl, callback) {
	collectionWrapper.replaceACL(this.token, this.domainId, this.id, acl, function(err, result) {
	  callback(err, result);
	});
  },
	  
  patchACL: function(aclPatch, callback) {
	collectionWrapper.patchACL(this.token, this.domainId, this.id, aclPatch, function(err, result) {
	  callback(err, result);
	});
  },
  
  removePermissionSubject: function(acl, callback) {
	collectionWrapper.removePermissionSubject(this.token, this.domainId, this.id, acl, function(err, result) {
	  callback(err, result);
	});
  },
  
  findDocuments: function(query, callback) {
	collectionWrapper.findDocuments(this.token, this.domainId, this.id, query, function(err, docsData) {
	  callback(err, docsData);
	});
  }
};

// ----------------- END INITIALIZE MODULE SCOPE VARIABLES ------------------

// ---------------- BEGIN PUBLIC METHODS ------------------

create = function(token, domainId, collectionData) {
  var 
    collection = Object.create(collectionProto, {
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

  return extend(true, collection, collectionData)
};

module.exports = {
  create: create
};

// ----------------- END PUBLIC METHODS -----------------