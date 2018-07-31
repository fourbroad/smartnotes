import Client from 'client';
import * as $ from 'jquery';
import 'jquery.event.ue';
import 'jquery.event.gevent';

import accountHtml from './account.html';

var
  $account = $(accountHtml),
  $container,  $avatar, $nickname, $dropdownMenu, $dropdownToggle, $signOff,
  _onSignoff, _onClientChanged, _onClickAccount,
  client, init, options;

_onSignoff = function(event){
  event.preventDefault();
  event.stopPropagation();

  client.logout(function(){
    Client.login(function(err, client){
      $.gevent.publish('clientChanged', client);
    });
  });
  
};

_onClientChanged = function(event, c){
  var isAnonymous;

  client = c;
  isAnonymous = client.currentUser.isAnonymous();

  if(isAnonymous){
    $dropdownMenu.detach();
    $dropdownToggle.removeAttr('data-toggle');
    $nickname.text('Please sign-in');
  } else {
    $dropdownMenu.appendTo($account);
    $dropdownToggle.attr({'data-toggle':'dropdown'});
    $nickname.text(client.currentUser.id);
  }
};

_onClickAccount = function(event){
  var isAnonymous = !client || client.currentUser.isAnonymous();

  if(isAnonymous){
//     event.preventDefault();
//     event.stopPropagation();

    $.gevent.publish('signInClicked');
  }

}

/**
 * init account menu.
 *
 * @param none
 * @return none
 * @throws none
 */
init = function(opts) {
  options = opts;
  $container = options.$container;
  $container.append($account);
  $avatar = $account.find('.avatar');
  $nickname = $account.find('.nickname');
  $dropdownToggle = $account.find('.dropdown-toggle');
  $dropdownMenu = $account.find('.dropdown-menu');
  $signOff = $dropdownMenu.find('.sign-off');
  
  $account.bind('utap', _onClickAccount);
  $signOff.bind('utap', _onSignoff);
  $.gevent.subscribe($container, 'clientChanged',  _onClientChanged);
};

export default {
  init: init
};
