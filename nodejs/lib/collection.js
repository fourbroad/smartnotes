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
  documentWrapper = new __DocumentWrapper(),
  collectionProto, newCollection;

// ------------- END MODULE SCOPE VARIABLES ---------------

// ---------------- BEGIN INITIALIZE MODULE SCOPE VARIABLES -----------------

collectionProto = {
  createDocument: function(docId, callback){
	documentWrapper.createDocument(this.name, docId, this.token, function(err, collection){
	  callback(err, collection);	  
	});
  },
  getDocument: function(docId, callback){
	documentWrapper.getDocument(this.name, docId, this.token, function(err, collection){
	  callback(err, collection);	  
	});
  },
  replaceDocument: function(docId, content, callback){
	documentWrapper.replaceDocument(this.name, docId, this.token, content, function(err, collection){
	  callback(err, collection);	  
	});
  },
  patchDocument: function(docId, patch, callback){
	documentWrapper.patchDocument(this.name, docId, this.token, patch, function(err, collection){
	  callback(err, collection);	  
	});
  },
  deleteDocument: function(docId, callback){
	documentWrapper.deleteDocument(this.name, docId, this.token, function(err, result){
	  callback(err, result);	  
	});
  },
  authorizeDocument: function(docId, acl, callback){
	documentWrapper.authorizeDocument(this.name, docId, this.token, acl, function(err, result){
	  callback(err, result);	  
	});
  }
};

// ----------------- END INITIALIZE MODULE SCOPE VARIABLES ------------------


// ---------------- BEGIN PUBLIC METHODS ------------------

newCollection = function(domainName, collectionName, token, callback){
	var collection = Object.create(collectionProto);
	collection.domainName = domainName;
	collection.name = collectionName;
	collection.token     = token;
	callback(null,collection);
};

module.exports = {
  newCollection : newCollection
};

// ----------------- END PUBLIC METHODS -----------------
