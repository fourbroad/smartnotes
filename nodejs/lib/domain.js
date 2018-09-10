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
  Collection = require('./collection'),
  domainWrapper = new __DomainWrapper(),  
  collectionWrapper = new __CollectionWrapper();

var
  domainProto, create;

// ------------- END MODULE SCOPE VARIABLES ---------------

// ---------------- BEGIN INITIALIZE MODULE SCOPE VARIABLES -----------------

domainProto = {
  createCollection: function(collectionId, collectionRaw, callback){
	const 
	  token = this.token,
	  domainId = this.id;
	
	collectionWrapper.create(token, domainId, collectionId, collectionRaw, function(err, collectionData){
	  callback(err, err ? null : Collection.create(token, domainId, collectionData));	  
	});
  },
  
  getCollection: function(collectionId, callback){
	const 
	  token = this.token,
	  domainId = this.id;

	collectionWrapper.get(token, domainId, collectionId, function(err, collectionData){
	  callback(err, err ? null : Collection.create(token, domainId, collectionData));
	});
  },
  
  findCollections: function(callback){
	domainWrapper.findCollections(this.token, this.id, function(err, collectionInfos){
	  callback(err, collectionInfos);	  
	});
  },
  
  replace: function(domainRaw, callback){
	const
	  self = this,
	  token = this.token,
	  domainId = this.id;

	domainWrapper.replace(token, domainId, domainRaw, function(err, domainData){
	  if(err) return callback(err);

	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }
	  
	  extend(self, domainData);
	  callback(null, true);	  
	});
  },
  
  patch: function(patch, callback){
	const 
	  self = this,
	  token = this.token,
	  domainId = this.id;
	
	domainWrapper.patch(token, domainId, patch, function(err, domainData){
	  if(err) return callback(err);
	  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  extend(self, domainData);
	  callback(null, true);	  
	});
  },
  
  remove: function(callback){
	domainWrapper.remove(this.token, this.id, function(err, result){
	  callback(err, result);	  
	});
  },
  
  getACL : function(callback) {
	domainWrapper.getACL(this.token, this.id, function(err, acl) {
	  callback(err, acl);
	});
  },

  replaceACL : function(acl, callback) {
	domainWrapper.replaceACL(this.token, this.id, acl, function(err, result) {
	  callback(err, result);
	});
  },

  patchACL : function(aclPatch, callback) {
	domainWrapper.patchACL(this.token, this.id, aclPatch, function(err, result) {
	  callback(err, result);
	});
  },

  refresh: function(callback){
	domainWrapper.refresh(this.token, this.id, function(err, result){
	  callback(err, result);	  
    });
  },
  
  garbageCollection: function(callback){
	domainWrapper.garbageCollection(this.token, this.id, function(err, result){
	  callback(err, result);	  
	});
  }
};

// ----------------- END INITIALIZE MODULE SCOPE VARIABLES ------------------


// ---------------- BEGIN PUBLIC METHODS ------------------

create = function(token, domainData){
  var 
    domain = Object.create(domainProto, {
      token:{
    	value: token,
    	configurable: false,
    	writable: false,
    	enumerable: false
      }
    });
	  
  return extend(true, domain, domainData)	
};

module.exports = {
  create: create
};

// ----------------- END PUBLIC METHODS -----------------
