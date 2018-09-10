import './full-text.scss';

import * as $ from 'jquery';
import fullTextHtml from './full-text.html';


function create(opts) {

  var
    collection,
    $container, $fullTextSearch, $input, $inputIcon,
    changeCallback, _setSearchIcon, _setCancelIcon, _fireChange, getKeyword;

  _setSearchIcon = function(){
    $inputIcon.removeClass('fa-times');
    $inputIcon.addClass('fa-search');
  };

  _setCancelIcon = function(){
    $inputIcon.removeClass('fa-search');
    $inputIcon.addClass('fa-times');
  };

  _fireChange = function(keyword){
    $fullTextSearch.trigger('valueChanged', {field:'*', type:'fullText', keyword:keyword});    
  };

  getKeyword = function(){
    return $input.val().trim();
  };

  collection = opts.collection;
  $container = opts.$container;
  $fullTextSearch = $(fullTextHtml).appendTo($container);
  $input = $('input', $fullTextSearch);
  $inputIcon = $('.input-group-text>i', $fullTextSearch);

  $input.on('keyup change', function(){
    var filter = $input.val();
    if(filter != ''){
      _setCancelIcon();      
    }else{
      _setSearchIcon();
    }

    _fireChange(getKeyword());
  });

  $inputIcon.on('click', function(){
    var filter = $input.val(), changed = filter.trim() != '';
    $input.val('');
    _setSearchIcon();    
    if(changed) _fireChange(getKeyword());
  });

  return {
    getKeyword: getKeyword
  };
};

export default {
  create: create
}