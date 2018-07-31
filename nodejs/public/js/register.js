/*
 * spa.register.js
 * Register feature module for SPA
 */

/*jslint         browser : true, continue : true,
  devel  : true, indent  : 2,    maxerr   : 50,
  newcap : true, nomen   : true, plusplus : true,
  regexp : true, sloppy  : true, vars     : false,
  white  : true
*/

/*global $, spa */

define(['validate','util', 'jquery', 'bootstrap', 'bootstrap-dialog','moment'], function(validate, util, $, none, BootstrapDialog) {
  'use strict';

  //---------------- BEGIN MODULE SCOPE VARIABLES --------------
  var
    configMap = {
      main_html : String()
        + '<div class="container-fluid">'
          + '<form class="register form-horizontal" action="/example" method="post" novalidate>'
            + '<div class="form-group">'
              + '<label class="col-sm-2 control-label" for="email">Email</label>'
              + '<div class="col-sm-5">'
                + '<input id="email" class="form-control" type="email" placeholder="Email" name="email"/>'
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
            + '<div class="form-group">'
              + '<label class="col-sm-2 control-label" for="confirm-password">Confirm password</label>'
              + '<div class="col-sm-5">'
                + '<input id="confirm-password" class="form-control" type="password" placeholder="Confirm password" name="confirm-password">'
              + '</div>'
              + '<div class="col-sm-5 messages"></div>'
            + '</div>'
            + '<div class="form-group">'
              + '<label class="col-sm-2 control-label" for="username">Username</label>'
              + '<div class="col-sm-5">'
                + '<input id="username" class="form-control" type="text" placeholder="Username" name="username">'
              + '</div>'
              + '<div class="col-sm-5 messages"></div>'
            + '</div>'
            + '<div class="form-group">'
              + '<label class="col-sm-2 control-label" for="birthdate">Birthdate</label>'
              + '<div class="col-sm-5">'
                + '<input id="birthdate" class="form-control" type="date" placeholder="YYYY-MM-DD" name="birthdate">'
              + '</div>'
              + '<div class="col-sm-5 messages"></div>'
            + '</div>'
            + '<div class="form-group">'
              + '<label class="col-sm-2 control-label" for="country">Country</label>'
              + '<div class="col-sm-5">'
                + '<select id="country" class="form-control" name="country">'
                  + '<option value=""></option>'
                  + '<option value="AF">Afghanistan</option>'
                  + '<option value="AX">Åland Islands</option>'
                  + '<option value="AL">Albania</option>'
                  + '<option value="DZ">Algeria</option>'
                  + '<option value="AS">American Samoa</option>'
                  + '<option value="AD">Andorra</option>'
                  + '<option value="AO">Angola</option>'
                  + '<option value="AI">Anguilla</option>'
                  + '<option value="AQ">Antarctica</option>'
                  + '<option value="AG">Antigua and Barbuda</option>'
                  + '<option value="AR">Argentina</option>'
                  + '<option value="AM">Armenia</option>'
                  + '<option value="AW">Aruba</option>'
                  + '<option value="AU">Australia</option>'
                  + '<option value="AT">Austria</option>'
                  + '<option value="AZ">Azerbaijan</option>'
                  + '<option value="BS">Bahamas</option>'
                  + '<option value="BH">Bahrain</option>'
                  + '<option value="BD">Bangladesh</option>'
                  + '<option value="BB">Barbados</option>'
                  + '<option value="BY">Belarus</option>'
                  + '<option value="BE">Belgium</option>'
                  + '<option value="BZ">Belize</option>'
                  + '<option value="BJ">Benin</option>'
                  + '<option value="BM">Bermuda</option>'
                  + '<option value="BT">Bhutan</option>'
                  + '<option value="BO">Bolivia</option>'
                  + '<option value="BA">Bosnia and Herzegovina</option>'
                  + '<option value="BW">Botswana</option>'
                  + '<option value="BV">Bouvet Island</option>'
                  + '<option value="BR">Brazil</option>'
                  + '<option value="IO">British Indian Ocean Territory</option>'
                  + '<option value="BN">Brunei Darussalam</option>'
                  + '<option value="BG">Bulgaria</option>'
                  + '<option value="BF">Burkina Faso</option>'
                  + '<option value="BI">Burundi</option>'
                  + '<option value="KH">Cambodia</option>'
                  + '<option value="CM">Cameroon</option>'
                  + '<option value="CA">Canada</option>'
                  + '<option value="CV">Cape Verde</option>'
                  + '<option value="KY">Cayman Islands</option>'
                  + '<option value="CF">Central African Republic</option>'
                  + '<option value="TD">Chad</option>'
                  + '<option value="CL">Chile</option>'
                  + '<option value="CN">China</option>'
                  + '<option value="CX">Christmas Island</option>'
                  + '<option value="CC">Cocos (Keeling) Islands</option>'
                  + '<option value="CO">Colombia</option>'
                  + '<option value="KM">Comoros</option>'
                  + '<option value="CG">Congo</option>'
                  + '<option value="CD">Congo, The Democratic Republic of The</option>'
                  + '<option value="CK">Cook Islands</option>'
                  + '<option value="CR">Costa Rica</option>'
                  + '<option value="CI">Cote D\'ivoire</option>'
                  + '<option value="HR">Croatia</option>'
                  + '<option value="CU">Cuba</option>'
                  + '<option value="CY">Cyprus</option>'
                  + '<option value="CZ">Czech Republic</option>'
                  + '<option value="DK">Denmark</option>'
                  + '<option value="DJ">Djibouti</option>'
                  + '<option value="DM">Dominica</option>'
                  + '<option value="DO">Dominican Republic</option>'
                  + '<option value="EC">Ecuador</option>'
                  + '<option value="EG">Egypt</option>'
                  + '<option value="SV">El Salvador</option>'
                  + '<option value="GQ">Equatorial Guinea</option>'
                  + '<option value="ER">Eritrea</option>'
                  + '<option value="EE">Estonia</option>'
                  + '<option value="ET">Ethiopia</option>'
                  + '<option value="FK">Falkland Islands (Malvinas)</option>'
                  + '<option value="FO">Faroe Islands</option>'
                  + '<option value="FJ">Fiji</option>'
                  + '<option value="FI">Finland</option>'
                  + '<option value="FR">France</option>'
                  + '<option value="GF">French Guiana</option>'
                  + '<option value="PF">French Polynesia</option>'
                  + '<option value="TF">French Southern Territories</option>'
                  + '<option value="GA">Gabon</option>'
                  + '<option value="GM">Gambia</option>'
                  + '<option value="GE">Georgia</option>'
                  + '<option value="DE">Germany</option>'
                  + '<option value="GH">Ghana</option>'
                  + '<option value="GI">Gibraltar</option>'
                  + '<option value="GR">Greece</option>'
                  + '<option value="GL">Greenland</option>'
                  + '<option value="GD">Grenada</option>'
                  + '<option value="GP">Guadeloupe</option>'
                  + '<option value="GU">Guam</option>'
                  + '<option value="GT">Guatemala</option>'
                  + '<option value="GG">Guernsey</option>'
                  + '<option value="GN">Guinea</option>'
                  + '<option value="GW">Guinea-bissau</option>'
                  + '<option value="GY">Guyana</option>'
                  + '<option value="HT">Haiti</option>'
                  + '<option value="HM">Heard Island and Mcdonald Islands</option>'
                  + '<option value="VA">Holy See (Vatican City State)</option>'
                  + '<option value="HN">Honduras</option>'
                  + '<option value="HK">Hong Kong</option>'
                  + '<option value="HU">Hungary</option>'
                  + '<option value="IS">Iceland</option>'
                  + '<option value="IN">India</option>'
                  + '<option value="ID">Indonesia</option>'
                  + '<option value="IR">Iran, Islamic Republic of</option>'
                  + '<option value="IQ">Iraq</option>'
                  + '<option value="IE">Ireland</option>'
                  + '<option value="IM">Isle of Man</option>'
                  + '<option value="IL">Israel</option>'
                  + '<option value="IT">Italy</option>'
                  + '<option value="JM">Jamaica</option>'
                  + '<option value="JP">Japan</option>'
                  + '<option value="JE">Jersey</option>'
                  + '<option value="JO">Jordan</option>'
                  + '<option value="KZ">Kazakhstan</option>'
                  + '<option value="KE">Kenya</option>'
                  + '<option value="KI">Kiribati</option>'
                  + '<option value="KP">Korea, Democratic People\'s Republic of</option>'
                  + '<option value="KR">Korea, Republic of</option>'
                  + '<option value="KW">Kuwait</option>'
                  + '<option value="KG">Kyrgyzstan</option>'
                  + '<option value="LA">Lao People\'s Democratic Republic</option>'
                  + '<option value="LV">Latvia</option>'
                  + '<option value="LB">Lebanon</option>'
                  + '<option value="LS">Lesotho</option>'
                  + '<option value="LR">Liberia</option>'
                  + '<option value="LY">Libyan Arab Jamahiriya</option>'
                  + '<option value="LI">Liechtenstein</option>'
                  + '<option value="LT">Lithuania</option>'
                  + '<option value="LU">Luxembourg</option>'
                  + '<option value="MO">Macao</option>'
                  + '<option value="MK">Macedonia, The Former Yugoslav Republic of</option>'
                  + '<option value="MG">Madagascar</option>'
                  + '<option value="MW">Malawi</option>'
                  + '<option value="MY">Malaysia</option>'
                  + '<option value="MV">Maldives</option>'
                  + '<option value="ML">Mali</option>'
                  + '<option value="MT">Malta</option>'
                  + '<option value="MH">Marshall Islands</option>'
                  + '<option value="MQ">Martinique</option>'
                  + '<option value="MR">Mauritania</option>'
                  + '<option value="MU">Mauritius</option>'
                  + '<option value="YT">Mayotte</option>'
                  + '<option value="MX">Mexico</option>'
                  + '<option value="FM">Micronesia, Federated States of</option>'
                  + '<option value="MD">Moldova, Republic of</option>'
                  + '<option value="MC">Monaco</option>'
                  + '<option value="MN">Mongolia</option>'
                  + '<option value="ME">Montenegro</option>'
                  + '<option value="MS">Montserrat</option>'
                  + '<option value="MA">Morocco</option>'
                  + '<option value="MZ">Mozambique</option>'
                  + '<option value="MM">Myanmar</option>'
                  + '<option value="NA">Namibia</option>'
                  + '<option value="NR">Nauru</option>'
                  + '<option value="NP">Nepal</option>'
                  + '<option value="NL">Netherlands</option>'
                  + '<option value="AN">Netherlands Antilles</option>'
                  + '<option value="NC">New Caledonia</option>'
                  + '<option value="NZ">New Zealand</option>'
                  + '<option value="NI">Nicaragua</option>'
                  + '<option value="NE">Niger</option>'
                  + '<option value="NG">Nigeria</option>'
                  + '<option value="NU">Niue</option>'
                  + '<option value="NF">Norfolk Island</option>'
                  + '<option value="MP">Northern Mariana Islands</option>'
                  + '<option value="NO">Norway</option>'
                  + '<option value="OM">Oman</option>'
                  + '<option value="PK">Pakistan</option>'
                  + '<option value="PW">Palau</option>'
                  + '<option value="PS">Palestinian Territory, Occupied</option>'
                  + '<option value="PA">Panama</option>'
                  + '<option value="PG">Papua New Guinea</option>'
                  + '<option value="PY">Paraguay</option>'
                  + '<option value="PE">Peru</option>'
                  + '<option value="PH">Philippines</option>'
                  + '<option value="PN">Pitcairn</option>'
                  + '<option value="PL">Poland</option>'
                  + '<option value="PT">Portugal</option>'
                  + '<option value="PR">Puerto Rico</option>'
                  + '<option value="QA">Qatar</option>'
                  + '<option value="RE">Reunion</option>'
                  + '<option value="RO">Romania</option>'
                  + '<option value="RU">Russian Federation</option>'
                  + '<option value="RW">Rwanda</option>'
                  + '<option value="SH">Saint Helena</option>'
                  + '<option value="KN">Saint Kitts and Nevis</option>'
                  + '<option value="LC">Saint Lucia</option>'
                  + '<option value="PM">Saint Pierre and Miquelon</option>'
                  + '<option value="VC">Saint Vincent and The Grenadines</option>'
                  + '<option value="WS">Samoa</option>'
                  + '<option value="SM">San Marino</option>'
                  + '<option value="ST">Sao Tome and Principe</option>'
                  + '<option value="SA">Saudi Arabia</option>'
                  + '<option value="SN">Senegal</option>'
                  + '<option value="RS">Serbia</option>'
                  + '<option value="SC">Seychelles</option>'
                  + '<option value="SL">Sierra Leone</option>'
                  + '<option value="SG">Singapore</option>'
                  + '<option value="SK">Slovakia</option>'
                  + '<option value="SI">Slovenia</option>'
                  + '<option value="SB">Solomon Islands</option>'
                  + '<option value="SO">Somalia</option>'
                  + '<option value="ZA">South Africa</option>'
                  + '<option value="GS">South Georgia and The South Sandwich Islands</option>'
                  + '<option value="ES">Spain</option>'
                  + '<option value="LK">Sri Lanka</option>'
                  + '<option value="SD">Sudan</option>'
                  + '<option value="SR">Suriname</option>'
                  + '<option value="SJ">Svalbard and Jan Mayen</option>'
                  + '<option value="SZ">Swaziland</option>'
                  + '<option value="SE">Sweden</option>'
                  + '<option value="CH">Switzerland</option>'
                  + '<option value="SY">Syrian Arab Republic</option>'
                  + '<option value="TW">Taiwan, Province of China</option>'
                  + '<option value="TJ">Tajikistan</option>'
                  + '<option value="TZ">Tanzania, United Republic of</option>'
                  + '<option value="TH">Thailand</option>'
                  + '<option value="TL">Timor-leste</option>'
                  + '<option value="TG">Togo</option>'
                  + '<option value="TK">Tokelau</option>'
                  + '<option value="TO">Tonga</option>'
                  + '<option value="TT">Trinidad and Tobago</option>'
                  + '<option value="TN">Tunisia</option>'
                  + '<option value="TR">Turkey</option>'
                  + '<option value="TM">Turkmenistan</option>'
                  + '<option value="TC">Turks and Caicos Islands</option>'
                  + '<option value="TV">Tuvalu</option>'
                  + '<option value="UG">Uganda</option>'
                  + '<option value="UA">Ukraine</option>'
                  + '<option value="AE">United Arab Emirates</option>'
                  + '<option value="GB">United Kingdom</option>'
                  + '<option value="US">United States</option>'
                  + '<option value="UM">United States Minor Outlying Islands</option>'
                  + '<option value="UY">Uruguay</option>'
                  + '<option value="UZ">Uzbekistan</option>'
                  + '<option value="VU">Vanuatu</option>'
                  + '<option value="VE">Venezuela</option>'
                  + '<option value="VN">Viet Nam</option>'
                  + '<option value="VG">Virgin Islands, British</option>'
                  + '<option value="VI">Virgin Islands, U.S.</option>'
                  + '<option value="WF">Wallis and Futuna</option>'
                  + '<option value="EH">Western Sahara</option>'
                  + '<option value="YE">Yemen</option>'
                  + '<option value="ZM">Zambia</option>'
                  + '<option value="ZW">Zimbabwe</option>'
                + '</select>'
              + '</div>'
              + '<div class="col-sm-5 messages"></div>'
            + '</div>'
            + '<div class="form-group">'
              + '<label class="col-sm-2 control-label" for="zip">ZIP Code</label>'
              + '<div class="col-sm-5">'
                + '<input id="zip" class="form-control" type="text" placeholder="12345" name="zip">'
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

      settable_map: {
        title               : true,

        login_model       : true,
        people_model      : true,
      },

      title            : 'Register New User',
      login_model      : null,
      people_model     : null,
      
      constraints : {         // These are the constraints used to validate the form
        email: {
          presence: true,     // Email is required
          email: true         // and must be an email (duh)
        },
        password: {
          presence: true,     // Password is also required
          length: {           // And must be at least 5 characters long
            minimum: 5
          }
        },
        "confirm-password": {
          presence: true,     // You need to confirm your password
          equality: {         // and it needs to be equal to the other password
            attribute: "password",
            message: "^The passwords does not match" // The ^ prevents the field name from being prepended to the error
          }
        },
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
        birthdate: {
          presence: true,          // The user needs to give a birthday
          date: {                  // and must be born at least 18 years ago
            latest: moment().subtract(18, "years"),
            message: "^You must be at least 18 years old to use this service"
          }
        },
        country: {
          presence: true,          // You also need to input where you live
          inclusion: {             // And we restrict the countries supported to Sweden
            within: ["SE"],
            message: "^Sorry, this service is for Sweden only" 
          }
        },
        zip: {
          format: {               // Zip is optional but if specified it must be a 5 digit long number
            pattern: "\\d{5}"
          }
        }
      }
    },
    stateMap  = {
      dialog           : undefined,
    },
    jqueryMap = {},

    setJqueryMap, disableSubmit, enableSubmit,
    onSubmit, openDialog, showErrorsForInput,
    configModule,  initModule;
  //----------------- END MODULE SCOPE VARIABLES ---------------

  //------------------- BEGIN UTILITY METHODS ------------------
  //-------------------- END UTILITY METHODS -------------------

  //--------------------- BEGIN DOM METHODS --------------------
  // Begin DOM method /setJqueryMap/
  setJqueryMap = function (dialog) {
    var
      $modal = dialog.getModal(),
      $form        = $modal.find('form.register');

    jqueryMap = {
      $form           : $form,
      $registerButton : dialog.getButton('register')
    };
  };
  // End DOM method /setJqueryMap/

  //---------------------- END DOM METHODS ---------------------

  //------------------- BEGIN EVENT HANDLERS -------------------

  // Hook up the form so we can prevent it from being posted
  onSubmit = function(ev) {
    ev.preventDefault();

    // validate the form aainst the constraints
    var errors = validate(jqueryMap.$form, configMap.constraints);
    // then we update the form to reflect the results
    if(errors){
      jqueryMap.$form.find("input[name], select[name]").each(function(){
        // Since the errors can be null if no errors were found we need to handle that
        showErrorsForInput($(this), errors[this.name]);
      });
    }else {
      disableSubmit();
      setTimeout(function(){
        enableSubmit();
        console.log(validate.collectFormValues(jqueryMap.$form,{trim:true}))
        stateMap.dialog.close();
      },1000);
    }
  };

  disableSubmit = function(){
    jqueryMap.$registerButton.disable();
    jqueryMap.$registerButton.spin();
    stateMap.dialog.setClosable(false);
  };

  enableSubmit = function(){
    jqueryMap.$registerButton.enable();
    jqueryMap.$registerButton.stopSpin();
    stateMap.dialog.setClosable(true);    
  };
  
  //-------------------- END EVENT HANDLERS --------------------


  //------------------- BEGIN PRIVATE METHODS -------------------

  // Shows the errors for a specific input
  showErrorsForInput = function($input, errors){
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

  //------------------- END PRIVATE METHODS -------------------


  //------------------- BEGIN PUBLIC METHODS -------------------

  /**
   * Show register dialog.
   *
   * @param none
   * @return true
   * @throws none
   */
  openDialog = function () {
    stateMap.dialog.realize();    
    setJqueryMap(stateMap.dialog);

    var $inputs = jqueryMap.$form.find("input, textarea, select")
    $inputs.each(function(){
      $(this).bind("change", function(ev){
        var errors = validate(jqueryMap.$form, configMap.constraints) || {};
        showErrorsForInput($(this), errors[this.name])
      });
    });
        
    jqueryMap.$registerButton.bind('utap', onSubmit);
    jqueryMap.$form.bind('submit', onSubmit);

    stateMap.dialog.open();
    return true;
  };

  // Begin public method /configModule/
  // Example   : spa.login.configModule({ dialog_open_em : 18 });
  // Purpose   : Configure the module prior to initialization
  // Arguments :
  //   * set_login_anchor - a callback to modify the URI anchor to
  //     indicate opened or closed state. This callback must return
  //     false if the requested state cannot be met
  //   * login_model - the login model object provides methods
  //       to interact with our instant messaging
  //   * people_model - the people model object which provides
  //       methods to manage the list of people the model maintains
  //   * dialog_* settings. All these are optional scalars.
  //       See mapConfig.settable_map for a full list
  //       Example: dialog_open_em is the open height in em's
  // Action    :
  //   The internal configuration data structure (configMap) is
  //   updated with provided arguments. No other actions are taken.
  // Returns   : true
  // Throws    : JavaScript error object and stack trace on
  //             unacceptable or missing arguments
  //
  configModule = function(input_map){
    spa.util.setConfigMap({
      input_map    : input_map,
      settable_map : configMap.settable_map,
      config_map   : configMap
    });
    return true;
  };
  // End public method /configModule/

  // Begin public method /initModule/
  // Example    : spa.login.initModule( $('#div_id') );
  // Purpose    :
  //   Directs Chat to offer its capability to the user
  // Arguments  :
  //   * $append_target (example: $('#div_id')).
  //     A jQuery collection that should represent
  //     a single DOM container
  // Action     :
  //   Appends the login dialog to the provided container and fills
  //   it with HTML content.  It then initializes elements,
  //   events, and handlers to provide the user with a chat-room
  //   interface
  // Returns    : true on success, false on failure
  // Throws     : none
  //
  initModule = function($append_target){
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

    stateMap.dialog = new BootstrapDialog({
      title: configMap.title,
      message: function(dialog) { return $(configMap.main_html);},
      buttons: [{
        id: 'register',
        label: 'Register'
      }]
    });

  };
  // End public method /initModule/

  // return public methods
  return {
    configModule      : configModule,
    initModule        : initModule,
    openDialog        : openDialog
  };
  //------------------- END PUBLIC METHODS ---------------------
});
