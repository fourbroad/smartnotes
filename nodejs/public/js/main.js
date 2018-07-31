
require.config({
  baseUrl: "js",
  paths: {
	'socket.io'          : ['/socket.io/socket.io','io.fake'],
    css                  : '../thirdparty/require.js/css',
    domReady             : '../thirdparty/require.js/domReady',
    i18n                 : '../thirdparty/require.js/i18n',
    text                 : '../thirdparty/require.js/text',
    taffydb              : '../thirdparty/jquery/taffydb-2.6.2',
    underscore           : '../thirdparty/underscore.js-1.9.1/underscore',
    moment               : '../thirdparty/moment.js-2.22.2/moment-with-locales',
    validate             : '../thirdparty/validate.js-0.12.0/validate',
    jquery               : '../thirdparty/jquery/jquery-3.3.1',
    'jquery.uriAnchor'   : '../thirdparty/jquery/jquery.uriAnchor-1.1.3',
    'jquery.event.gevent': '../thirdparty/jquery/jquery.event.gevent-1.1.2',
    'jquery.event.ue'    : '../thirdparty/jquery/jquery.event.ue-1.3.0',
    bootstrap            : '../thirdparty/bootstrap-3.3.7/js/bootstrap',
    'bootstrap-dialog'   : '../thirdparty/bootstrap3-dialog-1.35.4/js/bootstrap-dialog'
  },
  shim: {
	'socket.io': {
      exports: 'io'
    },
    taffydb: {
      exports: 'TAFFY'
    },
    underscore: {
      exports : '_'
    },
    validate: {
      exports : 'validate'
    },
    'jquery.uriAnchor': {
      deps: ['jquery'],
      exports: 'jQuery.fn.uriAnchor'
    },
    'jquery.event.gevent': ['jquery'],
    'jquery.event.ue': ['jquery'],
    bootstrap: ['jquery','css!../thirdparty/bootstrap-3.3.7/css/bootstrap','css!../thirdparty/bootstrap-3.3.7/css/bootstrap-theme'],
    'bootstrap-dialog': ['jquery','bootstrap','css!../thirdparty/bootstrap3-dialog-1.35.4/css/bootstrap-dialog']
  }
});

// Start loading the main app file. Put all of your application logic in there.
require(['notes', 'css!../css/main']);
