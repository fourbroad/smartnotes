import './email.scss';

import * as $ from 'jquery';
import PerfectScrollbar from 'perfect-scrollbar';
import emailHtml from './email.html';

var
  init;

init = function () {
  $('#mainContent').html(emailHtml);

  const scrollables = $('.scrollable');
  if (scrollables.length > 0) {
    scrollables.each((index, el) => {
      new PerfectScrollbar(el,{suppressScrollX:true, wheelPropagation: true});
    });
  }

  $('.email-side-toggle').on('click', e => {
    $('.email-app').toggleClass('side-active');
    e.preventDefault();
  });

  $('.email-list-item, .back-to-mailbox').on('click', e => {
    $('.email-content').toggleClass('open');
    e.preventDefault();
  });
};

export default {
  init: init
}
