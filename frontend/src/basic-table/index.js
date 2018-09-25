import * as $ from 'jquery';
import PerfectScrollbar from 'perfect-scrollbar';
import basicTableHtml from './basic-table.html';

var
  init;

init = function () {
  $('#mainContent').html(basicTableHtml);

  const scrollables = $('.scrollable');
  if (scrollables.length > 0) {
    scrollables.each((index, el) => {
      new PerfectScrollbar(el);
    });
  }
};

export default {
  init: init
}
