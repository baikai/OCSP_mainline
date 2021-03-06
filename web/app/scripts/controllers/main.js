'use strict';

/**
 * @ngdoc function
 * @name ocspApp.controller:MainCtrl
 * @description
 * # MainCtrl
 * Controller of the ocspApp
 */
angular.module('ocspApp')
  .controller('MainCtrl',['$scope', '$location', '$rootScope', 'hotkeys', function ($scope, $location, $rootScope, hotkeys) {
    if($rootScope.getUsername()){
      if($rootScope.isAdmin()) {
        $location.path("/dashboard");
      }else{
        $location.path("/task_management");
      }
    }

    $scope.login = function(){
      if($scope.user.pass !== undefined) {
        $rootScope.login($scope.user.name, $scope.user.pass);
      }
    };

    hotkeys.bindTo($scope).add({
      combo: 'enter',
      allowIn: ['INPUT', 'SELECT', 'TEXTAREA'],
      callback: function() {
        $scope.login();
      }
    });

}]);
