import './numeric-range.scss';

import 'bootstrap';
import * as $ from 'jquery';
import utils from 'utils';
import validate from "validate.js";
import numericRangeHtml from './numeric-range.html';


function create(opts) {
  const
    constraints = {
      lowestValue: {
        numericality: function(value, attributes, attributeName, options, constraints) {
          if(!attributes.highestValue) return null;
          return {lessThanOrEqualTo: Number(attributes.highestValue)};
        }
      },
      highestValue: {
        numericality: function(value, attributes, attributeName, options, constraints) {
          if(!attributes.lowestValue) return null;
          return {greaterThanOrEqualTo: Number(attributes.lowestValue)};
        }
      }
    };

  var
    name, title, collection, lowestValue, highestValue,
    $container, $numericRange, $numericRangeBtn, $dropdownMenu, $form, $lowestInput, $highestInput, $updateBtn, $resetBtn, $cancelBtn,
    _refreshButton, _onSubmit, _onReset, _onCancel, _doSubmit, getRange;

  _refreshButton = function(){
    var label = '';
    if(lowestValue && highestValue){
      label = title+':'+lowestValue + '-' + highestValue;
    }else if(lowestValue){
      label = title+'>='+lowestValue;
    } else if(highestValue){
      label = title+'<='+highestValue;
    }else{
      label = title+':all'
    }

    $numericRangeBtn.html(label);
  };

  _doSubmit = function(dropdown){
    var errors = validate($form, constraints);
    if (errors) {
      utils.showErrors($form, errors);
    } else {
      var values = validate.collectFormValues($form, {trim: true}),
          lv = values.lowestValue ? parseInt(values.lowestValue) : null,
          hv = values.highestValue? parseInt(values.highestValue) : null;
      if(lowestValue != lv || highestValue != hv){
        lowestValue = lv;
        highestValue = hv;
        _refreshButton();
        $numericRange.trigger('valueChanged', {name:name, type:'numericRange', lowestValue:lowestValue, highestValue:highestValue});
      }
      utils.clearErrors($form);
      if(!dropdown) $numericRange.trigger('click');
    }
  };

  _onSubmit = function(ev){
    ev.preventDefault();
    ev.stopPropagation();
    _doSubmit();
  };

  _onReset = function(ev){
    $highestInput.val('');
    $lowestInput.val('');
    _doSubmit(true);
  };

  _onCancel = function(ev){
    $numericRange.trigger('click');
  };

  getRange = function(){
    return '';
  };

  title = opts.title;
  name = opts.name;
  collection = opts.collection;
  lowestValue = opts.lowestValue;
  highestValue = opts.highestValue;
  
  $container = opts.$container;
  $numericRange = $(numericRangeHtml).appendTo($container);
  $numericRangeBtn = $numericRange.children('button');
  $dropdownMenu = $('.dropdown-menu', $numericRange);
  $form = $('form', $dropdownMenu);
  $lowestInput = $('input[name=lowestValue]', $form);
  $highestInput = $('input[name=highestValue]', $form);
  $updateBtn = $('#update', $form)
  $resetBtn = $('#reset', $form)
  $cancelBtn = $('#cancel', $form)

  _refreshButton();

  $numericRange.on('show.bs.dropdown', function () {
    if(lowestValue) $lowestInput.val(lowestValue);
    if(highestValue) $highestInput.val(highestValue);
  });

  $dropdownMenu.on('click', function(evt){
    evt.stopImmediatePropagation();
  });

  $lowestInput.on('change', function(evt){
    var errors = validate($form, constraints) || {};
    utils.showErrorsForInput($lowestInput, errors[evt.target.name]);
    if(!errors[evt.target.name]&&$highestInput.val()!=''){
      utils.showErrorsForInput($highestInput, errors[$highestInput.attr('name')]);        
    }

  });

  $highestInput.on('change', function(evt){
    var errors = validate($form, constraints) || {};
    utils.showErrorsForInput($highestInput, errors[evt.target.name]);
    if(!errors[evt.target.name]&&$lowestInput.val()!=''){
      utils.showErrorsForInput($lowestInput, errors[$lowestInput.attr('name')]);        
    }
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