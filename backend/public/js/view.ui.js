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

define(['underscore','jquery', 'util', 'bootstrap'], function(_, $, util){
  'use strict';
  
  const
    defaultOptions = {},
    contentHtml = String()
      + '<div class="panel panel-default">'
        + '<div class="panel-heading">'
          + '<h3 class="panel-title"></h3>'
        + '</div>'
        + '<div style="width:100%;max-height:800px;overflow-y:scroll;">'
          + '<table class="table"><thead/><tbody/></table>'
        + '</div.'        
      + '</div>',
    alertHtml = String()
	  + '<div class="alert alert-warning alert-dismissible" role="alert">'
        + '<button type="button" class="close" data-dismiss="alert" aria-label="Close"><span aria-hidden="true">&times;</span></button>'
        + '<strong>Warning: </strong><p class="alert-content" style="display: inline-block;"></p>'
      + '</div>';

  var 
    options = {
  	  $container: null,
  	  view: null
    },
    $head, $query, $tHead, $tBody, $title,
    _onTapRaw, _onChangeClient, _clearActive, _showAlertMessage, _armTableRow,
    _init,
    viewUIProto, create, destroy;

  _onTapRaw = function (event){
  	var $row = $(event.elem_target).closest('tr');
  	_clearActive();
  	$row.addClass('active');
  	_showAlertMessage(JSON.stringify($row.data("row")));
  };

  _onChangeClient = function (event, client){
  	if(client.currentUser.isAnonymous())
  	    $container.empty();
  };

  _clearActive = function(){
  	var $container = options.$container;
  	$container.find('tr.active').removeClass('active');
  };

  _showAlertMessage = function(message){
    var $container = options.$container;
  	$container.find('div.alert').alert('close');
  	$container.prepend(alertHtml);
  	$container.find('p.alert-content').html(message);
  };

  _armTableRow = function(document){
  	var item = String() + '<tr><td>'+ JSON.stringify(document)+'</td></tr>';
	return item;
  };

  _init = function(opts){
  	var $container, view;

    util.extend(true, options, defaultOptions, opts);

    $container = options.$container;
    view = options.view;

  	$container.html(contentHtml);
  	$head = $container.find('.panel-heading');
  	$title = $head.find('.panel-title');
//   	$query = $container.find('.panel-body');
  	$tHead = $container.find('.table>thead');
  	$tBody = $container.find('.table>tbody');

    $title.html(view.id);
    view.findDocuments({}, function(err, documents){
      if(err) return _showAlertMessage(err.message);
      _.each(documents.hits, function(document){
      	$(_armTableRow(document._source)).data('row', document).appendTo($tBody);
      });
    });

    $.gevent.subscribe($container, 'changeClient', _onChangeClient);

    $tBody.bind('utap', _onTapRaw);

    return true;
  };

  destroy = function(){

  };

  viewUIProto = {
  	destroy: destroy
  };

  create = function(options){
    _init(options);
	return Object.create(viewUIProto);
  };

  return {
    create : create
  };
});
