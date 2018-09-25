import './form.scss';

import validate from "validate.js";
import utils from 'utils';
import formHtml from './form.html';

function create(opts) {
  const 
    constraints = {
      title: {
        presence: true
      }
    };

  var
    domain = opts.domain, docId = opts.docId, oldDocument, document, buttons,
    $container = opts.$container, $formContainer, $form, $formHeader, $title, $modified, $dropdownToggle, 
    $btnContainer, $saveBtn, $itemSaveAs, $itemDiscard, $form, $submitBtn, $editDocModel, $titleInput,
    _init, _initBtnBar, _isDirty, _refreshHeader, _onSubmit, 
    save, saveAs, onDiscard;

  _initBtnBar = function(){
    $btnContainer.empty();
    _.each(buttons, function(btn){});
  };

  _refreshHeader = function(){
    if(_isDirty()){
      $saveBtn.html("Save");
      $saveBtn.removeAttr('data-toggle');
      $modified.show();
      $dropdownToggle.show();
    }else{
      $saveBtn.html("Save as ...");
      $saveBtn.attr({'data-toggle':'modal'});      
      $modified.hide();
      $dropdownToggle.hide();
    }
  };

  _isDirty = function(){
    return jiff.diff(document, oldDocument).length > 0
  };

  _onSubmit = function(evt){
    evt.preventDefault();
    evt.stopPropagation();

    var errors = validate($form, constraints);
    if (errors) {
      utils.showErrors($form, errors);
    } else {
      var values = validate.collectFormValues($form, {trim: true}), title = values.title, viewInfo = _.cloneDeep(view);
      delete viewInfo.id;
      delete viewInfo.collectionId;
      delete viewInfo.domainId;
      viewInfo.title = title;
      currentDomain.createView(viewInfo, function(err, v){
        view.refresh(function(){
          table.draw(false);
          $newViewModel.modal('toggle')
        })
      })
      utils.clearErrors($form);
    }
  };

  _init = function(doc){
    document = doc;
    oldDocument = _.cloneDeep(doc);
    $formContainer = $(formHtml);
    $formHeader = ('.form-header', $formContainer);
    $modified = $('.modified', $formHeader);
    $dropdownToggle = $('.dropdown-toggle', $formHeader);
    $saveBtn =$('.save.btn', $formHeader);
    $title = $('h4', $formHeader);
    $title.html(document.title||document.id);
    $itemSaveAs = $('.dropdown-item.save-as', $formHeader);
    $itemDiscard = $('.dropdown-item.discard', $formHeader);
    $editDocModel = $('#newView', $formHeader);
    $titleInput = $('input[name="title"]', $editDocModel);
    $form = $('form.new-view', $newViewModel);
    $submitBtn = $('.btn.submit', $newViewModel);
    $btnContainer = $('.search-container', $view);
    $container.empty();
    $formContainer.appendTo($container);

    _refreshHeader();

    $editDocModel.on('shown.bs.modal', function () {
      $titleInput.val('');
      $titleInput.trigger('focus')
    })    

    $saveBtn.on('click', save);
    $itemDiscard.on('click', onDiscard);
    $submitBtn.on('click', _onSubmit);
    $form.bind('submit', _onSubmit);
  };

//   domain.getView(viewId, function(err, view){
//     _init(view);
//   });
  _init(document);

  save = function(){
    if(_isDirty()){}
  };

  saveAs = function(){

  };

  onDiscard = function(){
    document = _.cloneDeep(oldDocument); 
    _refreshHeader();
    _.each(buttons, function(sc){});
  };

  return {save: save, saveAs:saveAs};
};

export default {
  create: create
}