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
  getHostName, getToken;
// ------------- END MODULE SCOPE VARIABLES ---------------

// ---------------- BEGIN PUBLIC METHODS ------------------

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
  getToken: getToken
};
// ----------------- END PUBLIC METHODS -------------------
