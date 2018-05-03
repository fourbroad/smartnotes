const
  notes = require('../lib/notes'),
  expect = require('chai').expect;

process.on('SIGWINCH', function(){
    asyncCallback();
});

describe('#user.js', function(){
  var
	client;
  
  // anonymous
  it('registerUser() should return user object', function(done){
    notes.registerUser({userName: 'fourbroad', password:'z4bb4z'}, function(err, user){
    	expect(user).to.be.an('object');
    	done();
	});	
  });

  it('login() should return notes client', function(done){
    notes.login('fourbroad', 'z4bb4z', function(err, c){
      client = c
   	  expect(client).to.be.an('object');
      done();
	});	
  });

  it('createUser() should return user object', function(done){
    client.createUser({userName: 'hello', password:'world'}, function(err, user){
   	  expect(user).to.be.an('object');
	  done();
	});	
  });

  it('resetPassword() should return true', function(done){
    client.resetPassword(userName, newPassword, function(err, result){
   	  expect(result).to.be.ok;
	  done();
	});	
  });

  it('changePassword() should return true', function(done){
    client.createUser({userName: 'hello', password:'world'}, function(err, result){
   	  expect(result).to.be.ok;
	  done();
	});	
  });
  
  it('deleteUser() should return true', function(done){
    client.deleteUser(userName, function(err, result){
   	  expect(result).to.be.ok;
	  done();
	});	
  });

});