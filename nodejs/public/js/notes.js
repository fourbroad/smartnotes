/*
 * notes.js
 * notes module
 */

/*jslint         browser : true, continue : true,
  devel  : true, indent  : 2,    maxerr   : 50,
  newcap : true, nomen   : true, plusplus : true,
  regexp : true, sloppy  : true, vars     : false,
  white  : true
 */

define(['client','shell', 'jquery'], function(Client, shell, $) {
  'use strict';

  const $container = $("#notes");
  var currentClient, getCurrentClient;

  if(localStorage.token){
  	Client.connect(localStorage.token, function(err, client){
  	  if(err) return console.log(err);
  	  currentClient = client;
  	  shell.init($container, client);
  	});
  } else {
  	Client.login(function(err, client){
  	  if(err) return console.log(err);
  	  currentClient = client;
  	  shell.init($container, client);
  	});
  }

  $.gevent.subscribe($container, 'changeClient', function(event, client){
  	if(client.currentUser.isAnonymous()){
  	  localStorage.removeItem('token');
  	}else{
  	  localStorage.setItem('token', client.token);  		
  	}
  });

  getCurrentClient = function(){
  	return currentClient;
  };

  return {
    getCurrentClient : getCurrentClient
  };
});
