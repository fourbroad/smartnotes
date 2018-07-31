/*
 * login.js
 * Login feature module
 */

/*jslint         browser : true, continue : true,
  devel  : true, indent  : 2,    maxerr   : 50,
  newcap : true, nomen   : true, plusplus : true,
  regexp : true, sloppy  : true, vars     : false,
  white  : true
 */

define(['client', 'validate','underscore', 'util', 'jquery', 'bootstrap', 'bootstrap-dialog'], function(Client, validate, _, utils, $, none, BootstrapDialog) {
  'use strict';

  var
    title ='Login',
    content = String()
      + '<div class="container-fluid">'
        + '<div class="alert alert-warning alert-dismissible hidden" role="alert">'
          + '<button type="button" class="close" aria-label="Close"><span aria-hidden="true">&times;</span></button>'
          + '<strong>Warning: </strong><p class="alert-content" style="display: inline-block;"></p>'
        + '</div>'
        + '<form class="login form-horizontal" action="/example" method="post" novalidate>'
          + '<div class="form-group">'
            + '<label class="col-sm-2 control-label" for="username">Username</label>'
            + '<div class="col-sm-5">'
              + '<input id="username" class="form-control" type="text" placeholder="Username" name="username">'
            + '</div>'
            + '<div class="col-sm-5 messages"></div>'
          + '</div>'
          + '<div class="form-group">'
            + '<label class="col-sm-2 control-label" for="password">Password</label>'
            + '<div class="col-sm-5">'
              + '<input id="password" class="form-control" type="password" placeholder="Password" name="password">'
            + '</div>'
            + '<div class="col-sm-5 messages"></div>'
          + '</div>'
          + '<div class="form-group hidden">'
            + '<div class="col-sm-offset-2 col-sm-10">'
              + '<button type="submit" class="btn btn-default">Submit</button>'
            + '</div>'
          + '</div>'
        + '</form>'
      + '</div>',      
    constraints = {         // These are the constraints used to validate the form
        username: {
          presence: true,     // You need to pick a username too
          length: {           // And it must be between 3 and 20 characters long
            minimum: 3,
            maximum: 20
          },
          format: {
            pattern: "[a-z0-9]+",  // We don't allow anything that a-z and 0-9
            flags: "i",            // but we don't care if the username is uppercase or lowercase
            message: "can only contain a-z and 0-9"
          }
        },
        password: {
          presence: true,     // Password is also required
          length: {           // And must be at least 5 characters long
            minimum: 5
          }
        }
      },
    defaultOptions = {},
    options = {},
    initialized = false,
    $form, $loginButton, $alert, $alertContent, $alertHideBtn, dialog,

    _init, _onSubmit, _disableSubmit, _enableSubmit, _onHideAlert,
    _showErrorsForInput, _showAlertMessage, _closeAlertMessage, 
    showDialog;

  _init = function(){
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

    dialog = new BootstrapDialog({
      title: title,
      message: function(dialog) { return $(content);},
      buttons: [{id: 'login', label: 'Login'}]
    });

    initialized = true;
  };  

  _onSubmit = function(ev) {
    var o;

    ev.preventDefault();
    ev.stopPropagation();

    o = utils.extend(true, {}, defaultOptions, options);

    // validate the form aainst the constraints
    var errors = validate($form, constraints);
    // then we update the form to reflect the results
    if(errors){
      $form.find("input[name], select[name]").each(function(){
          _closeAlertMessage();        
        // Since the errors can be null if no errors were found we need to handle that
        _showErrorsForInput($(this), errors[this.name]);
      });
    }else {
      var loginData = validate.collectFormValues($form, {trim:true});
      _disableSubmit();
      Client.login(loginData.username,loginData.password, function(err, client){
        if(err){
          _showAlertMessage('alert-danger', err.message);
          _enableSubmit();          
          if(o.submitCallback) o.submitCallback(err);
        } else {
          _enableSubmit();
          _closeAlertMessage();
          dialog.close();
          if(o.submitCallback) o.submitCallback(null, client);
        }
      });
    }
  };

  _disableSubmit = function(){
    $loginButton.disable();
    $loginButton.spin();
    dialog.setClosable(false);
  };

  _enableSubmit = function(){
    $loginButton.enable();
    $loginButton.stopSpin();
    dialog.setClosable(true);    
  };

  _showAlertMessage = function(type, message){
    $alert.removeClass('hidden alert-success alert-info alert-warning alert-danger');
    $alert.addClass(type);
    $alertContent.html(message);
  };

  _closeAlertMessage = function(){
    $alert.addClass('hidden');
  };

  _onHideAlert = function(){
    $alert.addClass('hidden');
  };

  _showErrorsForInput = function($input, errors){
    var 
      $formGroup = $input.closest(".form-group"), // This is the root of the input
      $messages = $formGroup.find(".messages"); // Find where the error messages will be insert into
      
    // Remove the success and error classes
    $formGroup.removeClass("has-error has-success");
    // and remove any old messages
    $formGroup.find(".help-block.error").each(function(){
      $(this).remove();
    });

    // If we have errors
    if (errors) {
      // we first mark the group has having errors
      $formGroup.addClass("has-error");
      // then we append all the errors
      _.each(errors, function(error){
        $("<p/>").addClass("help-block error").text(error).appendTo($messages);
      });
    } else { // otherwise we simply mark it as success
      $formGroup.addClass("has-success");
    }
  };

  /**
   * Show login dialog.
   *
   * @param none
   * @return none
   * @throws none
   */
  showDialog = function(){
    var $modal;

	if(arguments.length == 1 && typeof arguments[0] == 'object'){
	  options = arguments[0];
	} else if(arguments.length == 0){
	  options = {};
	} else {
	  throw utils.makeError('Error', 'Number or type of arguments is not correct!', arguments);
	}

    if(!initialized) _init();
    dialog.realize();    

    $modal = dialog.getModal();
    $form = $modal.find('form.login');
    $loginButton = dialog.getButton('login');
    $alert = $modal.find('.alert');
    $alertContent = $modal.find('.alert-content');
    $alertHideBtn = $modal.find('button.close');


    var $inputs = $form.find("input, textarea, select")
    $inputs.each(function(){
      $(this).bind("change", function(ev){
        var errors = validate($form, constraints) || {};
        _showErrorsForInput($(this), errors[this.name])
      });
    });
        
    $loginButton.bind('utap', _onSubmit);
    $form.bind('submit', _onSubmit);
    $alertHideBtn.bind('click', _onHideAlert);

    dialog.open();
  };

  return {
    showDialog  : showDialog
  };
});