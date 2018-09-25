import * as $ from 'jquery';
import composeHtml from './compose.html';

var
  init;

init = function () {
  $('#mainContent').html(composeHtml);
};

export default {
  init: init
}