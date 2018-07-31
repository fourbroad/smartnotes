/*
 * im.js
 * Instant message module
 */

/*jslint         browser : true, continue : true,
  devel  : true, indent  : 2,    maxerr   : 50,
  newcap : true, nomen   : true, plusplus : true,
  regexp : true, sloppy  : true, vars     : false,
  white  : true
 */

define(['socket.io','taffydb'], function(io, TAFFY) {
  'use strict';

  var
    configMap = { },
    stateMap  = {
      anon_user      : null,
      cid_serial     : 0,
      is_connected   : false,
      people_cid_map : {},
      people_db      : TAFFY(),
      user           : null,
      token          : null,
      chatSocket     : null
    },

    personProto, makeCid, clearPeopleDb,
    makePerson, removePerson, getUser,

    _publish_listchange, _publish_updatechat,
    _update_list, _leave_chat,

    getDB, get_by_cid,

    get_chatee, join_chat, send_msg,
    set_chatee, update_avatar,

    chatee = null,
    
    _emitMessage, _chatMessage, init;

  personProto = {};

  makeCid = function(){
    return 'c' + String(stateMap.cid_serial++);
  };

  clearPeopleDb = function() {
    var user = stateMap.user;
    stateMap.people_db      = TAFFY();
    stateMap.people_cid_map = {};
    if ( user ) {
      stateMap.people_db.insert( user );
      stateMap.people_cid_map[ user.cid ] = user;
    }
  };

  getUser = function(){
  	return stateMap.user;
  };

  makePerson = function(person_map){
    var person;
    
    if(person_map.cid === undefined || !person_map.name){
      throw 'client id and name required';
    }
    
    person         = Object.create(personProto);
    person.cid     = person_map.cid;
    person.name    = person_map.name;
    person.css_map = person_map.css_map;
    person.token   = person_map.token;

    if(person_map.id){ person.id = person_map.id; }

    stateMap.people_cid_map[person.cid] = person;
    stateMap.people_db.insert(person);
    
    return person;
  };

  removePerson = function(person){
    if(!person){ return false; }

    stateMap.people_db({cid : person.cid}).remove();
    if(person.cid){
      delete stateMap.people_cid_map[person.cid];
    }
    return true;
  };

  _chatMessage = function(callback){
    if(!stateMap.chatSocket){
     stateMap.chatSocket = io.connect('/chat?token='+stateMap.token);
    }

    if(stateMap.chatSocket.disconnected){
     stateMap.chatSocket.connect().on('connect', function(){
       callback(stateMap.chatSocket);
     });
    }else{
      callback(stateMap.chatSocket);
    }
  };
  
  _update_list = function( arg_list ) {
    var i, person_map, make_person_map, person, is_chatee_online = false;

    clearPeopleDb();

    for(i = 0; i < arg_list.length; i++){
      person_map = arg_list[i];
      if( person_map.name ) {
        // if user defined, update css_map and skip remainder
        if(stateMap.user && stateMap.user.id === person_map._id) {
          stateMap.user.css_map = person_map.css_map;
        } else {
          make_person_map = {
          　　cid     : person_map._id,
            css_map : person_map.css_map,
            id      : person_map._id,
            name    : person_map.name
          };
            
          person = makePerson(make_person_map);
          if (chatee && chatee.id === make_person_map.id ) {
          　　is_chatee_online = true;
          　　chatee = person;
          }
        }
      }
    }

    stateMap.people_db.sort('name');

    // If chatee is no longer online, we unset the chatee which triggers the 'spa-setchatee' global event
    if(chatee && !is_chatee_online){set_chatee('');}
  };

  getDB = function() { return stateMap.people_db; };

  _publish_listchange = function(arg_list){
    _update_list(arg_list);
    $.gevent.publish('spa-listchange', arg_list);
  };

  _publish_updatechat = function(msg_map){
    if (!chatee) { 
      set_chatee( msg_map.sender_id ); 
    } else if ( msg_map.sender_id !== stateMap.user.id && msg_map.sender_id !== chatee.id) { 
      set_chatee( msg_map.sender_id ); 
    }

    $.gevent.publish('spa-updatechat', [msg_map] );
  };

  _leave_chat = function(){
    var user = stateMap.user;
    stateMap.user = stateMap.anon_user;
    clearPeopleDb();
      
    chatee  = null;
    stateMap.is_connected = false;
      
    if(stateMap.chatSocket){
      stateMap.chatSocket.emit( 'leavechat' );
      stateMap.chatSocket.off('listchange');
      stateMap.chatSocket.off('updatechat');
    }
  };

  get_chatee = function(){return chatee;};

  join_chat  = function(user){
    if(stateMap.is_connected) { return false; }
    
    stateMap.user = makePerson({
      cid     : makeCid(),
      css_map : {top:25, left:25, 'background-color':'#8f8'},
      name    : user.id
    });
      
    _chatMessage(function(chatSocket){
      chatSocket.on('userupdate', function(user_map){
          delete stateMap.people_cid_map[user_map.cid];
          
          stateMap.user.cid     = user_map._id;
          stateMap.user.id      = user_map._id;
          stateMap.user.css_map = user_map.css_map;
            
          stateMap.people_cid_map[user_map._id] = stateMap.user;
      });

      chatSocket.emit('adduser', {
        cid     : stateMap.user.cid,
        css_map : stateMap.user.css_map,
        name    : stateMap.user.name
      });
        
      chatSocket.on('listchange', _publish_listchange);
      chatSocket.on('updatechat', _publish_updatechat);
      stateMap.is_connected = true;
    });
  };

  send_msg = function(msg_text){
    var msg_map;

    if(!stateMap.chatSocket){return false;}
    if(!(stateMap.user && chatee)) { return false; }

    msg_map = {
      dest_id   : chatee.id,
      dest_name : chatee.name,
      sender_id : stateMap.user.id,
      msg_text  : msg_text
    };

    // we published updatechat so we can show our outgoing messages
    _publish_updatechat(msg_map);
    stateMap.chatSocket.emit('updatechat', msg_map);
    return true;
  };

  set_chatee = function(person_id){
    var new_chatee = stateMap.people_cid_map[person_id];
    if (new_chatee) {
      if(chatee && chatee.id === new_chatee.id){
        return false;
      }
    } else {
      new_chatee = null;
    }

    $.gevent.publish('spa-setchatee', {old_chatee: chatee, new_chatee: new_chatee});
    chatee = new_chatee;
    return true;
  };

  update_avatar = function(avatar_update_map){
    if(stateMap.chatSocket){
      stateMap.chatSocket.emit('updateavatar', avatar_update_map);
    }
  };

  get_by_cid = function(cid){
    return stateMap.people_cid_map[cid];
  }

  init = function(token) {
    stateMap.token = token;
  };

  return {
    init          : init,
    getUser       : getUser,
    get_chatee    : get_chatee,
    join          : join_chat,
    leave         : _leave_chat,
    send_msg      : send_msg,
    getDB         : getDB,
    set_chatee    : set_chatee,
    get_by_cid    : get_by_cid,
    update_avatar : update_avatar
  };
});
