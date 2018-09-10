/*
 * domain.js
 * Domain module
 */

/*jslint         browser : true, continue : true,
  devel  : true, indent  : 2,    maxerr   : 50,
  newcap : true, nomen   : true, plusplus : true,
  regexp : true, sloppy  : true, vars     : false,
  white  : true
 */

import Collection from './collection';
import utils from './utils';
import _ from 'lodash';

'use strict';

var domainProto, create;

domainProto = {
  createCollection: function(collectionId, collectionRaw, callback){
    const domainId = this.id, socket = this.socket;
	socket.emit('createCollection', domainId, collectionId, collectionRaw, function(err, collectionData){
	  callback(err, err ? null : Collection.create(socket, domainId, collectionData));	  
	});
  },

  getCollection: function(collectionId, callback){
    const domainId = this.id, socket = this.socket;
	socket.emit('getCollection', domainId, collectionId, function(err, collectionData){
	  callback(err, err ? null : Collection.create(socket, domainId, collectionData));
	});
  },

  createView: function(viewId, viewRaw, callback){
    const domainId = this.id, socket = this.socket;
	socket.emit('createView', domainId, viewId, viewRaw, function(err, viewData){
	  callback(err, err ? null : View.create(socket, domainId, viewData));	  
	});
  },

  getView: function(viewId, callback){
    const domainId = this.id, socket = this.socket;
	socket.emit('getView', domainId, viewId, function(err, viewData){
	  callback(err, err ? null : View.create(socket, domainId, viewData));
	});
  },

  findCollections: function(callback){
    const domainId = this.id, socket = this.socket;
    socket.emit('findCollections', this.id, function(err, collectionInfos){
      if(err) return callback(err);

      var collections = _.map(collectionInfos.hits, function(collectionInfo){
      	return Collection.create(socket, domainId, collectionInfo._source)
      })
	  callback(null, collections);	  
	});
  },

  findViews: function(callback){
    const domainId = this.id, socket = this.socket;
    socket.emit('findViews', this.id, function(err, viewInfos){
      if(err) return callback(err);      	
      var views = _.map(viewInfos.hits, function(viewInfo){
      	return View.create(socket, domainId, viewInfo._source)
      })
	  callback(null, views);	  
	});
  },

  findFoms: function(callback){
    const domainId = this.id, socket = this.socket;
    socket.emit('findFoms', this.id, function(err, formInfos){
      if(err) return callback(err);      	
      var forms = _.map(formInfos.hits, function(formInfo){
      	return Form.create(socket, domainId, formInfo._source)
      })
	  callback(null, forms);
	});
  },

  findRoles: function(callback){
    const domainId = this.id, socket = this.socket;
    socket.emit('findRoles', this.id, function(err, roleInfos){
      if(err) return callback(err);      	
      var roles = _.map(roleInfos.hits, function(roleInfo){
        return Role.create(socket, domainId, roleInfo._source)
      })
	  callback(null, roles);
	});
  },

  findProfiles: function(callback){
    const domainId = this.id, socket = this.socket;
    socket.emit('findProfiles', this.id, function(err, profileInfos){
      if(err) return callback(err);      	
      var profiles = _.map(profileInfos.hits, function(profileInfo){
        return Profile.create(socket, domainId, profileInfo._source)
      })
	  callback(null, profiles);
	});
  },

  findUsers: function(callback){
    const domainId = this.id, socket = this.socket;
    socket.emit('findUsers', this.id, function(err, userInfos){
      if(err) return callback(err);      	
      var users = _.map(userInfos.hits, function(userInfo){
        return User.create(socket, domainId, userInfo._source)
      })
	  callback(null, users);
	});
  },

  findDomains: function(callback){
    const domainId = this.id, socket = this.socket;
    socket.emit('findDomains', this.id, function(err, domainInfos){
      if(err) return callback(err);      	
      var domains = _.map(domainInfos.hits, function(domainInfo){
        return Domain.create(socket, domainId, domainInfo._source)
      })
	  callback(null, domains);
	});
  },

  replace: function(domainRaw, callback){
	const self = this, domainId = this.id;
	this.socket.emit('replaceDomain', domainId, domainRaw, function(err, domainData){
	  if(err) return callback(err);

	  for(var key in self) {
	    if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }
			  
	  utils.extend(self, domainData);
	  callback(null, true);	  
	});
  },

  patch: function(patch, callback){
    const self = this, domainId = this.id;
	this.socket.emit('patchDomain', domainId, patch, function(err, domainData){
	  if(err) return callback(err);
			  
	  for(var key in self) {
	    if(self.hasOwnProperty(key)) try{delete self[key];}catch(e){}
	  }

	  utils.extend(self, domainData);
	  callback(null, true);	  
	});
  },
		  
  remove: function(callback){
    this.socket.emit('removeDomain', this.id, function(err, result){
	  callback(err, result);	  
	});
  },
		  
  getACL: function(callback) {
    this.socket.emit('getDomainACL', this.id, function(err, acl) {
	  callback(err, acl);
	});
  },

  replaceACL : function(acl, callback) {
    this.socket.emit('replaceDomainACL', this.id, acl, function(err, result) {
  	  callback(err, result);
	});
  },

  patchACL : function(aclPatch, callback) {
    this.socket.emit('patchDomainACL',  this.id, aclPatch, function(err, result) {
	  callback(err, result);
	});
  },

  garbageCollection: function(callback){
    this.socket.emit('domainGarbageCollection', this.id, function(err, result){
	  callback(err, result);	  
    });
  }
};
  
create = function(socket, domainData){
  var domain = Object.create(domainProto, {
    socket:{
	  value: socket,
	  configurable: false,
	  writable: false,
	  enumerable: false
	}
  });
		  
  return utils.extend(true, domain, domainData);
};

export default {
  create : create
};