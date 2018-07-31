import * as $ from 'jquery';
import 'datatables';
import dataTableHtml from './data-table.html';

var
  init;

init = function () {
  $('#mainContent').html(dataTableHtml);  
  $('#dataTable').DataTable();
};

export default {
  init: init
}