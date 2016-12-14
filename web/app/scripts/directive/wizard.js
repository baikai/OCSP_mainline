'use strict';
/**
 * Wizard directive
 */
angular.module('ocspApp').directive('wizard',['$filter', function($filter){
  return {
    restrict: 'E',
    templateUrl: 'views/directive/wizard.html',
    transclude: true,
    replace: true,
    scope:{
      submitMethod: "&",
      task: "="
    },
    link : function(scope, element, attrs) {
      var options = {
        contentWidth : attrs.width,
        contentHeight : 600,
        backdrop: 'static'
      };
      var wizard = element.find(".wizard");
      wizard.attr("data-title",attrs.title);
      wizard.find("div.wizard-card").each(function(){
        var cardname = $(this).attr("data-cardname");
        $(this).prepend("<h3 style='display: none'>" + $filter('translate')(cardname) + "</h3>");
      });
      element.find("button.wizard-button").text($filter('translate')(attrs.name));
      var wizardModal = wizard.wizard(options);
      scope.showModal = function(){
        wizardModal.show();
      };
      scope.$on('openModal',function(){
        wizardModal.show();
      });
      wizardModal.on("closed", function(){
        wizardModal.reset();
      });
      for(var i = 1; i < 5 ; i++) {
        wizardModal.cards["create_task_" + i].on("validate", function (card) {
          var input = card.el.find("input[required]");
          var flag = true;
          input.each(function(){
            var name = $(this).val();
            if (name === "") {
              card.wizard.errorPopover($(this), "Cannot be empty");
              flag = false;
            }
          });
          if(card.name === "create_task_2" || card.name === "create_task_4") {
            var divs = card.el.find("div.ng-invalid");
            divs.each(function(){
              var fields = divs.find("div.token");
              if(fields.length <= 0){
                card.wizard.errorPopover($(this), "Cannot be empty");
                flag = false;
              }
            });
          }
          return flag;
        });
      }
      wizardModal.on("submit", function() {
        var promise = scope.submitMethod();
        promise.then(function () {
          wizardModal.submitSuccess();
          wizardModal.reset();
          wizardModal.close();
        }, function () {
          wizardModal.submitFailure();
          wizardModal.reset();
        });
      });
    }
  };
}]);
