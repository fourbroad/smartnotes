import Client from 'client';
import utils from 'client/utils';
import validate from "validate.js";
import * as $ from 'jquery';
import 'jquery.event.ue';

import signinHtml from './signin.html';

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
  defaultOptions = {}, options = {}, 
  $container, $form, $loginButton, $alert, $alertContent, $alertHideBtn, 
  init, _onSubmit, _disableSubmit, _enableSubmit, _onHideAlert, _showErrorsForInput, 
  _showAlertMessage, _closeAlertMessage;

/**
 * init login form.
 *
 * @param none
 * @return none
 * @throws none
 */
init = function(opts) {
  options = opts;
  $container = options.$container;

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

  $container.html(signinHtml);
  $form = $container.find('form.login');
  $loginButton = $container.find('#login.btn');

  $alert = $container.find('.alert');
  $alertContent = $alert.find('.alert-content');
  $alertHideBtn = $alert.find('button.close');

  var $inputs = $form.find("input, textarea, select")
  $inputs.each(function() {
    $(this).bind("change", function(ev) {
      var errors = validate($form, constraints) || {};
      _showErrorsForInput($(this), errors[this.name])
    });
  });

  $loginButton.bind('utap', _onSubmit);
  $form.bind('submit', _onSubmit);
  $alertHideBtn.bind('click', _onHideAlert);
};

_onSubmit = function(ev) {
  var o;

  ev.preventDefault();
  ev.stopPropagation();

  o = utils.extend(true, {}, defaultOptions, options);

  // validate the form aginst the constraints
  var errors = validate($form, constraints);
  // then we update the form to reflect the results
  if (errors) {
    $form.find("input[name], select[name]").each(function() {
      _closeAlertMessage();
      // Since the errors can be null if no errors were found we need to handle that
      _showErrorsForInput($(this), errors[this.name]);
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
        if (o.submitCallback)
          o.submitCallback(err);
      } else {
        _enableSubmit();
        _closeAlertMessage();
        if (o.submitCallback)
          o.submitCallback(null, client);
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
  $loginButton.attr({
    'aria-disabled': true
  });
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

_showErrorsForInput = function($input, errors) {
  var $formGroup = $input.closest(".form-group")
    , // This is the root of the input
  $messages = $formGroup.find(".messages");
  // Find where the error messages will be insert into

  // Remove the success and error classes
  $formGroup.removeClass("alert-danger alert-success");
  // and remove any old messages
  $formGroup.find(".help-block.alert").each(function() {
    $(this).remove();
  });

  // If we have errors
  if (errors) {
    // we first mark the group has having errors
    $formGroup.addClass("alert-danger");
    // then we append all the errors
    _.each(errors, function(error) {
      $("<p/>").addClass("help-block alert").text(error).appendTo($messages);
    });
  } else {
    // otherwise we simply mark it as success
    $formGroup.addClass("alert-success");
  }
};

export default {
  init: init
};
