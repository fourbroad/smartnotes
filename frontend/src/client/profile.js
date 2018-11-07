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

var profileProto, create;

profileProto = {
  replace: function(profileRaw, callback) {
	const self = this;
	this.socket.emit('replaceProfile', this.id, profileRaw, function(err, profileData) {
	  if(err) return callback(err);
			  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  utils.extend(self, profileData);
	  callback(null, true);	  
	});
  },
  
  patch: function(patch, callback) {
	const self = this;
	this.socket.emit('patchProfile', this.id, patch, function(err, profileData) {
	  if(err) return callback(err);
				  
	  for(var key in self) {
		if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  utils.extend(self, profileData);
	  callback(null, true);	  
	});
  },
  
  remove: function(callback) {
	this.socket.emit('removeProfile', this.id, function(err, result) {
	  callback(err, result);	  
	});
  },
  
  getACL: function(callback) {
	this.socket.emit('getProfileACL', this.id, function(err, acl) {
	  callback(err, acl);
	});
  },

  replaceACL: function(acl, callback) {
	this.socket.emit('replaceProfileACL', this.id, acl, function(err, result) {
	  callback(err, result);
	});
  },

  patchACL: function(aclPatch, callback) {
	this.socket.emit('patchProfileACL', this.id, aclPatch, function(err, result) {
	  callback(err, result);
	});
  },
  
  removePermissionSubject: function(acl, callback) {
	this.socket.emit('removeProfilePermissionSubject', this.id, acl, function(err, result) {
	  callback(err, result);
	});
  }
};

create = function(socket, domainId, profileData) {
  var
    profile = Object.create(profileProto, {
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

  return utils.extend(true, profile, profileData)
};

export default {
  create     : create
};