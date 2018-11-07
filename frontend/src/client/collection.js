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
import Form from './form';
import uuidv4 from 'uuid/v4';
import utils from './utils';
import _ from 'lodash';


'use strict';

var collectionProto, create;

collectionProto = {
  createDocument: function() {
	const domainId = this.domainId, collectionId = this.id, socket = this.socket;
	var docId, docRaw, callback;

	if(arguments.length == 2 && typeof arguments[1] == 'function'){
	  docId = uuidv4();
	  docRaw = arguments[0];
	  callback = arguments[1];
	} else if(arguments.length == 3 && typeof arguments[2] == 'function'){
	  docId = arguments[0];
	  docRaw = arguments[1];
	  callback = arguments[2];
	} else {
	  throw utils.makeError('Error', 'Number or type of Arguments is not correct!', arguments);
	}
	  	
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
	socket.emit('findCollectionDocuments', domainId, collectionId, query, function(err, docsData) {
      if(err) return callback(err);

      var documents = _.map(docsData.hits.hits, function(docData){
      	return Document.create(socket, domainId, collectionId, docData._source);
      })

	  callback(null, {total:docsData.hits.total, documents: documents});
	});
  },

  findForms: function(query, callback){
    const domainId = this.domainId, collectionId = this.id, socket = this.socket;
    socket.emit('findCollectionFoms', domainId, collectionId, query, function(err, formInfos){
      if(err) return callback(err);
      var forms = _.map(formInfos.hits.hits, function(formInfo){
      	return Form.create(socket, domainId, formInfo._source);
      });
	  callback(null, {total:formInfos.hits.total, forms: forms});
	});
  },

  refresh: function(callback) {
	this.socket.emit('refreshCollection', this.domainId, this.id, function(err, result){
	  callback(err, result);	  
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