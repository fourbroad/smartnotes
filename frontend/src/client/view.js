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
import Document from './document';

'use strict';

var viewProto, create;

viewProto = {
  replace: function(viewRaw, callback) {
	const self = this;
	this.socket.emit('replaceView', this.domainId, this.id, viewRaw, function(err, viewData) {
	  if(err) return callback(err);
		  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  utils.extend(self, viewData);
	  callback(null, true);	  
	});
  },
  
  patch: function(patch, callback) {
	const self = this;
	this.socket.emit('patchView', this.domainId, this.id, patch, function(err, viewData) {
	  if(err) return callback(err);

	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  utils.extend(self, viewData);
	  callback(null, true);
	});
  },
  
  remove: function(callback) {
	  this.socket.emit('removeView', this.domainId, this.id, function(err, result) {
	  callback(err, result);	  
	});
  },

  getACL: function(callback) {
	this.socket.emit('getViewACL', this.domainId, this.id, function(err, acl) {
	  callback(err, acl);
	});
  },
  
  replaceACL: function(acl, callback) {
	this.socket.emit('replaceViewACL', this.domainId, this.id, acl, function(err, result) {
	  callback(err, result);
	});
  },
  
  patchACL: function(aclPatch, callback) {
	this.socket.emit('patchViewACL', this.domainId, this.id, aclPatch, function(err, result) {
	  callback(err, result);
	});
  },
  
  removePermissionSubject: function(acl, callback) {
	this.socket.emit('removeViewPermissionSubject', this.domainId, this.id, acl, function(err, result) {
	  callback(err, result);
	});
  },
  
  findDocuments: function(query, callback) {
    const domainId = this.domainId, viewId = this.id, socket = this.socket;
	socket.emit('findViewDocuments', domainId, viewId, query, function(err, docsData) {
      if(err) return callback(err);

      var documents = _.map(docsData.hits.hits, function(docData){
      	return Document.create(socket, domainId, viewId, docData._source);
      });

	  callback(null, {total:docsData.hits.total, documents: documents});
	});
  },

  refresh: function(callback) {
    this.socket.emit('refreshView', this.domainId, this.id, function(err, result) {
	  callback(err, result);	  
	});
  }
};

create = function(socket, domainId, viewData) {
  var 
    view = Object.create(viewProto, {
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

  return utils.extend(true, view, viewData)
};

export default {
  create     : create
};