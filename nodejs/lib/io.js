/*
 * io.js - module to provide network I/O.
 */

/*jslint         node    : true, continue : true,
  devel  : true, indent  : 2,    maxerr   : 50,
  newcap : true, nomen   : true, plusplus : true,
  regexp : true, sloppy  : true, vars     : false,
  white  : true
 */

// ------------ BEGIN MODULE SCOPE VARIABLES --------------
'use strict';
const
  domainWrapper = new __DomainWrapper(),
  collectionWrapper = new __CollectionWrapper(),
  viewWrapper = new __ViewWrapper(),
  documentWrapper = new __DocumentWrapper(),
  userWrapper = new __UserWrapper(),
  socketIO = require('socket.io'),
  extend = require('extend'),
  chat = require('./chat'),
  utils = require('./utils');

var
  io;
// ------------- END MODULE SCOPE VARIABLES ---------------

// ---------------- BEGIN PUBLIC METHODS ------------------
io = {
  connect : function(server) {
    var io = socketIO.listen(server);
    
    io.of('/domains').on('connection', function(socket){
   	  const token = socket.handshake.query.token;
      
   	  socket.on('login', function(username, password, callback){
      	userWrapper.login(username, password, function(err1, token){
      	  if(err1) return callback(err1);
      	  userWrapper.get(token, username, function(err2, userData) {
      		if(err2) return callback(err2);
      		callback(null, token, userData);
      	  });
      	});
      });

      socket.on('registerUser', function(userInfo, callback){
      	userWrapper.register(userInfo, callback);
      });

      socket.on('logout', function(callback){
   		userWrapper.logout(token, callback);    	  
      });

      socket.on('getUser', function(){
    	var userId, callback;
    	if(arguments.length == 1 && typeof arguments[0] == 'function'){
    	  userId = '';
   		  callback = arguments[0];
   		} else if(arguments.length == 2 && typeof arguments[1] == 'function'){
   		  userId = arguments[0];
   		  callback = arguments[1];
   		} else {
   		  throw util.makeError('Error', 'Number or type of arguments is not correct!', arguments);
   		}
    	userWrapper.get(token, userId, callback);
      });
      
      socket.on('createUser', function(userRaw, callback){
    	userWrapper.create(token, userRaw, callback)
      });
      
      socket.on('replaceUser', function(userId, userRaw, callback){
        userWrapper.replace(token, userId, userRaw, callback);
      });
        
      socket.on('patchUser', function(userId, patch, callback){
        userWrapper.patch(token, userId, patch, callback);
      });
      
      socket.on('removeUser', function(userId, callback){
        userWrapper.remove(token, userId, callback);
      });

      socket.on('resetPassword', function(userId, newPassword, callback){
        userWrapper.resetPassword(token, userId, newPassword, callback);
      });
      
      socket.on('getUserACL', function(userId, callback){
        userWrapper.getACL(token, userId, callback);
      });

      socket.on('replaceUserACL', function(userId, acl, callback){
        userWrapper.replaceACL(token, userId, acl, callback);
      });
      
      socket.on('patchUserACL', function(userId, aclPatch, callback){
        userWrapper.patchACL(token, userId, aclPatch, callback);
      });
        
      socket.on('removeUserPermissionSubject', function(userId, acl, callback){
        userWrapper.removePermissionSubject(token, userId, acl, callback);
      });
          
      socket.on('createDomain', function(domainId, domainRaw, callback){
       	domainWrapper.create(token, domainId, domainRaw, callback);
      });
            
      socket.on('getDomain', function(domainId, callback){
      	domainWrapper.get(token, domainId, callback);
      });
            
      socket.on('replaceDomain', function(domainId, domainRaw, callback){
       	domainWrapper.replace(token, domainId, domainRaw, callback);
      });

      socket.on('patchDomain', function(domainId, patch, callback){
       	domainWrapper.patch(token, domainId, patch, callback);
      });
      
      socket.on('removeDomain', function(domainId, callback){
       	domainWrapper.remove(token, domainId, callback);
      });
      
      socket.on('getDomainACL', function(domainId, callback){
       	domainWrapper.getACL(token, domainId, callback);
      });

      socket.on('replaceDomainACL', function(domainId, acl, callback){
       	domainWrapper.replaceACL(token, domainId, acl, callback);
      });
      
      socket.on('patchDomainACL', function(domainId, aclPatch, callback){
       	domainWrapper.patchACL(token, domainId, aclPatch, callback);
      });
      
      socket.on('domainGarbageCollection', function(domainId, callback){
       	domainWrapper.garbageCollection(token, domainId, callback);
      });
      
      socket.on('joinDomain', function(domainId, userId, permission, callback){
       	domainWrapper.join(token, domainId, userId, permission, callback);
      });

      socket.on('quitDomain', function(domainId, userId, callback){
       	domainWrapper.quit(token, domainId, userId, callback);
      });

      socket.on('findCollections', function(domainId, callback){
      	domainWrapper.findCollections(token, domainId, callback);
      });
      
      socket.on('createCollection', function(domainId, collectionId, collectionRaw, callback){
    	collectionWrapper.create(token, domainId, collectionId, collectionRaw, callback);
      });
        
      socket.on('getCollection', function(domainId, collectionId, callback){
      	collectionWrapper.get(token, domainId, collectionId, callback);
      });

      socket.on('replaceCollection', function(domainId, collectionId, collectionRaw, callback){
        collectionWrapper.replace(token, domainId, collectionId, collectionRaw, callback);
      });

      socket.on('patchCollection', function(domainId, collectionId, patch, callback){
        collectionWrapper.patch(token, domainId, collectionId, patch, callback);
      });

      socket.on('removeCollection', function(domainId, collectionId, callback){
        collectionWrapper.remove(token, domainId, collectionId, callback);
      });

      socket.on('getCollectionACL', function(domainId, collectionId, callback){
        collectionWrapper.getACL(token, domainId, collectionId, callback);
      });

      socket.on('replaceCollectionACL', function(domainId, collectionId, acl, callback){
        collectionWrapper.replaceACL(token, domainId, collectionId, acl, callback);
      });
      
      socket.on('patchCollectionACL', function(domainId, collectionId, aclPatch, callback){
        collectionWrapper.patchACL(token, domainId, collectionId, aclPatch, callback);
      });
        
      socket.on('removeCollectionPermissionSubject', function(domainId, collectionId, acl, callback){
        collectionWrapper.removePermissionSubject(token, domainId, collectionId, acl, callback);
      });

      socket.on('findCollectionDocuments', function(domainId, collectionId, query, callback){
        collectionWrapper.findDocuments(token, domainId, collectionId, query, callback);
      });
      
      socket.on('refreshCollection', function(domainId, collectionId, callback){
        collectionWrapper.refresh(token, domainId, collectionId, callback);
      });

      socket.on('findViews', function(domainId, callback){
       	domainWrapper.findViews(token, domainId, callback);
      });
        
      socket.on('createView', function(domainId, viewId, viewRaw, callback){
      	viewWrapper.create(token, domainId, viewId, viewRaw, callback);
      });
          
      socket.on('getView', function(domainId, viewId, callback){
      	viewWrapper.get(token, domainId, viewId, callback);
      });

      socket.on('replaceView', function(domainId, viewId, viewRaw, callback){
        viewWrapper.replace(token, domainId, viewId, viewRaw, callback);
      });

      socket.on('patchView', function(domainId, viewId, patch, callback){
        viewWrapper.patch(token, domainId, viewId, patch, callback);
      });

      socket.on('removeView', function(domainId, viewId, callback){
        viewWrapper.remove(token, domainId, viewId, callback);
      });

      socket.on('getViewACL', function(domainId, viewId, callback){
        viewWrapper.getACL(token, domainId, viewId, callback);
      });

      socket.on('replaceViewACL', function(domainId, viewId, acl, callback){
        viewWrapper.replaceACL(token, domainId, viewId, acl, callback);
      });
        
      socket.on('patchViewACL', function(domainId, viewId, aclPatch, callback){
        viewWrapper.patchACL(token, domainId, viewId, aclPatch, callback);
      });
          
      socket.on('removeViewPermissionSubject', function(domainId, viewId, acl, callback){
        viewWrapper.removePermissionSubject(token, domainId, viewId, acl, callback);
      });

      socket.on('findViewDocuments', function(domainId, viewId, query, callback){
        viewWrapper.findDocuments(token, domainId, viewId, query, callback);
      });
 
      socket.on('refreshView', function(domainId, viewId, callback){
        viewWrapper.refresh(token, domainId, viewId, callback);
      });
      
      socket.on('createDocument', function(domainId, collectionId, docId, docRaw, callback){
    	documentWrapper.create(token, domainId, collectionId, docId, docRaw, callback);
      });

      socket.on('getDocument', function(domainId, collectionId, docId, callback){
      	documentWrapper.get(token, domainId, collectionId, docId, callback);
      });
      
      socket.on('replaceDocument', function(domainId, collectionId, docId, docRaw, callback){
      	documentWrapper.replace(token, domainId, collectionId, docId, docRaw, callback);
      });

      socket.on('patchDocument', function(domainId, collectionId, docId, patch, callback){
      	documentWrapper.patch(token, domainId, collectionId, docId, patch, callback);
      });
      
      socket.on('removeDocument', function(domainId, collectionId, docId, callback){
       	documentWrapper.remove(token, domainId, collectionId, docId, callback);
      });

      socket.on('getDocumentACL', function(domainId, collectionId, docId, callback){
       	documentWrapper.getACL(token, domainId, collectionId, docId, callback);
      });

      socket.on('replaceDocumentACL', function(domainId, collectionId, docId, acl, callback){
       	documentWrapper.replaceACL(token, domainId, collectionId, docId, acl, callback);
      });

      socket.on('patchDocumentACL', function(domainId, collectionId, docId, aclPatch, callback){
       	documentWrapper.patchACL(token, domainId, collectionId, docId, aclPatch, callback);
      });

      socket.on('removeDocumentPermissionSubject', function(domainId, collectionId, docId, acl, callback){
       	documentWrapper.removePermissionSubject(token, domainId, collectionId, docId, acl, callback);
      });

      socket.on('disconnect', function(){
        console.log('%s disconnected.', token);
      });
    });
      
    chat.connect(io);
    
    return io;
  }
};

module.exports = io;
// ----------------- END PUBLIC METHODS -------------------
