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

var userProto, create;

userProto = {
  isAnonymous: function(){
  	return this.id === 'anonymous';
  },

  replace: function(userRaw, callback) {
	const self = this;
	this.socket.emit('replaceUser', this.id, userRaw, function(err, userData) {
	  if(err) return callback(err);
			  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  utils.extend(self, userData);
	  callback(null, true);	  
	});
  },
  
  patch: function(patch, callback) {
	const self = this;
	this.socket.emit('patchUser', this.id, patch, function(err, userData) {
	  if(err) return callback(err);
				  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  utils.extend(self, userData);
	  callback(null, true);	  
	});
  },
  
  remove: function(callback) {
	this.socket.emit('removeUser', this.id, function(err, result) {
	  callback(err, result);	  
	});
  },
  
  resetPassword: function(newPassword, callback) {
	this.socket.emit('resetPassword', this.id, newPassword, function(err, result) {
	  callback(err, result);
	});
  },
  
  getACL: function(callback) {
	this.socket.emit('getUserACL', this.id, function(err, acl) {
	  callback(err, acl);
	});
  },

  replaceACL: function(acl, callback) {
	this.socket.emit('replaceUserACL', this.id, acl, function(err, result) {
	  callback(err, result);
	});
  },

  patchACL: function(aclPatch, callback) {
	this.socket.emit('patchUserACL', this.id, aclPatch, function(err, result) {
	  callback(err, result);
	});
  },
  
  removePermissionSubject: function(acl, callback) {
	this.socket.emit('removeUserPermissionSubject', this.id, acl, function(err, result) {
	  callback(err, result);
	});
  }
};

create = function(socket, userData) {
  var
    user = Object.create(userProto, {
      socket: {
	    value: socket,
	    configurable: false,
	    writable: false,
	    enumerable: false
      }
    });

  return utils.extend(true, user, userData)
};

export default {
  create     : create
};