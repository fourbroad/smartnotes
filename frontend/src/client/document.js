/*
 * notes.js - module to provide CRUD db capabilities
 */

/*jslint         node    : true, continue : true,
 devel  : true, indent  : 2,    maxerr   : 50,
 newcap : true, nomen   : true, plusplus : true,
 regexp : true, sloppy  : true, vars     : false,
 white  : true
 */

import utils from './utils';

'use strict';

var documentProto, create;

documentProto = {
  replace: function(docRaw, callback) {
	const self = this;
	this.socket.emit('replaceDocument', this.domainId, this.collectionId, this.id, docRaw, function(err, docData) {
	  if(err) return callback(err);
		  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  utils.extend(self, docData);
	  callback(null, true);	  
	});
  },
  
  patch: function(patch, callback) {
	const self = this;
	this.socket.emit('patchDocument', this.domainId, this.collectionId, this.id, patch, function(err, docData) {
	  if(err) return callback(err);

	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  utils.extend(self, docData);
	  callback(null, true);
	});
  },
  
  remove: function(callback) {
	  this.socket.emit('removeDocument', this.domainId, this.collectionId, this.id, function(err, result) {
	  callback(err, result);	  
	});
  },

  getACL: function(callback) {
	this.socket.emit('getDocumentACL', this.domainId, this.collectionId, this.id, function(err, acl) {
	  callback(err, acl);
	});
  },
  
  replaceACL: function(acl, callback) {
	this.socket.emit('replaceDocumentACL', this.domainId, this.collectionId, this.id, acl, function(err, result) {
	  callback(err, result);
	});
  },
  
  patchACL: function(aclPatch, callback) {
	this.socket.emit('patchDocumentACL', this.domainId, this.collectionId, this.id, aclPatch, function(err, result) {
	  callback(err, result);
	});
  },
  
  removePermissionSubject: function(acl, callback) {
	this.socket.emit('removeDocumentPermissionSubject', this.domainId, this.collectionId, this.id, acl, function(err, result) {
	  callback(err, result);
	});
  }
};

create = function(socket, domainId, collectionId, docData) {
  var 
    document = Object.create(documentProto, {
      socket:{
  	    value: socket,
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

  return utils.extend(true, document, docData)
};

export default {
  create     : create
};