const
  notes = require('../lib/notes.js'),
  expect = require('chai').expect;

process.on('SIGWINCH', function(){
    asyncCallback();
});

describe('#rootDomain collection', function(){
  var
  client, rootDomain, collection, testDomain, testCollection;
  
  before(function(done){
	notes.login("administrator","!QAZ)OKM", function(err, c){
	  client = c;
	  client.getDomain('localhost',function(err, d){
	    rootDomain = d
	    expect(rootDomain).to.be.an('object');
	    done();
	  });	  
	});
  });

  it('Creating collection should return the collection object', function(done){
	this.timeout(10000);
	rootDomain.createCollection("policies", {hello:"world"}, function(err, c){
   	  expect(c).to.be.an('object');
      done();
	});
  });

  it('Getting collection should return the collection object', function(done){
	rootDomain.getCollection("policies", function(err, c){
	  collection = c
   	  expect(collection).to.be.an('object');
      done();
	});
  });

  it('replacing content in collection should return true', function(done){
    collection.replace({ hello2: "world2"},function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });

  it('Clearing content in collection should return true', function(done){
    collection.replace({},function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });

  it('Adding content in collection should return true', function(done){
	collection.patch([{op:"add", path:"/hello3", value:"world3"}],function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });

  it('Removing content in collection should return true', function(done){
	collection.patch([{op:"remove", path:"/hello3"}],function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });
  
  it('Removing collection should return true', function(done){
	this.timeout(10000)	  
	collection.remove(function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });
  
  it('RootDomain garbageCollection should return true', function(done){
	this.timeout(10000)
	rootDomain.garbageCollection(function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });
  
});

describe('#testDomain collection', function(){
  var
  client, rootDomain, collection, testDomain, testCollection;
 
  before(function(done){
	notes.login("administrator","!QAZ)OKM", function(err, c){
	  client = c;
	  client.getDomain('localhost',function(err, d){
	    rootDomain = d
	    expect(rootDomain).to.be.an('object');
	    done();
	  });	  
	});
  });

  it('Creating testDomain should return domain', function(done){
	this.timeout(20000);
    client.createDomain('www.notes.com',{}, function(err, d){
      testDomain = d
  	  expect(testDomain).to.be.an('object');
  	  done();
  	});
  });

  it('Creating collection should return the collection object', function(done){
	this.timeout(20000);
	testDomain.createCollection("policies", {hello:"world"}, function(err, c){
   	  expect(c).to.be.an('object');
      done();
	});
  });

  it('Getting collection should return the collection object', function(done){
	testDomain.getCollection("policies", function(err, c){
      console.log(err);
	  testCollection = c
   	  expect(testCollection).to.be.an('object');
      done();
	});
  });

  it('replacing content should return true', function(done){
    testCollection.replace({ hello2: "world2"},function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });

  it('Clearing content should return true', function(done){
	testCollection.replace({},function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });

  it('Adding content should return true', function(done){
	testCollection.patch([{op:"add", path:"/hello3", value:"world3"}],function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });

  it('Removing content should return true', function(done){
	testCollection.patch([{op:"remove", path:"/hello3"}],function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });
		  
  it('Removing testCollection should return true', function(done){
	this.timeout(10000)	  
	testCollection.remove(function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });

  it('testDomain garbageCollection should return true', function(done){
	this.timeout(10000)
	testDomain.garbageCollection(function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });

  it('removing testDomain should return true', function(done){
	this.timeout(10000)
	testDomain.remove(function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });
	  
  it('RootDomain garbageCollection should return true', function(done){
	this.timeout(10000)
	rootDomain.garbageCollection(function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });
	  
});