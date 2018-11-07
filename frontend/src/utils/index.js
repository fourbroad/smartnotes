var
  showErrorsForInput, showErrors, clearErrors,
  pluginIdent, pluginChunk, loadPlugin;

showErrorsForInput = function($input, errors) {
  var $formGroup = $input.closest(".form-group"), $messages = $formGroup.find(".messages");

  $input.removeClass("is-valid is-invalid");
  $messages.removeClass("invalid-feedback valid-feedback").empty();

  if (errors) {
    $input.addClass("is-invalid");
    $messages.addClass("invalid-feedback");
    _.each(errors, function(error) {
      $("<p/>").text(error).appendTo($messages);
    });
  } else {
    $input.addClass("is-valid");
    $messages.addClass("valid-feedback");
  }
};

showErrors = function($form, errors){
  $form.find("input[name]").each(function() {
    showErrorsForInput($(this), errors[this.name]);
  });
};

clearErrors = function($form){
  $('.messages', $form).removeClass("invalid-feedback valid-feedback").empty();
  $('input', $form).removeClass("is-valid is-invalid");
};

pluginIdent = function(pluginUri) {
  return '_' + pluginUri.replace(/[\.,-]/g,'_');
};

pluginChunk = function(pluginUri, name) {
  return '/plugins/' + pluginUri + '/dist/' + name + '.bundle.js'
};

loadPlugin = function(pluginUri, name, callback) {
  var script = document.createElement('script');
  var _pluginIdent = pluginIdent(pluginUri);
  script.type = 'text/javascript';
  script.charset = 'utf-8';
  script.src = pluginChunk(pluginUri, name);
  script.onload = function () {
    document.head.removeChild(script);
    callback && callback(window[_pluginIdent]);
    delete window[_pluginIdent];
  }
  document.head.appendChild(script);
};

export default {
  showErrorsForInput: showErrorsForInput,
  showErrors: showErrors,
  clearErrors: clearErrors,
  loadPlugin: loadPlugin
}