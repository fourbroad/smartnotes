import * as $ from 'jquery';
import Masonry from 'masonry-layout';
import 'bootstrap';
import uiHtml from './ui.html';

var
  init;

init = function(){
  $('#mainContent').html(uiHtml);

  if ($('.masonry').length > 0) {
    new Masonry('.masonry', {
      itemSelector: '.masonry-item',
      columnWidth: '.masonry-sizer',
      percentPosition: true,
    });
  }

  // ------------------------------------------------------
  // @Popover
  // ------------------------------------------------------
  $('[data-toggle="popover"]').popover();
};

export default {
  init: init
}