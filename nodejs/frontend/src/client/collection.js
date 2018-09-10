/*
 * collection.js - module to provide document collection capabilities
 */

/*jslint         node    : true, continue : true,
 devel  : true, indent  : 2,    maxerr   : 50,
 newcap : true, nomen   : true, plusplus : true,
 regexp : true, sloppy  : true, vars     : false,
 white  : true
 */

import Document from './document';
import utils from './utils';
import _ from 'lodash';


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

	  utils.extend(self, collectionData);
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

	  utils.extend(self, collectionData);
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
    const domainId = this.domainId, collectionId = this.id, socket = this.socket;
	socket.emit('findDocuments', domainId, collectionId, query, function(err, docsData) {
      if(err) return callback(err);

      var documents = _.map(docsData.hits.hits, function(docData){
      	return Document.create(socket, domainId, collectionId, docData._source);
      })

	  callback(null, {total:docsData.hits.total, documents: documents});
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

  return utils.extend(true, collection, collectionData)
};

export default {
  create : create
};