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
  Collection = require('./collection'),
  domainWrapper = new __DomainWrapper(),  
  collectionWrapper = new __CollectionWrapper();

var
  domainProto, create;

// ------------- END MODULE SCOPE VARIABLES ---------------

// ---------------- BEGIN INITIALIZE MODULE SCOPE VARIABLES -----------------

domainProto = {
  createCollection: function(collectionName, collectionRaw, callback){
	const 
	  domainName = this.name,
	  token = this.token;
	
	collectionWrapper.create(token, domainName, collectionName, collectionRaw, function(err, collectionData){
	  callback(err, err ? null : Collection.create(token, domainName, collectionName, collectionData));	  
	});
  },
  
  getCollection: function(collectionName, callback){
	const 
	  domainName = this.name,
	  token = this.token;

	collectionWrapper.getCollection(token, domainName, collectionName, function(err, collectionData){
	  callback(err, err ? null : Collection.create(token, domainName, collectionName, collectionData));
	});
  },
  
  listCollections: function(callback){
	domainWrapper.listCollections(this.token, this.name, function(err, collectionInfos){
	  callback(err, collectionInfos);	  
	});
  },
  
  replace: function(domainRaw, callback){
	const 
	  self = this,
	  token = this.token,
	  domainName = this.name;

	domainWrapper.replace(token, domainName, domainRaw, function(err, domainData){
	  if(err) return callback(err);
		  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) delete self[key];
	  }

	  extend(self, domainData);
	  callback(null, true);	  
	});
  },
  
  patch: function(patch, callback){
	const 
	  self = this,
	  token = this.token,
	  domainName = this.name;
	
	domainWrapper.patch(token, domainName, patch, function(err, domainData){
	  if(err) return callback(err);
	  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) delete self[key];
	  }

	  extend(self, domainData);
	  callback(null, true);	  
	});
  },
  
  remove: function(callback){
	domainWrapper.remove(this.token, this.name, function(err, result){
	  callback(err, result);	  
	});
  },
  
  garbageCollection: function(callback){
	domainWrapper.garbageCollection(this.token, this.name, function(err, result){
	  callback(err, result);	  
	});
  }
};

// ----------------- END INITIALIZE MODULE SCOPE VARIABLES ------------------


// ---------------- BEGIN PUBLIC METHODS ------------------

create = function(token, domainName, domainData){
  var 
    domain = Object.create(domainProto, {
      token:{
    	value: token,
    	configurable: false,
    	writable: false,
    	enumerable: false
      },
      name:{
    	value: domainName,
      	configurable: false,
    	writable: false,
    	enumerable: true
      }
    });
	  
  return extend(true, domain, domainData)	
};

module.exports = {
  create : create
};

// ----------------- END PUBLIC METHODS -----------------
