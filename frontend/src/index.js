import 'bootstrap';
import 'index.scss';

import Client from 'client';
import utils from 'utils';
import 'jquery.urianchor';
import 'jquery.event.gevent';
import 'jquery.event.ue';
import account from 'account';
import PerfectScrollbar from 'perfect-scrollbar';

const
  $container = $("#mainContent");

var
  client, domain,
  uriAnchor = {}, _changeAnchorPart, _setAchor,
  $viewContainer, $viewList,
  _init, _armViewListItem, _onClientChanged, _onHashchange,
  _loadSignUp, _loadDashboard, _loadEmail, _loadCompose, _loadCalendar, _loadChat, 
  _loadView, _loadDocument, _loadCharts, _loadForms, _loadUi, _loadBasicTable, _loadDataTable, 
  _loadGoogleMaps, _loadVectorMaps;

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
    var opts = {$container: $container, error: errorCallback};
    moduleProposed = anchorProposed.module;
    switch(moduleProposed){
      case 'signup':
        _loadSignUp(opts);
        break;      
      case 'dashboard':
        _loadDashboard(opts);
        break;
      case 'email':
        _loadEmail(opts);
        break;
      case 'compose':
        _loadCompose(opts);
        break;
      case 'calendar':
        _loadCalendar(opts);
        break;
      case 'chat':
        _loadChat(opts);
        break;
      case 'view':
        opts.domain = domain;
        opts.viewId = anchorProposed._module.viewId;
        _loadView(opts);
        break;
      case 'document':
        opts.domain = domain;
        opts.formId = anchorProposed._module.formId;
        opts.docId = anchorProposed._module.docId;
        _loadDocument(opts);
        break;
      case 'charts':
        _loadCharts(opts);
        break;
      case 'forms':
        _loadForms(opts);
        break;
      case 'ui':
        _loadUi(opts);
        break;
      case 'basic-table':
        _loadBasicTable(opts);
        break;
      case 'data-table':
        _loadDataTable(opts);
        break;
      case 'google-maps':
        _loadGoogleMaps(opts);
        break;
      case 'vector-maps':
        _loadVectorMaps(opts);
        break;
      default :        
    }
  }

  return false;
};

_loadSignUp = function(opts){
  import(/* webpackChunkName: "signup" */ './signup').then(module => {
    module.default.init(opts);
  });
};

_loadDashboard = function(opts){
  import(/* webpackChunkName: "dashboard" */ './dashboard').then(module => {
    module.default.init(opts);
  });
};

_loadEmail = function(opts){
  import(/* webpackChunkName: "email" */ './email').then(module => {
    module.default.init(opts);
  });
};

_loadCompose = function(opts){
  import(/* webpackChunkName: "compose" */ './compose').then(module => {
    module.default.init(opts);
  });
};

_loadCalendar = function(opts){
  import(/* webpackChunkName: "calendar" */ './calendar').then(module => {
    module.default.init(opts);
  });
};

_loadChat = function(opts){
  import(/* webpackChunkName: "chat" */ './chat').then(module => {
    module.default.init(opts);
  });
};

_loadView = function(opts){
  import(/* webpackChunkName: "view" */ './view').then(({default: View}) => {
    View.create(opts);
  });
};

_loadDocument = function(opts){
  utils.loadPlugin("com.ins24.webpack-numbers",function(webpackNumbers){
    console.log(webpackNumbers);
    console.log(webpackNumbers.wordToNum('Five'));
  });
};

_loadCharts = function(opts){
  import(/* webpackChunkName: "charts" */ './charts').then(module => {
    module.default.init(opts);
  });
};

_loadForms = function(opts){
  import(/* webpackChunkName: "forms" */ './forms').then(module => {
    module.default.init(opts);
  });
};

_loadUi = function(opts){
  import(/* webpackChunkName: "ui" */ './ui').then(module => {
    module.default.init(opts);
  });
};

_loadBasicTable = function(opts){
  import(/* webpackChunkName: "basic-table" */ './basic-table').then(module => {
    module.default.init(opts);
  });
};

_loadDataTable = function(opts){
  import(/* webpackChunkName: "data-table" */ './data-table').then(module => {
    module.default.init(opts);
  });
};

_loadGoogleMaps = function(opts){
  import(/* webpackChunkName: "google-maps" */ './google-maps').then(module => {
    module.default.init(opts);
  });
};

_loadVectorMaps = function(opts){
  import(/* webpackChunkName: "vector-maps" */ './vector-maps').then(module => {
    module.default.init(opts);
  });
};

_onClientChanged = function(event, c){
  client = c;
  if(client.currentUser.isAnonymous()){
    localStorage.removeItem('token');
  }else{
    localStorage.setItem('token', client.token);  		
  }
};

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
$('.sidebar .sidebar-menu').on('click','li>a', function () {
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

  if($parent.hasClass('view')){
    _changeAnchorPart({
      module: 'view',
      _module:{
        viewId: $parent.attr('id')
      }
    });
  }else{
    _changeAnchorPart({module:id});
  }
  

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

_armViewListItem = function(name){
  var item = String() + '<li id="' + name + '" class="view nav-item"><a class="sidebar-link">' + name + '</a></li>'
  return item;
};

_init = function(client){
  window.client = client;
  client.moment = moment;
  client.jiff = jiff;

  $viewContainer = $('.viewContainer');
  $viewList = $('.view-container .view-list');
  account.init({$container: $('.page-container .nav-right'), client: client});

  if(!client.currentUser.isAnonymous()){
    client.getDomain(function(err, d){
      window.currentDomain = domain = d;
      domain.findViews(function(err, views){
        if(err) return console.log(err);
        _.each(views.views, function(view){
          $(_armViewListItem(view.id)).data('item', view).appendTo($viewList);
        });
          
        $(window).trigger('hashchange');
      });
    })
  }
};

$.uriAnchor.configModule({
  schema_map : {
    module: ['signup','dashboard', 'email', 'compose', 'calendar', 'chat', 'view', 'document', 'charts', 'forms', 'ui', 'basic-table', 'data-table','google-maps','vector-maps'],
    _module:{ viewId: true, docId:true, formId:true }
  }
});

if(localStorage.token){
  Client.connect(localStorage.token, function(err, c){
    if(err) return console.log(err);
    client = c;
    _init(client);
  });
} else {
  Client.login(function(err, c){
    if(err) return console.log(err);
    client = c;
    _init(client);
  });
}

$.gevent.subscribe($container, 'clientChanged',  _onClientChanged);
$(window).bind('hashchange', _onHashchange);