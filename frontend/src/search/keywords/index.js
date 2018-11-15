import './keywords.scss';

import uuidv4 from 'uuid/v4';
import keywordsHtml from './keywords.html';


function create(opts) {

  var
    name, title, selectedItems, view,
    $container, $keywords, $keywordsBtn, $dropdownMenu, $itemContainer, $input, $inputIcon, $clearLink,
    changeCallback, _armItem, _armItems, _refresh, _refreshButton, _refreshClearLink, 
    _setSearchIcon, _setClearIcon, _distinctQuery, _fetchMenuItems,
    getSelectedItems;

  _distinctQuery = function(name, wildcard){
    var query = {
      collapse:{field: name + '.keyword'},
      aggs:{
        itemCount:{
          cardinality:{
            field: name + '.keyword'
          }
        }
      },
      _source:[name]
    };

    if(wildcard){
      query.query = {wildcard:{}};
      query.query.wildcard[name+".keyword"] = wildcard;
    }

    return query;
  },

  _setSearchIcon = function(){
    $inputIcon.removeClass('fa-times');
    $inputIcon.addClass('fa-search');
  };

  _setClearIcon = function(){
    $inputIcon.removeClass('fa-search');
    $inputIcon.addClass('fa-times');
  };

  _refreshClearLink = function(){
    var filter = $input.val();
    if(filter.trim() == ''){
      $clearLink.show();
      if(selectedItems.length > 0){
        $clearLink.removeClass('disabled');      
      }else{
        $clearLink.addClass('disabled');
      }
    }else{
      $clearLink.hide();
    }
  };

  _fetchMenuItems = function(name, wildcard){
    view.findDocuments(_distinctQuery(name, wildcard), function(err, docs){
      if(err) return console.log(err);
      var items = _.map(docs.documents, function(doc){
      	return {label:_.at(doc,name)[0], value:_.at(doc,name)[0]};
      });

      _refreshClearLink();
      _armItems(items);
    });
  };

  _armItem = function(label, value, checked){
    var id = uuidv4();
    var itemText = '<div class="dropdown-item form-check">'
    if(checked){
      itemText = itemText + '<input id="'+ id +'" type="checkbox" value="'+ value+'" class="form-check-input" checked>';
    }else{
      itemText = itemText + '<input id="'+ id +'" type="checkbox" value="'+ value+'" class="form-check-input">';
    }
    itemText = itemText + '<label class="form-check-label" for="' + id + '">' + label + '</label>' + '</div>'
    return itemText;
  };

  _armItems = function(items){
    $itemContainer.empty();

    _.each(_.intersectionWith(items, selectedItems, _.isEqual), function(item){
      $(_armItem(item.label, item.value, true)).data('item', item).appendTo($itemContainer);
    });

    $.each(_.differenceWith(items, selectedItems, _.isEqual), function(index, item){
      $(_armItem(item.label, item.value)).data('item', item).appendTo($itemContainer);
    });
  };

  _refreshButton = function(){
    var label;
    label = _.reduce(selectedItems, function(text, item) {
      var label;
      if(text == ''){
        label = item.label;
      }else{
        label = text + ',' + item.label;
      }
      return label;
    }, '');
  
    label = label == '' ? title + ":all" : label;
  
    $keywordsBtn.html(label);
  };

  _refresh = function(){
    _refreshClearLink();
    _refreshButton();
    $itemContainer.trigger('valueChanged', {name:name, type:'keywords', selectedItems:selectedItems});
  };

  title = opts.title;
  name = opts.name;
  view = opts.view;
  selectedItems = opts.selectedItems||[];
  $container = opts.$container;
  $keywords = $(keywordsHtml).appendTo($container);
  $keywordsBtn = $keywords.children('button');
  $dropdownMenu = $('.dropdown-menu', $keywords);
  $clearLink = $('.clear-selected-items', $keywords);
  $itemContainer = $('form.item-container', $keywords);
  $input = $('input', $keywords);
  $inputIcon = $('.input-group-text>i', $keywords);

  _refreshButton();

  $input.on('keyup change', function(){
    var filter = $input.val();
    if(filter != ''){
      _setClearIcon();
    }else{
      _setSearchIcon();
    }

    _fetchMenuItems(name, '*'+filter.trim()+'*');
  });

  $inputIcon.on('click', function(){
    var filter = $input.val(), rearm = filter.trim() != '';
    $input.val('');
    _setSearchIcon();    
    if(rearm){
      _fetchMenuItems(name);
    } 
  });

  $clearLink.on('click', function(){
    if(!$clearLink.hasClass('disabled') && selectedItems.length > 0){
      $('input[type="checkbox"]:checked', $itemContainer).prop('checked', false);
      selectedItems = [];
      _refresh();
      _fetchMenuItems(name);
    }
  });

  $keywords.on('show.bs.dropdown', function () {
    _fetchMenuItems(name);
  });

  $keywords.on('hidden.bs.dropdown', function () {
    $input.val('');
    _setSearchIcon();
  });

  $dropdownMenu.on('click', function(e){
    e.stopImmediatePropagation();
  });

  $itemContainer.on('click','.dropdown-item', function(e){
    var $target = $(e.target), $checkbox = $target.children('input:checkbox');
    $checkbox.prop('checked', !$checkbox.prop('checked'));
    $checkbox.trigger('change');
  });

  $itemContainer.on('change', 'input[type="checkbox"]', function(e){
    var $checkbox = $(e.target), $dropdownItem = $checkbox.parent('.dropdown-item'), 
        item = $dropdownItem.data('item');

    if($checkbox.prop('checked')){
      selectedItems = _.unionWith(selectedItems, [item], _.isEqual); 
    }else{
      selectedItems = _.differenceWith(selectedItems, [item], _.isEqual);
    }
            
    _refresh();
  });


  getSelectedItems = function(){
    return selectedItems;
  };

  return {
    getSelectedItems: getSelectedItems
  };
};

export default {
  create: create
}