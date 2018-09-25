import Client from 'client';
import utils from 'utils';
import validate from "validate.js";
import 'jquery.event.ue';
import 'jquery.event.gevent';

import accountHtml from './account.html';

const 
  constraints = {
  // These are the constraints used to validate the form
  username: {
    presence: true,
    // You need to pick a username too
    length: {
      // And it must be between 3 and 20 characters long
      minimum: 3,
      maximum: 20
    },
    format: {
      pattern: "[a-z0-9]+",
      // We don't allow anything that a-z and 0-9
      flags: "i",
      // but we don't care if the username is uppercase or lowercase
      message: "can only contain a-z and 0-9"
    }
  },
  password: {
    presence: true,
    // Password is also required
    length: {
      // And must be at least 5 characters long
      minimum: 5
    }
  }
};

var
  $account = $(accountHtml),
  $container,  $avatar, $nickname, $profileMenu, $loginMenu, $dropdownToggle, $signOff,
  $form, $loginButton, $alert, $alertContent, $alertHideBtn,
  _onSubmit, _disableSubmit, _enableSubmit, _onHideAlert,
  _showAlertMessage, _closeAlertMessage,
  _refresh, _onSignoff, _onClientChanged,
  client, init;

_onSignoff = function(event){
  event.preventDefault();
  event.stopPropagation();

  client.logout(function(){
    Client.login(function(err, client){
      $.gevent.publish('clientChanged', client);
    });
  });
  
};

_refresh = function(){
  if(client.currentUser.isAnonymous()){
    $profileMenu.detach();
    $loginMenu.appendTo($account);
    $nickname.text('Please sign-in');
  } else {
    $loginMenu.detach();
    $profileMenu.appendTo($account);
    $nickname.text(client.currentUser.id);
  }
};

_onClientChanged = function(event, c){
  client = c;
  _refresh();
};

_onSubmit = function(ev) {
  ev.preventDefault();
  ev.stopPropagation();

  // validate the form aginst the constraints
  var errors = validate($form, constraints);
  // then we update the form to reflect the results
  if (errors) {
    $form.find("input[name], select[name]").each(function() {
      _closeAlertMessage();
      // Since the errors can be null if no errors were found we need to handle that
      utils.showErrorsForInput($(this), errors[this.name]);
    });
  } else {
    var loginData = validate.collectFormValues($form, {
      trim: true
    });
    _disableSubmit();
    Client.login(loginData.username, loginData.password, function(err, client) {
      if (err) {
        _showAlertMessage('alert-danger', err.message);
        _enableSubmit();
      } else {
        _enableSubmit();
        _closeAlertMessage();
        $.gevent.publish('clientChanged', client);
      }
    });
  }
};

_disableSubmit = function() {
  if (!$loginButton.data('normal-text')) {
    $loginButton.data('normal-text', $loginButton.html());
  }

  $loginButton.html($loginButton.data('loading-text'));
  $loginButton.addClass("disabled");
  $loginButton.attr({'aria-disabled': true});
};

_enableSubmit = function() {
  $loginButton.html($loginButton.data('normal-text'));
  $loginButton.removeClass("disabled");
  $loginButton.removeAttr("aria-disabled");
};

_showAlertMessage = function(type, message) {
  $alert.removeClass('d-none alert-success alert-info alert-warning alert-danger');
  $alert.addClass(type);
  $alertContent.html(message);
};

_closeAlertMessage = function() {
  $alert.addClass('d-none');
};

_onHideAlert = function() {
  $alert.addClass('d-none');
};

/**
 * init account menu.
 *
 * @param none
 * @return none
 * @throws none
 */
init = function(options) {
  $container = options.$container;
  client = options.client;

  // Before using it we must add the parse and format functions
  // Here is a sample implementation using moment.js
  validate.extend(validate.validators.datetime, {
    // The value is guaranteed not to be null or undefined but otherwise it
    // could be anything.
    parse: function(value, options) {
      return +moment.utc(value);
    },
    // Input is a unix timestamp
    format: function(value, options) {
      var format = options.dateOnly ? "YYYY-MM-DD" : "YYYY-MM-DD hh:mm:ss";
      return moment.utc(value).format(format);
    }
  });

  $container.append($account);
  $avatar = $account.find('.avatar');
  $nickname = $account.find('.nickname');
  $dropdownToggle = $account.find('.dropdown-toggle');
  $profileMenu = $account.find('.profile-menu');
  $signOff = $profileMenu.find('.sign-off');

  $loginMenu = $account.find('.login-menu');
  $form = $loginMenu.find('form');
  $loginButton = $loginMenu.find('#login.btn');

  $alert = $loginMenu.find('.alert');
  $alertContent = $alert.find('.alert-content');
  $alertHideBtn = $alert.find('button.close');

  $("input, textarea, select", $form).each(function() {
    $(this).bind("change", function(ev) {
      var errors = validate($form, constraints) || {};
      utils.showErrorsForInput($(this), errors[this.name]);
    });
  });

  $loginButton.bind('utap', _onSubmit);
  $form.bind('submit', _onSubmit);
  $alertHideBtn.bind('click', _onHideAlert);

  _refresh();
    
  $signOff.bind('utap', _onSignoff);
  $.gevent.subscribe($container, 'clientChanged',  _onClientChanged);
};

export default {
  init: init
};
