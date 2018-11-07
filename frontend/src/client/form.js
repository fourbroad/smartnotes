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

var formProto, create;

formProto = {
  replace: function(formRaw, callback) {
	const self = this;
	this.socket.emit('replaceForm', this.id, formRaw, function(err, formData) {
	  if(err) return callback(err);
			  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  utils.extend(self, formData);
	  callback(null, true);	  
	});
  },
  
  patch: function(patch, callback) {
	const self = this;
	this.socket.emit('patchForm', this.id, patch, function(err, formData) {
	  if(err) return callback(err);
				  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  utils.extend(self, formData);
	  callback(null, true);	  
	});
  },
  
  remove: function(callback) {
	this.socket.emit('removeForm', this.id, function(err, result) {
	  callback(err, result);	  
	});
  },
  
  getACL: function(callback) {
	this.socket.emit('getFormACL', this.id, function(err, acl) {
	  callback(err, acl);
	});
  },

  replaceACL: function(acl, callback) {
	this.socket.emit('replaceFormACL', this.id, acl, function(err, result) {
	  callback(err, result);
	});
  },

  patchACL: function(aclPatch, callback) {
	this.socket.emit('patchFormACL', this.id, aclPatch, function(err, result) {
	  callback(err, result);
	});
  },
  
  removePermissionSubject: function(acl, callback) {
	this.socket.emit('removeFormPermissionSubject', this.id, acl, function(err, result) {
	  callback(err, result);
	});
  }
};

create = function(socket, domainId, formData) {
  var
    form = Object.create(formProto, {
      socket: {
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

  return utils.extend(true, form, formData)
};

export default {
  create     : create
};