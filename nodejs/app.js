/*
 * app.js - Express server with routing
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
//  createError = require('http-errors'),
  http = require('http'),
  express = require('express'),
//expressJwt = require('express-jwt'),  
  logger = require('morgan'),
  path = require('path'),
  routes = require( './lib/routes' ),
  app     = express(),
  server = http.createServer(app);  
//------------- END MODULE SCOPE VARIABLES ---------------

//------------- BEGIN SERVER CONFIGURATION ---------------
app.use(logger('dev'));
app.use(express.json());
app.use(express.urlencoded({extended: true}));
app.use(express.static(path.join(__dirname,'public')));
//app.use(expressJwt({secret:'z4bb4z'}).unless({path:['/','/_login','/js','/css']}))

app.use("/", routes);

process.on('SIGUSR1', () => {
	console.log('Received SIGUSR1.');
});

process.on('SIGWINCH', () => {

});

//catch 404 and forward to error handler
//app.use(function(req,res,next){
//	next(createError(404));
//});

// error handler
app.use(function(err, req, res, next) {
	// set locals, only providing error in development
	res.locals.message = err.message;
	res.locals.error = req.app.get('env') === 'development' ? err : {};

	// render the error page
	res.status(err.status || 500);
	res.render('error');
});

module.exports = app;

//-------------- END SERVER CONFIGURATION ----------------

