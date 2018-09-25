/*
 * views.ui.js
 * views user interface.
 */

/* jslint         browser : true, continue : true,
   devel  : true, indent  : 2,    maxerr   : 50,
   newcap : true, nomen   : true, plusplus : true,
   regexp : true, sloppy  : true, vars     : false,
   white  : true
 */

define(['underscore','jquery', 'bootstrap'], function(_, $){
  'use strict';
  
  const
    contentHtml = String() + '<div class="list-group"></div>',
	alertHtml = String()
	  + '<div class="alert alert-warning alert-dismissible" role="alert">'
        + '<button type="button" class="close" data-dismiss="alert" aria-label="Close"><span aria-hidden="true">&times;</span></button>'
        + '<strong>Warning: </strong><p class="alert-content" style="display: inline-block;"></p>'
      + '</div>';
  var      
    domain,
    $container, $listGroup,    
    _onTapNav, _onChangeClient, _clearActive, _showAlertMessage, _armListGroupItem,
    openView, init;

  _onTapNav = function (event){
  	var listGroupItem = $(event.elem_target).closest('a.list-group-item');
  	_clearActive();
  	listGroupItem.addClass('active');
  	openView(listGroupItem.data('item'));
  };

  _onChangeClient = function (event, client){
  	if(client.currentUser.isAnonymous())
  	    $container.empty();
  };

  _clearActive = function(){
  	$container.find('a.active').removeClass('active');
  };

  _showAlertMessage = function(message){
  	$container.find('div.alert').alert('close');
  	$container.prepend(alertHtml);
  	$container.find('p.alert-content').html(message);
  };

  _armListGroupItem = function(heading, text){
  	var item = String() + '<a class="list-group-item"><h4 class="list-group-item-heading">' + heading + '</h4>'
	if(text)
	  item = item + '<p class="list-group-item-text">' + text +'</p>'
	item = item + '</a>';
	return item;
  };

  init = function(options){
  	domain = options.domain;
  	$container = options.$container;
  	openView = options.openView;

    $listGroup = $(contentHtml).appendTo($container);

    domain.findCollections(function(err, collections){
      if(err) return _showAlertMessage(err.message);
      _.each(collections, function(collection){
      	$(_armListGroupItem(collection.id)).data('item', collection).appendTo($listGroup);
      });
    });

    $.gevent.subscribe($container, 'changeClient', _onChangeClient);

    $container.bind('utap', _onTapNav);

    return true;
  };

  return {
    init   : init
  };
});
