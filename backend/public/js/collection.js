/*
 * collection.js - module to provide document collection capabilities
 */

/*jslint         node    : true, continue : true,
 devel  : true, indent  : 2,    maxerr   : 50,
 newcap : true, nomen   : true, plusplus : true,
 regexp : true, sloppy  : true, vars     : false,
 white  : true
 */

define(['socket.io', 'document', 'util'], function(io, Document, util) {
  'use strict';

  var collectionProto, create;

collectionProto = {
  createDocument: function(docId, docRaw, callback) {
	const domainId = this.domainId, collectionId = this.id, socket = this.socket;
	socket.emit('createDocument', domainId, collectionId, docId, docRaw, function(err, docData) {
	  callback(err, err ? null : Document.create(socket, domainId, collectionId, docData));
	});
  },
  
  getDocument: function(docId, callback) {
	const domainId = this.domainId, collectionId = this.id, socket = this.socket;
	socket.emit('getDocument', domainId, collectionId, docId, function(err, docData) {
	  callback(err, err ? null : Document.create(socket, domainId, collectionId, docData));
	});
  },

  replace: function(collectionRaw, callback) {
	const self = this, domainId = this.domainId, collectionId = this.id;
	this.socket.emit('replaceCollection', domainId, collectionId, collectionRaw, function(err, collectionData) {
	  if(err) return callback(err);
		  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  util.extend(self, collectionData);
	  callback(null, true);	  
	});
  },
  
  patch: function(patch, callback) {
	const self = this, domainId = this.domainId, collectionId = this.id;
	this.socket.emit('patchCollection', domainId, collectionId, patch, function(err, collectionData) {
	  if(err) return callback(err);
			  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  util.extend(self, collectionData);
	  callback(null, true);	  
    });
  },
  
  remove: function(callback) {
	this.socket.emit('removeCollection', this.domainId, this.id, function(err, result){
	  callback(err, result);	  
	});
  },

  getACL: function(callback) {
	this.socket.emit('getCollectionACL', this.domainId, this.id, function(err, acl) {
	  callback(err, acl);
	});
  },
  
  replaceACL: function(acl, callback) {
	this.socket.emit('replaceCollectionACL', this.domainId, this.id, acl, function(err, result) {
	  callback(err, result);
	});
  },
	  
  patchACL: function(aclPatch, callback) {
	this.socket.emit('patchCollectionACL', this.domainId, this.id, aclPatch, function(err, result) {
	  callback(err, result);
	});
  },
  
  removePermissionSubject: function(acl, callback) {
	this.socket.emit('removeCollectionPermissionSubject', this.domainId, this.id, acl, function(err, result) {
	  callback(err, result);
	});
  },
  
  findDocuments: function(query, callback) {
	this.socket.emit('findDocuments', this.domainId, this.id, query, function(err, docsData) {
	  callback(err, docsData);
	});
  }
};

create = function(socket, domainId, collectionData) {
  var 
    collection = Object.create(collectionProto, {
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
      }
    });

  return util.extend(true, collection, collectionData)
};

return {
  create     : create
};

});
