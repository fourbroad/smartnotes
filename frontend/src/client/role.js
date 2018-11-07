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

var roleProto, create;

roleProto = {
  replace: function(roleRaw, callback) {
	const self = this;
	this.socket.emit('replaceRole', this.id, roleRaw, function(err, roleData) {
	  if(err) return callback(err);
			  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  utils.extend(self, roleData);
	  callback(null, true);	  
	});
  },
  
  patch: function(patch, callback) {
	const self = this;
	this.socket.emit('patchRole', this.id, patch, function(err, roleData) {
	  if(err) return callback(err);
				  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  utils.extend(self, roleData);
	  callback(null, true);	  
	});
  },
  
  remove: function(callback) {
	this.socket.emit('removeRole', this.id, function(err, result) {
	  callback(err, result);	  
	});
  },
  
  getACL: function(callback) {
	this.socket.emit('getRoleACL', this.id, function(err, acl) {
	  callback(err, acl);
	});
  },

  replaceACL: function(acl, callback) {
	this.socket.emit('replaceRoleACL', this.id, acl, function(err, result) {
	  callback(err, result);
	});
  },

  patchACL: function(aclPatch, callback) {
	this.socket.emit('patchRoleACL', this.id, aclPatch, function(err, result) {
	  callback(err, result);
	});
  },
  
  removePermissionSubject: function(acl, callback) {
	this.socket.emit('removeRolePermissionSubject', this.id, acl, function(err, result) {
	  callback(err, result);
	});
  }
};

create = function(socket, domainId, roleData) {
  var
    role = Object.create(roleProto, {
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

  return utils.extend(true, role, roleData)
};

export default {
  create     : create
};