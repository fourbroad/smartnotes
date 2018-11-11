import './datetime-range.scss';

import 'jquery-datetimepicker';
import 'jquery-datetimepicker/jquery.datetimepicker.css';
import validate from "validate.js";
import utils from 'utils';
import datetimeRangeHtml from './datetime-range.html';


function create(opts) {
  const
    constraints = {
      earliest: {
        datetime: function(value, attributes, attributeName, options, constraints) {
          if(!attributes.latest) return null;
          return {
            latest: moment.utc(attributes.latest),
            message:'must be no later than ' + attributes.latest
          };
        }
      },
      latest: {
        datetime: function(value, attributes, attributeName, options, constraints) {
          if(!attributes.earliest) return null;
          return {
            earliest: moment.utc(attributes.earliest),
            message:'must be no earlier than '+ attributes.earliest
          };
        }
      }
    };

  var
    name, title, view, earliest, latest,
    $container, $datetimeRange, $earliestPicker, $latestPicker, $datetimeRangeBtn, $dropdownMenu, $form, 
    $earliestInput, $latestInput, $earliestIcon, $latestIcon, $updateBtn, $resetBtn, $cancelBtn,
    _refreshButton, _onSubmit, _onReset, _onCancel, _doSubmit, getRange;

  _refreshButton = function(){
    var label = '';
    if(earliest && latest){
      label = title+':'+moment.utc(earliest).format('YYYY-MM-DD') + '~' + moment.utc(latest).format('YYYY-MM-DD');
    }else if(earliest){
      label = title+'>='+moment.utc(earliest).format('YYYY-MM-DD');
    } else if(latest){
      label = title+'<='+moment.utc(latest).format('YYYY-MM-DD');
    }else{
      label = title+':all'
    }

    $datetimeRangeBtn.html(label);
  };

  _doSubmit = function(dropdown){
    var errors = validate($form, constraints);
    if (errors) {
      utils.showErrors($form, errors);
    } else {
      var ep = $earliestPicker.val() ? +moment.utc($earliestPicker.val()) : null, 
          lp = $latestPicker.val() ? +moment.utc($latestPicker.val()) : null;
      if(earliest != ep || latest != lp){
        earliest = ep;
        latest = lp;
        _refreshButton();
        $datetimeRange.trigger('valueChanged', {name:name, type:'datetimeRange', earliest:earliest, latest:latest});
      }
      utils.clearErrors($form);
      if(!dropdown) $datetimeRange.trigger('click');
    }
  };

  _onSubmit = function(ev){
    ev.preventDefault();
    ev.stopPropagation();
    _doSubmit();
  };

  _onReset = function(ev){
    $earliestInput.val('');
    $latestInput.val('');
    _doSubmit(true);
  };

  _onCancel = function(ev){
    $datetimeRange.trigger('click');
  };

  getRange = function(){
    return '';
  };

  validate.extend(validate.validators.datetime, {
    parse: function(value, options) {
      return +moment.utc(value);
    },

    format: function(value, options) {
      var format = options.dateOnly ? "YYYY-MM-DD" : "YYYY-MM-DD HH:mm:ss";
      return moment.utc(value).format(format);
    }
  });


  title = opts.title;
  name = opts.name;
  view = opts.view;
  earliest = opts.earliest;
  latest = opts.latest;

  $container = opts.$container;
  $datetimeRange = $(datetimeRangeHtml).appendTo($container);
  $earliestPicker = $('[name="earliest"]', $datetimeRange);
  $latestPicker = $('[name="latest"]', $datetimeRange);
  $datetimeRangeBtn = $datetimeRange.children('button');
  $dropdownMenu = $('.dropdown-menu', $datetimeRange);
  $form = $('form', $dropdownMenu);
  $latestInput = $('input[name=earliest]', $form);
  $earliestInput = $('input[name=latest]', $form);
  $latestIcon = $('.latest-icon', $form);
  $earliestIcon = $('.earliest-icon', $form);
  $updateBtn = $('#update', $form)
  $resetBtn = $('#reset', $form)
  $cancelBtn = $('#cancel', $form)

  _refreshButton();

  $.datetimepicker.setDateFormatter('moment');

  $earliestPicker.datetimepicker({
    format:'YYYY-MM-DD HH:mm:ss'
  });
  $latestPicker.datetimepicker({
    format:'YYYY-MM-DD HH:mm:ss'
  });

  $datetimeRange.on('show.bs.dropdown', function () {
    if(earliest) $latestInput.val(moment.utc(earliest).format('YYYY-MM-DD HH:mm:ss'));
    if(latest) $earliestInput.val(moment.utc(latest).format('YYYY-MM-DD HH:mm:ss'));
  });

  $dropdownMenu.on('click', function(evt){
    evt.stopImmediatePropagation();
  });

  $earliestInput.on('change', function(evt){
    var errors = validate($form, constraints) || {};
    utils.showErrorsForInput($earliestInput, errors[evt.target.name]);
    if($latestInput.val()!=''){
      utils.showErrorsForInput($latestInput, errors[$latestInput.attr('name')]);        
    }
  });

  $latestInput.on('change', function(evt){
    var errors = validate($form, constraints) || {};
    utils.showErrorsForInput($latestInput, errors[evt.target.name]);
    if($earliestInput.val()!=''){
      utils.showErrorsForInput($earliestInput, errors[$earliestInput.attr('name')]);        
    }

  });

  $latestIcon.on('click', function(evt){
    $latestPicker.datetimepicker('toggle');
  });

  $earliestIcon.on('click', function(evt){
    $earliestPicker.datetimepicker('toggle');
  });
  
  $updateBtn.bind('utap', _onSubmit);
  $resetBtn.bind('utap', _onReset);
  $cancelBtn.bind('utap', _onCancel);
  $form.bind('submit', _onSubmit);

  return {
    getRange: getRange
  };
};

export default {
  create: create
}