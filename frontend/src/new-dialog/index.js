import Loader from '@notesabc/loader';
import Masonry from 'masonry-layout';
import 'jquery.event.ue';

import newDialogHtml from './new-dialog.html';


var
  masonry,
  $newDialog = $(newDialogHtml), $container, $formGrid, $createBtn,
  domain, init, show, onCreate, createDocument, getSelected;

createDocument = function(form){
  Loader.load(form.plugin, function(module){
    var JsonForm = module.default;
    JsonForm.create({
      client: client,
      $container:$container,
      form: form,
      document: {}
    });
  });  
};

init = function(options) {
  $container = options.$container;
  domain = options.domain;
  $newDialog.appendTo('body').modal({show:false});
  $formGrid = $('.masonry', $newDialog);
  $createBtn = $('button.create', $newDialog);

  masonry = new Masonry($formGrid.get(0), {
      itemSelector: '.masonry-item',
      columnWidth: '.masonry-sizer',
      percentPosition: true,
    });

  $newDialog.on('shown.bs.modal', function (e) {
    $('.masonry-item', $formGrid).remove();
    domain.findForms({}, function(err, forms){
      if(err) return console.log(err);
      var $items = _.reduce(forms.forms, function(items, form){
        var item = $('<div class="masonry-item col-6 col-sm-5 col-md-4 col-lg-3 col-xl-2">'
                      +'<div class="form p-10 bd">'
                        +'<img class="form-img" alt="Form image">'
                        +'<h6 class="c-grey-900">' + (form.title||form.id) +'</h6>'
                      +'</div>'
                    +'</div>').data('item',form);
        return items.add(item);
      },$());

      $formGrid.append($items);
      masonry.appended($items);
      masonry.layout();

//         $(window).trigger('hashchange');
    });
  });

  $formGrid.on('click','.form', function(e){
    var form = $(e.currentTarget).parent('.masonry-item').data('item');
    Loader.load(form.plugin, function(module){
      var JsonForm = module.default;
      JsonForm.create({
        client: client,
        $container:$container,
        form: form,
        document: {}
      });
    });
  });

  $createBtn.bind('utap', onCreate);
//   $form.bind('submit', _onSubmit);
};

getSelected = function(){
  return $('.masonry-item.selected', $formGrid).data('item');
};

onCreate = function(e){  
  createDocument(getSelected());
};

show = function(){
  $newDialog.modal('show');
};

export default {
  init: init,
  show: show
};