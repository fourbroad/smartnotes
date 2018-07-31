import * as $ from 'jquery';
import Masonry from 'masonry-layout';
import formsHtml from './forms.html';

var
  init;

init = function () {
  $('#mainContent').html(formsHtml);

  if ($('.masonry').length > 0) {
    new Masonry('.masonry', {
      itemSelector: '.masonry-item',
      columnWidth: '.masonry-sizer',
      percentPosition: true,
    });
  }
};

export default {
  init: init
}