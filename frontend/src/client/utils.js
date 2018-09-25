/*
 * utils.js
 * General JavaScript utilities
 *
 * Michael S. Mikowski - mmikowski at gmail dot com
 * These are routines I have created, compiled, and updated
 * since 1998, with inspiration from around the web.
 *
 * MIT License
 *
 */

/*jslint          browser : true,  continue : true,
  devel  : true,  indent  : 2,     maxerr   : 50,
  newcap : true,  nomen   : true,  plusplus : true,
  regexp : true,  sloppy  : true,  vars     : false,
  white  : true
 */

'use strict';
	  
var
 hasOwn = Object.prototype.hasOwnProperty,
 toStr = Object.prototype.toString,
 isArray, isPlainObject, extend, create,
 makeError, setConfigMap;

// Begin Public constructor /makeError/
// Purpose: a convenience wrapper to create an error object
// Arguments:
//   * name_text - the error name
//   * msg_text  - long error message
//   * data      - optional data attached to error object
// Returns  : newly constructed error object
// Throws   : none
//
makeError = function ( name_text, msg_text, data ) {
  var error     = new Error();
  error.name    = name_text;
  error.message = msg_text;

  if ( data ){ error.data = data; }

  return error;
};
// End Public constructor /makeError/

// Begin Public method /setConfigMap/
// Purpose: Common code to set configs in feature modules
// Arguments:
//   * input_map    - map of key-values to set in config
//   * settable_map - map of allowable keys to set
//   * config_map   - map to apply settings to
// Returns: true
// Throws : Exception if input key not allowed
//
setConfigMap = function ( arg_map ){
  var
    input_map    = arg_map.input_map,
    settable_map = arg_map.settable_map,
    config_map   = arg_map.config_map,
    key_name, error;
  for(key_name in input_map){
    if ( input_map.hasOwnProperty( key_name ) ){
      if ( settable_map.hasOwnProperty( key_name ) ){
        config_map[key_name] = input_map[key_name];
      }
      else {
        error = makeError( 'Bad Input',
          'Setting config key |' + key_name + '| is not supported'
        );
        throw error;
      }
    }
  }
};
// End Public method /setConfigMap/

isArray = function isArray(arr) {
  if (typeof Array.isArray === 'function') {
	return Array.isArray(arr);
  }

  return toStr.call(arr) === '[object Array]';
};

isPlainObject = function isPlainObject(obj) {
  if (!obj || toStr.call(obj) !== '[object Object]') {
  	return false;
  }

  var hasOwnConstructor = hasOwn.call(obj, 'constructor');
  var hasIsPrototypeOf = obj.constructor && obj.constructor.prototype && hasOwn.call(obj.constructor.prototype, 'isPrototypeOf');
  // Not own constructor property must be Object
  if (obj.constructor && !hasOwnConstructor && !hasIsPrototypeOf) {
  	return false;
  }

  // Own properties are enumerated firstly, so to speed up,
  // if last one is own, then all properties are own.
  var key;
  for (key in obj) { /**/ }

  return typeof key === 'undefined' || hasOwn.call(obj, key);
};

extend = function(){
  var options, name, src, copy, copyIsArray, clone;
  var target = arguments[0];
  var i = 1;
  var length = arguments.length;
  var deep = false;

  // Handle a deep copy situation
  if (typeof target === 'boolean') {
  	deep = target;
  	target = arguments[1] || {};
  	// skip the boolean and the target
  	i = 2;
  }
  if (target == null || (typeof target !== 'object' && typeof target !== 'function')) {
    target = {};
  }

  for (; i < length; ++i) {
    options = arguments[i];
  	// Only deal with non-null/undefined values
  	if (options != null) {
  	  // Extend the base object
	  for (name in options) {
	    src = target[name];
		copy = options[name]
		// Prevent never-ending loop
  		if (target !== copy) {
  		  // Recurse if we're merging plain objects or arrays
  		  if (deep && copy && (isPlainObject(copy) || (copyIsArray = isArray(copy)))) {
  		    if (copyIsArray) {
  			  copyIsArray = false;
  			  clone = src && isArray(src) ? src : [];
  			} else {
  			  clone = src && isPlainObject(src) ? src : {};
  			}

  			// Never move original objects, clone them
  			target[name] = extend(deep, clone, copy);

  			// Don't bring in undefined values
  	      } else if (typeof copy !== 'undefined') {
  		    target[name] = copy;
  		  }
  		}
  	  }
    }
  }

  // Return the modified object
  return target;
};

create = function(proto, propertiesObject){
  function result() {}
  if (proto && proto instanceof Object) {
    result.prototype = proto;
  }
  var resultReal = new result();
  if (propertiesObject && propertiesObject instanceof Object) {
    Object.defineProperties(resultReal, propertiesObject);
  }
  return resultReal;
};

export default {
  extend       : extend,
  create       : create,
  makeError    : makeError,
  setConfigMap : setConfigMap
};