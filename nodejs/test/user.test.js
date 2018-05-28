const
  notes = require('../lib/notes'),
  expect = require('chai').expect;

process.on('SIGWINCH', function(){
    asyncCallback();
});

describe.only('#user.js', function(){
  var
	client, rootDomain;

  before(function(done){
	this.timeout(10000);
	notes.login("administrator","!QAZ)OKM", function(err1, adminClient){
	  adminClient.getDomain('localhost',function(err2, domain){
		console.log(domain);
		rootDomain = domain
		domain.setCollectionACL('.users',[{op:"add",path:"/create_document/users/-",value:"anonymous"}],function(err3, result){
		  console.log(err3);
		  console.log(result);
	      done();
		});
      });
	});
  });

  after(function(done){
	rootDomain.removeCollectionPermissionSubject('.users',[{op:"add",path:"/create_document/users/-",value:"anonymous"}],function(err3, result){
	  console.log(err3);
	  console.log(result);
      done();
	});
  });
  
  // anonymous
  it.only('registerUser() should return user object', function(done){
	this.timeout(500);
    notes.registerUser({userName: 'fourbroad', password:'z4bb4z'}, function(err, user){
    	if(err) console.log(err);
    	else console.log(user);
//    	expect(err).to.include.keys('message')
//    	expect(err).to.be.deep.equal({ code: 401, message: 'User have no permission to access!' });
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
    client.changePassword({userName: 'hello', password:'world'}, function(err, result){
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