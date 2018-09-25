/*
 * cache.js - Redis cache implementation
*/

/*jslint         node    : true, continue : true,
  devel  : true, indent  : 2,    maxerr   : 50,
  newcap : true, nomen   : true, plusplus : true,
  regexp : true, sloppy  : true, vars     : false,
  white  : true
*/
/*global */

// ------------ BEGIN MODULE SCOPE VARIABLES --------------
'use strict';
var
  getHostName, getToken, makeError;
// ------------- END MODULE SCOPE VARIABLES ---------------

// ---------------- BEGIN PUBLIC METHODS ------------------

makeError = function ( name_text, msg_text, data ) {
  var error     = new Error();
  error.name    = name_text;
  error.message = msg_text;

  if ( data ){ error.data = data; }

  return error;
};

getHostName = function(req){
	return req.headers.host.replace(/:.*$/,"");
};

getToken = function(req) {
  if (req.headers.authorization && req.headers.authorization.split(' ')[0] === 'Bearer') {
	return req.headers.authorization.split(' ')[1];
  } else if (req.query && req.query.token) {
   	return req.query.token;
  }
  return null;
};

module.exports = {
  getHostName: getHostName,
  getToken: getToken,
  makeError: makeError
};
// ----------------- END PUBLIC METHODS -------------------
