import * as $ from 'jquery';
import signupHtml from './signup.html';

var
  init;

init = function () {
  $('#mainContent').html(signupHtml);
};

export default {
  init: init
}