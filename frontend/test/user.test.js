import Client from 'src/client';

// const expect = chai.expect;

describe('#user', function(){
  var
    adminClient, rootDomain, testUser, testClient, userCollection;

  this.slow(200);
  
  before(function(done){
	this.timeout(5000);
    Client.login("administrator","!QAZ)OKM", function(err1, client){
      adminClient = client;
	  adminClient.getDomain('localhost',function(err2, domain){
	    rootDomain = domain;
		rootDomain.getCollection('.users', function(err3, collection){
		  userCollection = collection;
		  userCollection.patchACL([{op:"add",path:"/createDocument/roles/-",value:"anonymous"},{op:"add",path:"/createDocument/users/-",value:"test"}], function(err4, result){
		    expect(result).to.be.ok;			  
		    done();				
		  });
		});
      });
	});
  });

  after(function(done){
	userCollection.getACL(function(err1, acl){
	  var users = acl.createDocument.users;
	  var roles = acl.createDocument.roles;
	  acl.createDocument.users = users.filter(e => e != "test");
	  acl.createDocument.roles = roles.filter(e => e != "anonymous");
	  userCollection.replaceACL(acl, function(err2, result){
		expect(result).to.be.ok;
        done();
	  });
	});
  });

  // anonymous
  it('register new test user and should return user object.', function(done){
	this.timeout(5000);
	adminClient.registerUser({id:'test', password:'z4bb4z'}, function(err, userData){
      expect(userData).to.be.an('object');
      done();
	});	
  });

  it('add test user to root domain and return true.', function(done){
	adminClient.joinDomain("localhost","test", {}, function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });

  it('test user login and return test client.', function(done){
    Client.login('test', 'z4bb4z', function(err, c){
      testClient = c;
   	  expect(testClient).to.be.an('object');
      done();
	});	
  });

  it('test client get test user and shoud return the user object.', function(done){
	testClient.getUser('test', function(err, user){
	  testUser = user;
	  expect(testUser).to.be.an('object');
   	  done();
	});	
  });

  it('replace content of test user and should return true', function(done){
	testUser.replace({ hello100: "world100"}, function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });

  it('Clear content of test user and should return true', function(done){
	testUser.replace({},function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });

  it('Add content to test user and should return true', function(done){
	testUser.patch([{op:"add", path:"/hello200", value:"world200"}],function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });

  it('Remove content from test user and should return true', function(done){
	testUser.patch([{op:"remove", path:"/hello200"}],function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });
	  
  it('test user reset password and should return true', function(done){
	testUser.resetPassword("z4b", function(err, result){
   	  expect(result).to.be.ok;
	  done();
	});
  });
  
  it('test client create a new user named hello and should return user object', function(done){
	testClient.createUser({id: 'hello', password:'world'}, function(err, user){
   	  expect(user).to.be.an('object');
	  done();
	});	
  });
  
  it('test client get user named hello and remove the returned user.', function(done){
	this.timeout(10000);
	testClient.getUser('hello', function(err1, user){
	  user.remove(function(err2, result){
	    if(err2) console.log(err2);
	   	else console.log(result);
	   	expect(result).to.be.ok;
	   	done();
	  });
	});	
  });

  it('remove test user and should return true', function(done){
	testUser.remove(function(err, result){
      if(err) console.log(err);
      else console.log(result);
   	  expect(result).to.be.ok;
	  done();
	});	
  });

  it('RootDomain garbageCollection should return true', function(done){
	this.timeout(10000);	  
	rootDomain.garbageCollection(function(err, result){
      expect(result).to.be.ok;
      done();
	});
  });
});