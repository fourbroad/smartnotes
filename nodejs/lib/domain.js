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
  Collection = require('./collection'),
  extend = require('extend'),
  collectionWrapper = new __CollectionWrapper();

var
  domainProto, newDomain;

// ------------- END MODULE SCOPE VARIABLES ---------------

// ---------------- BEGIN INITIALIZE MODULE SCOPE VARIABLES -----------------

domainProto = {
  createCollection: function(collectionName, callback){
	collectionWrapper.createCollection(this.name, collectionName, this.token, function(err, collection){
	  callback(err, collection);	  
	});
  },
  getCollection: function(collectionName, callback){
	const 
	  domainName = this.name,
	  token = this.token;

	collectionWrapper.getCollection(domainName, collectionName, token, function(err, collectionData){
	  if(err) return callback(err);
	  Collection.newCollection(domainName, collectionName, token, function(err2, collection){
		callback(err2, extend(true, collection, collectionData, {collectionWrapper: collectionWrapper}));  
	  });
	});
  },
  replaceCollection: function(collectionName, content, callback){
	collectionWrapper.replaceCollection(this.name, collectionName, this.token, content, function(err, collection){
	  callback(err, collection);	  
	});
  },
  patchCollection: function(collectionName, patch, callback){
	collectionWrapper.patchCollection(this.name, collectionName, this.token, patch, function(err, collection){
	  callback(err, collection);	  
	});
  },
  deleteCollection: function(collectionName, callback){
	collectionWrapper.deleteCollection(this.name, collectionName, this.token, function(err, result){
	  callback(err, result);	  
	});
  },
  authorizeCollection: function(collectionName, acl, callback){
	collectionWrapper.authorizeCollection(this.name, collectionName, this.token, acl, function(err, result){
	  callback(err, result);	  
	});
  },
  listCollections: function(callback){
	this.domainWrapper.listCollections(this.name, this.token, function(err, collections){
	  callback(err, collections);	  
	});
  }
};

// ----------------- END INITIALIZE MODULE SCOPE VARIABLES ------------------


// ---------------- BEGIN PUBLIC METHODS ------------------

newDomain = function(domainName, token, callback){
	var domain = Object.create(domainProto);
	domain.name = domainName;
	domain.token = token;
	callback(null,domain);
};

module.exports = {
  newDomain : newDomain
};

// ----------------- END PUBLIC METHODS -----------------
