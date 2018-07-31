import './chat.scss';

import * as $ from 'jquery';
import PerfectScrollbar from 'perfect-scrollbar';
import 'bootstrap';
import chatHtml from './chat.html';

var
  init;

init = function () {
  $('#mainContent').html(chatHtml);

  $('#chat-sidebar-toggle').on('click', e => {
    $('#chat-sidebar').toggleClass('open');
    e.preventDefault();
  });

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
