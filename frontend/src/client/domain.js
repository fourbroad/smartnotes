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
import View from './view';
import Form from './form';
import Profile from './profile';
import Role from './role';
import User from './user';
import utils from './utils';
import uuidv4 from 'uuid/v4';
import _ from 'lodash';

'use strict';

var domainProto, create;

domainProto = {
  createCollection: function(){
    const domainId = this.id, socket = this.socket;
	var collectionId, collectionRaw, callback; 
	   
	if(arguments.length == 2 && typeof arguments[1] == 'function'){
	  collectionId = uuidv4();
	  collectionRaw = arguments[0];
	  callback = arguments[1];
	} else if(arguments.length == 3 && typeof arguments[2] == 'function'){
	  collectionId = arguments[0];
	  collectionRaw = arguments[1];
	  callback = arguments[2];
	} else {
	  throw utils.makeError('Error', 'Number or type of Arguments is not correct!', arguments);
	}

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

  findCollections: function(query, callback){
    const domainId = this.id, socket = this.socket;
    socket.emit('findCollections', domainId, query, function(err, collectionInfos){
      if(err) return callback(err);
      var collections = _.map(collectionInfos.hits.hits, function(collectionInfo){
      	return Collection.create(socket, domainId, collectionInfo._source);
      })

	  callback(null, {total:collectionInfos.hits.total, collections: collections});
	});
  },

  getDocument: function(collectionId, documentId, callback){
    const domainId = this.id, socket = this.socket;
	socket.emit('getDocument', domainId, collectionId, documentId, function(err, docData) {
      if(err) return callback(err);

      switch(docData._metadata.type){
      	case 'domain':
      	  callback(null, create(socket, docData));
      	  break;
        case 'collection':
          callback(null, Collection.create(socket, domainId, docData));
          break;
        case 'view':
          callback(null, View.create(socket, domainId, docData));
          break;
        case 'form':
          callback(null, Form.create(socket, domainId, docData));
          break;
        case 'role':
          callback(null, Role.create(socket, domainId, docData));
          break;
        case 'profile':
          callback(null, Profile.create(socket, domainId, docData));
          break;
        case 'user':
          callback(null, View.create(socket, docData));
          break;
        default:
          callback(null, Document.create(socket, domainId, collectionId, docData));
          break;
      }      
	});
  },

  createView: function(){
    const domainId = this.id, socket = this.socket;
	var viewId, viewRaw, callback;

	if(arguments.length == 2 && typeof arguments[1] == 'function'){
	  viewId = uuidv4();
	  viewRaw = arguments[0];
	  callback = arguments[1];
	} else if(arguments.length == 3 && typeof arguments[2] == 'function'){
	  viewId = arguments[0];
	  viewRaw = arguments[1];
	  callback = arguments[2];
	} else {
	  throw utils.makeError('Error', 'Number or type of Arguments is not correct!', arguments);
	}
	    
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

  findViews: function(query, callback){
    const domainId = this.id, socket = this.socket;
    socket.emit('findViews', domainId, query, function(err, viewInfos){
      if(err) return callback(err); 
      var views = _.map(viewInfos.hits.hits, function(viewInfo){
      	return View.create(socket, domainId, viewInfo._source);
      })
	  callback(null, {total:viewInfos.hits.total, views: views});
	});
  },

  createForm: function(){
    const domainId = this.id, socket = this.socket;
	var formId, formRaw, callback;

	if(arguments.length == 2 && typeof arguments[1] == 'function'){
	  formId = uuidv4();
	  formRaw = arguments[0];
	  callback = arguments[1];
	} else if(arguments.length == 3 && typeof arguments[2] == 'function'){
	  formId = arguments[0];
	  formRaw = arguments[1];
	  callback = arguments[2];
	} else {
	  throw utils.makeError('Error', 'Number or type of Arguments is not correct!', arguments);
	}
	    
	socket.emit('createView', domainId, formId, formRaw, function(err, formData){
	  callback(err, err ? null : View.create(socket, domainId, formData));	  
	});
  },
  
  getForm: function(formId, callback){
    const domainId = this.id, socket = this.socket;
	socket.emit('getForm', domainId, formId, function(err, formData){
	  callback(err, err ? null : Form.create(socket, domainId, formData));
	});
  },

  findForms: function(query, callback){
    const domainId = this.id, socket = this.socket;
    socket.emit('findDomainFoms', domainId, query, function(err, formInfos){
      if(err) return callback(err);
      var forms = _.map(formInfos.hits.hits, function(formInfo){
      	return Form.create(socket, domainId, formInfo._source);
      });
	  callback(null, {total:formInfos.hits.total, forms: forms});
	});
  },

  createRole: function(roleId, roleRaw, callback){
    const domainId = this.id, socket = this.socket;
	if(arguments.length == 2 && typeof arguments[1] == 'function'){
	  roleId = uuidv4();
	  roleRaw = arguments[0];
	  callback = arguments[1];
	} else if(arguments.length == 3 && typeof arguments[2] == 'function'){
	  roleId = arguments[0];
	  roleRaw = arguments[1];
	  callback = arguments[2];
	} else {
	  throw utils.makeError('Error', 'Number or type of Arguments is not correct!', arguments);
	}
	    
	socket.emit('createRole', domainId, roleId, roleRaw, function(err, roleData){
	  callback(err, err ? null : Role.create(socket, domainId, roleData));	  
	});
  },

  getRole: function(roleId, callback){
    const domainId = this.id, socket = this.socket;
	socket.emit('getRole', domainId, roleId, function(err, roleData){
	  callback(err, err ? null : Role.create(socket, domainId, roleData));
	});
  },  

  findRoles: function(query, callback){
    const domainId = this.id, socket = this.socket;
    socket.emit('findRoles', domainId, query, function(err, roleInfos){
      if(err) return callback(err);      	
      var roles = _.map(roleInfos.hits.hits, function(roleInfo){
      	return Role.create(socket, domainId, roleInfo._source);
      })

	  callback(null, {total:roleInfos.hits.total, roles: roles});
	});
  },

  createProfile: function(profileId, profileRaw, callback){
    const domainId = this.id, socket = this.socket;
	if(arguments.length == 2 && typeof arguments[1] == 'function'){
	  profileId = uuidv4();
	  profileRaw = arguments[0];
	  callback = arguments[1];
	} else if(arguments.length == 3 && typeof arguments[2] == 'function'){
	  profileId = arguments[0];
	  profileRaw = arguments[1];
	  callback = arguments[2];
	} else {
	  throw utils.makeError('Error', 'Number or type of Arguments is not correct!', arguments);
	}
	    
	socket.emit('createProfile', domainId, profileId, profileRaw, function(err, profileData){
	  callback(err, err ? null : Profile.create(socket, domainId, profileData));	  
	});
  },

  getProfile: function(profileId, callback){
    const domainId = this.id, socket = this.socket;
	socket.emit('getProfile', domainId, profileId, function(err, profileData){
	  callback(err, err ? null : Profile.create(socket, domainId, profileData));
	});
  },  

  findProfiles: function(query, callback){
    const domainId = this.id, socket = this.socket;
    socket.emit('findProfiles', domainId, query, function(err, profileInfos){
      if(err) return callback(err);      	
      var profiles = _.map(profileInfos.hits.hits, function(profileInfo){
      	return Profile.create(socket, domainId, profileInfo._source);
      })

	  callback(null, {total:profileInfos.hits.total, profiles: profiles});
	});
  },

  createUser: function(userId, userRaw, callback){
    const socket = this.socket;
	socket.emit('createUser', userId, userRaw, function(err, userData){
	  callback(err, err ? null : User.create(socket, domainId, userData));	  
	});
  },

  getUser: function(userId, callback){
    const socket = this.socket;
	socket.emit('getUser', userId, function(err, userData){
	  callback(err, err ? null : User.create(socket, domainId, userData));
	});
  },  

  findUsers: function(query, callback){
    const socket = this.socket;
    socket.emit('findUsers', query, function(err, userInfos){
      if(err) return callback(err);      	
      var users = _.map(userInfos.hits.hits, function(userInfo){
      	return User.create(socket, domainId, userInfo._source);
      })

	  callback(null, {total:profileInfos.hits.total, profiles: profiles});
	});
  },

  createDomain: function(domainId, domainRaw, callback){
    const socket = this.socket;
	socket.emit('createDomain', domainId, domainRaw, function(err, domainData){
	  callback(err, err ? null : Domain.create(socket, domainData));	  
	});
  },

  getDomain: function(domainId, callback){
    const socket = this.socket;
	socket.emit('getDomain', domainId, function(err, domainData){
	  callback(err, err ? null : Domain.create(socket, domainData));
	});
  },  

  findDomains: function(query, callback){
    const socket = this.socket;
    socket.emit('findDomains', query, function(err, domainInfos){
      if(err) return callback(err);
      var domains = _.map(domainInfos.hits.hits, function(domainInfo){
        return Domain.create(socket, domainInfo._source)
      })
	  callback(null, {total:domainInfos.hits.total, domains: domains});
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