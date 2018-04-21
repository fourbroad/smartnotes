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
	documentWrapper.createDocument(this.domainName, this.name, docId, this.token, function(err, document){
	  callback(err, document);	  
	});
  },
  getDocument: function(docId, callback){
	documentWrapper.getDocument(this.domainName, this.name, docId, this.token, function(err, document){
	  callback(err, document);	  
	});
  },
  replaceDocument: function(docId, content, callback){
	documentWrapper.replaceDocument(this.domainName, this.name, docId, this.token, content, function(err, document){
	  callback(err, document);	  
	});
  },
  patchDocument: function(docId, patch, callback){
	documentWrapper.patchDocument(this.domainName, this.name, docId, this.token, patch, function(err, document){
	  callback(err, document);	  
	});
  },
  deleteDocument: function(docId, callback){
	documentWrapper.deleteDocument(this.domainName, this.name, docId, this.token, function(err, result){
	  callback(err, result);	  
	});
  },
  authorizeDocument: function(docId, acl, callback){
	documentWrapper.authorizeDocument(this.domainName, this.name, docId, this.token, acl, function(err, result){
	  callback(err, result);	  
	});
  },
  findDocuments: function(query, callback){
	  this.collectionWrapper.findDocuments(this.domainName, this.name, this.token, query, function(err,result){
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
