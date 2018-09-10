var
  showErrorsForInput, showErrors, clearErrors;

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
}

clearErrors = function($form){
  $('.messages', $form).removeClass("invalid-feedback valid-feedback").empty();
  $('input', $form).removeClass("is-valid is-invalid");
};

export default {
  showErrorsForInput: showErrorsForInput,
  showErrors: showErrors,
  clearErrors: clearErrors
}