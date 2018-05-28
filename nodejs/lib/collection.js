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
  createDocument : function(docId, docRaw, callback) {
	const
	  token = this.token,
	  domainName = this.domainName,
	  collectionName = this.name;
	  
	documentWrapper.create(token, domainName, collectionName, docId, docRaw, function(err, docData) {
	  callback(err, err ? null, Document.create(token,domainName,collectionName, docId, docData);
	});
  },
  
  getDocument : function(docId, callback) {
	const
	  token = this.token,
	  domainName = this.domainName,
	  collectionName = this.name;

	documentWrapper.get(token, domainName, collectionName, docId, function(err, docData) {
	  callback(err, err ? null : Document.create(token, domainName, collectionName, docId, docData));		
	});
  },
  
  replace : function(collectionRaw, callback) {
	const
	  self = this,
	  token = this.token,
	  domainName = this.domainName,
	  collectionName = this.collectionName;

	collectionWrapper.replace(token, domainName, collectionName, collectionRaw, function(err, collectionData) {
	  if(err) return callback(err);
		  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) delete self[key];
	  }

	  extend(self, collectionData);
	  callback(null, true);	  
	});
  },
  
  patch : function(patch, callback) {
	const
	  self = this,
	  token = this.token,
	  domainName = this.domainName,
	  collectionName = this.collectionName;
		
	collectionWrapper.patch(token, domainName, collectionName, patch, function(err, collectionData) {
	  if(err) return callback(err);
			  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) delete self[key];
	  }

	  extend(self, collectionData);
	  callback(null, true);	  
    });
  },
  
  remove : function(callback) {
	var
	  sef = this;

	collectionWrapper.remove(this.token, this.domainName, this.name, function(err, result){
	  self.removed = result ? true : false;
	  callback(err, result);	  
	});
  },
  
  setACL : function(acl, callback) {
	collectionWrapper.setACL(this.token, this.domainName, this.name, acl, function(err, result) {
	  callback(err, result);
	});
  },
  
  removePermissionSubject : function(acl, callback) {
	collectionWrapper.removePermissionSubject(this.token, this.domainName, this.name, acl, function(err, result) {
	  callback(err, result);
	});
  },
  
  findDocuments : function(query, callback) {
	collectionWrapper.findDocuments(this.token, this.domainName, this.name, query, function(err, result) {
	  callback(err, result);
	});
  }
};

// ----------------- END INITIALIZE MODULE SCOPE VARIABLES ------------------

// ---------------- BEGIN PUBLIC METHODS ------------------

create = function(token, domainName, collectionName, collectionData) {
  var 
    collection = Object.create(collectionProto, {
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
      name:{
    	value: collectionName,
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