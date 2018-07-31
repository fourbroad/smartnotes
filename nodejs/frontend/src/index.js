import 'bootstrap';
// import 'assets/styles/index.scss';
import 'index.scss';

import Client from 'client';
import * as $ from 'jquery';
import 'jquery.urianchor';
import 'jquery.event.gevent';
import 'jquery.event.ue';
import account from 'account';
import PerfectScrollbar from 'perfect-scrollbar';

const
  $container = $("#mainContent");

var
  client, uriAnchor = {}, _changeAnchorPart, _setAchor,
  _onSignInClicked, _onClientChanged, _onHashchange,
  _loadSignIn, _loadSignUp, _loadDashboard, _loadEmail, _loadCompose, _loadCalendar, _loadChat, 
  _loadCharts, _loadForms, _loadUi, _loadBasicTable, _loadDataTable, _loadGoogleMaps,
  _loadVectorMaps; 

_setAchor = function(anchor){
  uriAnchor = anchor;
  $.uriAnchor.setAnchor(anchor, null, true);
};

/* Purpose    : Changes part of the URI anchor component
 * Arguments  :
 *   argMap - The map describing what part of the URI anchor we want changed.
 * Returns    :
 *   true  - the Anchor portion of the URI was updated
 *   false - the Anchor portion of the URI could not be updated
 * Actions    :
 *   The current anchor rep stored in stateMap.anchor_map.
 *   See uriAnchor for a discussion of encoding. 
 *   This method:
 *   1) Creates a copy of this uri anchor map.
 *   2) Modifies the key-values using argMap.
 *   3) Manages the distinction between independent
 *   4) and dependent values in the encoding.
 *   5) Attempts to change the URI using uriAnchor.
 *   6) Returns true on success, and false on failure.
 */
_changeAnchorPart = function(argMap){
  var
    anchorRevised = $.extend( true, {}, uriAnchor),
    result        = true,
    keyName, keyNameDep;

  for(keyName in argMap){
    if(argMap.hasOwnProperty(keyName) && keyName.indexOf('_') !== 0){
      // update independent key value
      anchorRevised[keyName] = argMap[keyName];

      // update matching dependent key
      keyNameDep = '_' + keyName;
      if(argMap[keyNameDep]){
        anchorRevised[keyNameDep] = argMap[keyNameDep];
      } else {
        delete anchorRevised[keyNameDep];
        delete anchorRevised['_s' + keyNameDep];
      }
    }
  }

  // Attempt to update URI; revert if not successful
  try {
    $.uriAnchor.setAnchor(anchorRevised);
  } catch(error) {
    // replace URI with existing state
    $.uriAnchor.setAnchor(uriAnchor, null, true);
    result = false;
  }

  return result;
};

/* Purpose    : Handles the hashchange event
 * Arguments  :
 *   event - jQuery event object. 
 * Settings   : none
 * Returns    : false
 * Actions    :
 *   1) Parses the URI anchor component
 *   2) Compares proposed application state with current
 *   3) Adjust the application only where proposed state
 *     differs from existing and is allowed by anchor schema
 */
_onHashchange = function(event){
  var
    anchorProposed, anchorPrevious = $.extend(true, {}, uriAnchor),
    moduleProposed, isOk = true, errorCallback;

  errorCallback = function(){
    setAchor(anchorPrevious)
  };
  
  // attempt to parse anchor
  try {
    anchorProposed = $.uriAnchor.makeAnchorMap(); 
  } catch(error) {
    $.uriAnchor.setAnchor(anchorPrevious, null, true);
    return false;
  }
  uriAnchor = anchorProposed;

  // Adjust chat component if changed
  if(anchorPrevious._s_module !== anchorProposed._s_module) {
    moduleProposed = anchorProposed.module;
    switch(moduleProposed){
      case 'signin':
        _loadSignIn({error: errorCallback});
        break;      
      case 'signup':
        _loadSignUp({error: errorCallback});
        break;      
      case 'dashboard':
        _loadDashboard({error: errorCallback});
        break;
      case 'email':
        _loadEmail({error: errorCallback});
        break;
      case 'compose':
        _loadCompose({error: errorCallback});
        break;
      case 'calendar':
        _loadCalendar({error: errorCallback});
        break;
      case 'chat':
        _loadChat({error: errorCallback});
        break;
      case 'charts':
        _loadCharts({error: errorCallback});
        break;
      case 'forms':
        _loadForms({error: errorCallback});
        break;
      case 'ui':
        _loadUi({error: errorCallback});
        break;
      case 'basic-table':
        _loadBasicTable({error: errorCallback});
        break;
      case 'data-table':
        _loadDataTable({error: errorCallback});
        break;
      case 'google-maps':
        _loadGoogleMaps({error: errorCallback});
        break;
      case 'vector-maps':
        _loadVectorMaps({error: errorCallback});
        break;
      default :
        _loadDashboard({error: errorCallback});
    }
  }

  return false;
};

_loadSignIn = function(opts){
  import(/* webpackChunkName: "signin" */ './signin').then(module => {
    module.default.init({
      $container: $container,
      submitCallback: function(err, client){
        if(err) return opts && opts.errorCallback;

        $.gevent.publish('clientChanged', client);
        import(/* webpackChunkName: "dashboard" */ './dashboard').then(module => {
          module.default.init();
        });
      }
    });
  });  
};

_loadSignUp = function(opts){
  import(/* webpackChunkName: "signup" */ './signup').then(module => {
    module.default.init({
      $container: $container
    });
  });
};

_loadDashboard = function(opts){
  import(/* webpackChunkName: "dashboard" */ './dashboard').then(module => {
    module.default.init({
      $container: $container
    });
  });
};

_loadEmail = function(opts){
  import(/* webpackChunkName: "email" */ './email').then(module => {
    module.default.init({
      $container: $container
    });
  });
};

_loadCompose = function(opts){
  import(/* webpackChunkName: "compose" */ './compose').then(module => {
    module.default.init({
      $container: $container
    });
  });
};

_loadCalendar = function(opts){
  import(/* webpackChunkName: "calendar" */ './calendar').then(module => {
    module.default.init({
      $container: $container
    });
  });
};

_loadChat = function(opts){
  import(/* webpackChunkName: "chat" */ './chat').then(module => {
    module.default.init({
      $container: $container
    });
  });
};

_loadCharts = function(opts){
  import(/* webpackChunkName: "charts" */ './charts').then(module => {
    module.default.init({
      $container: $container
    });
  });
};

_loadForms = function(opts){
  import(/* webpackChunkName: "forms" */ './forms').then(module => {
    module.default.init({
      $container: $container
    });
  });
};

_loadUi = function(opts){
  import(/* webpackChunkName: "ui" */ './ui').then(module => {
    module.default.init({
      $container: $container
    });
  });
};

_loadBasicTable = function(opts){
  import(/* webpackChunkName: "basic-table" */ './basic-table').then(module => {
    module.default.init({
      $container: $container
    });
  });
};

_loadDataTable = function(opts){
  import(/* webpackChunkName: "data-table" */ './data-table').then(module => {
    module.default.init({
      $container: $container
    });
  });
};

_loadGoogleMaps = function(opts){
  import(/* webpackChunkName: "google-maps" */ './google-maps').then(module => {
    module.default.init({
      $container: $container
    });
  });
};

_loadVectorMaps = function(opts){
  import(/* webpackChunkName: "vector-maps" */ './vector-maps').then(module => {
    module.default.init({
      $container: $container
    });
  });
};

_onSignInClicked = function(event){
  _loadSignIn();
};

_onClientChanged = function(event, client){

};

$.uriAnchor.configModule({
  schema_map : {
    module: ['signin','signup','dashboard', 'email', 'compose', 'calendar', 'chat', 'charts', 'forms', 'ui', 'basictable', 'datatable','googlemaps','vectormaps']
  }
});


$.gevent.subscribe($container, 'signInClicked',  _onSignInClicked);
$.gevent.subscribe($container, 'clientChanged',  _onClientChanged);

account.init({$container: $('.page-container .nav-right')});

$('.scrollable').each((index, el) => {
  new PerfectScrollbar(el,{suppressScrollX:true, wheelPropagation: true});
});

// ------------------------------------------------------
// @Window Resize
// ------------------------------------------------------

/**
 * NOTE: Register resize event for Masonry layout
 */
const EVENT = document.createEvent('UIEvents');
window.EVENT = EVENT;
EVENT.initUIEvent('resize', true, false, window, 0);

// Trigger window resize event after page load for recalculation of masonry layout.
window.addEventListener('load', () => {
  window.dispatchEvent(EVENT);
});


// ------------------------------------------------------
// @External Links
// ------------------------------------------------------

// Open external links in new window
$('a').filter('[href^="http"], [href^="//"]')
  .not(`[href*="${window.location.host}"]`)
  .attr('rel', 'noopener noreferrer')
  .attr('target', '_blank');

// ------------------------------------------------------
// @Resize Trigger
// ------------------------------------------------------

// Trigger resize on any element click
document.addEventListener('click', () => {
  window.dispatchEvent(window.EVENT);
});


$('.search-toggle').on('click', e => {
  $('.search-box, .search-input').toggleClass('active');
  $('.search-input input').focus();
  e.preventDefault();
});



// Sidebar links
$('.sidebar .sidebar-menu li a').on('click', function () {
  const $this = $(this), $parent = $this.parent(), id = $parent.attr('id');

  if ($parent.hasClass('open')) {
    $parent.children('.dropdown-menu').slideUp(200, () => {
      $parent.removeClass('open');
    });
  } else {
    $parent.parent().children('li.open').children('.dropdown-menu').slideUp(200);
    $parent.parent().children('li.open').children('a').removeClass('open');
    $parent.parent().children('li.open').removeClass('open');
    $parent.children('.dropdown-menu').slideDown(200, () => {
      $parent.addClass('open');
    });
  }

  $('.sidebar').find('.sidebar-link').removeClass('active');
  $this.addClass('active');

  _changeAnchorPart({module:id});
});

// ٍSidebar Toggle
$('.sidebar-toggle').on('click', e => {
  $('.app').toggleClass('is-collapsed');
  e.preventDefault();
});

/**
 * Wait untill sidebar fully toggled (animated in/out)
 * then trigger window resize event in order to recalculate
 * masonry layout widths and gutters.
 */
$('#sidebar-toggle').click(e => {
  e.preventDefault();
  setTimeout(() => {
    window.dispatchEvent(window.EVENT);
  }, 300);
});

$(window).bind('hashchange', _onHashchange).trigger('hashchange');
