var 
  unless = require('express-unless'),
  utils = require('./utils');
  
function UnauthorizedError(code, error) {
	this.name = "UnauthorizedError";
	this.message = error.message;
	Error.call(this, error.message);
	Error.captureStackTrace(this, this.constructor);
	this.code = code;
	this.status = 401;
	this.inner = error;
}

UnauthorizedError.prototype = Object.create(Error.prototype);
UnauthorizedError.prototype.constructor = UnauthorizedError;

module.exports = function(options) {
  var
    opts = options || {},
    credentialsRequired = typeof opts.credentialsRequired === 'undefined' ? true : opts.credentialsRequired,
    middleware = function(req, res, next) {
    var 
      hostName = utils.getHostName(req),
      domain, token;
    
    if (req.method === 'OPTIONS' && req.headers.hasOwnProperty('access-control-request-headers')) {
      var hasAuthInAccessControl = !!~req.headers['access-control-request-headers']
                                    .split(',').map(function (header) {
                                      return header.trim();
                                    }).indexOf('authorization');

      if (hasAuthInAccessControl) {
        return next();
      }
    }

    if (opts.getToken && typeof opts.getToken === 'function') {
      try {
        token = options.getToken(req);
      } catch (e) {
        return next(e);
      }
    } else if (req.headers && req.headers.authorization) {
      var parts = req.headers.authorization.split(' ');
      if (parts.length == 2) {
        var scheme = parts[0];
        var credentials = parts[1];

        if (/^Bearer$/i.test(scheme)) {
          token = credentials;
        } else {
          if (credentialsRequired) {
            return next(new UnauthorizedError('credentials_bad_scheme', { message: 'Format is Authorization: Bearer [token]' }));
          } else {
            return next();
          }
        }
      } else {
        return next(new UnauthorizedError('credentials_bad_format', { message: 'Format is Authorization: Bearer [token]' }));
      }
    }

    if (!token) {
      if (credentialsRequired) {
          return next(new UnauthorizedError('credentials_required', { message: 'No authorization token was found!' }));
      } else {
        return next();
      }
    }

    domain = new Domain(hostName,token);
    domain.isValidToken(function(err, result){
        if (err) { return next(err); }
        if(result){
        	req["domain"] = domain;
        	next();
        }else{
        	next(new UnauthorizedError('invalid_token', {message:'Token is invalid!'}));
        }
    });

  };

  middleware.unless = unless;
  middleware.UnauthorizedError = UnauthorizedError;

  return middleware;
};

module.exports.UnauthorizedError = UnauthorizedError;