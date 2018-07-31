import Client from 'src/client';

// const expect = chai.expect;

describe('#rootDomain', function(){
  var
	client, rootDomain;

  this.slow(200);

  before(function(done){
	this.timeout(5000);
    Client.login("administrator","!QAZ)OKM", function(err, c){
      client = c
  	  expect(client).to.be.an('object');
	  done();
	});
  });
  
  after(function(done){
	client.logout(function(err, result){
  	  expect(result).to.be.ok;
	  done();
	});
  })

  it('Getting rootDomain should return rootDomain', function(done){
    client.getDomain('localhost',function(err, d){
      rootDomain = d
  	  expect(rootDomain).to.be.an('object');
  	  done();
  	});
  });

  it('replacing content should return true', function(done){
	rootDomain.replace({hello:"world"},function(err, result){
   	  expect(result).to.be.ok;
      done();
	});
  });

  it('Clearing content should return true', function(done){
    rootDomain.replace({},function(err, result){
   	  expect(result).to.be.ok;
      done();
	});
  });
  
  it('Adding content should return true', function(done){
	rootDomain.patch([{op:"add",path:"/hello2",value:"world2"}],function(err, result){
      expect(result).to.be.ok;
      done();
	});
  });

  it('Removing content should return true', function(done){
	rootDomain.patch([{op:"remove",path:"/hello2"}],function(err, result){
      expect(result).to.be.ok;
      done();
	});
  });
  
  it('RootDomain garbageCollection should return true', function(done){
	rootDomain.garbageCollection(function(err, result){
      expect(result).to.be.ok;
      done();
	});
  });

});

describe('#testDomain', function(){
  var
	client, rootDomain, testDomain;

  this.slow(200);
  
  before(function(done){
	this.timeout(5000);
    Client.login("administrator","!QAZ)OKM", function(err, c){
	  client = c
      client.getDomain('localhost',function(err, d){
	    rootDomain = d
	    expect(rootDomain).to.be.an('object');
	    done();
	  });
    });
  });
	  
  after(function(done){
	client.logout(function(err, result){
  	  expect(result).to.be.ok;
	  done();
	});
  })
  

  it('Creating testDomain should return domain', function(done){
	this.timeout(20000);
    client.createDomain('www.notes.com',{}, function(err, d){
  	  expect(d).to.be.an('object');
  	  done();
  	});
  });
	  
  it('Getting testDomain should return domain', function(done){
    client.getDomain('www.notes.com', function(err, d){
      testDomain = d
	  expect(testDomain).to.be.an('object');
	  done();
	});
  });  

  it('replacing content should return true', function(done){
	testDomain.replace({hello:"world"},function(err, result){
   	  expect(result).to.be.ok;
      done();
	});
  });

  it('Clearing content should return true', function(done){
	testDomain.replace({},function(err, result){
   	  expect(result).to.be.ok;
      done();
	});
  });

  it('Adding content should return true', function(done){
	testDomain.patch([{op:"add",path:"/hello2",value:"world2"}],function(err, result){
      expect(result).to.be.ok;
      done();
	});
  });

  it('Removing content should return true', function(done){
	testDomain.patch([{op:"remove",path:"/hello2"}],function(err, result){
      expect(result).to.be.ok;
      done();
	});
  });

  it('Removing testDomain should return true', function(done){
	testDomain.remove(function(err, result){
      expect(result).to.be.ok;
      done();
	});
  });

  it('RootDomain garbageCollection should return true', function(done){
	rootDomain.garbageCollection(function(err, result){
      expect(result).to.be.ok;
      done();
	});
  });

});