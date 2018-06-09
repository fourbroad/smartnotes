const
  notes = require('../lib/notes.js'),
  expect = require('chai').expect;

process.on('SIGWINCH', function(){
    asyncCallback();
});

describe('#rootDomain document', function(){
  var
  client, rootDomain, collection, document;
  
  before(function(done){
	this.timeout(10000);
	notes.login("administrator","!QAZ)OKM", function(err, c){
	  client = c;
	  client.getDomain('localhost',function(err, d){
	    rootDomain = d
		rootDomain.createCollection("policies", {}, function(err, c){
		  collection = c
	   	  expect(collection).to.be.an('object');
	      done();
		});
	  });	  
	});
  });

  it('Creating document with id should return the document object', function(done){
	this.timeout(5000);
	collection.createDocument("helloWorld", {hello:"world"}, function(err, d){
	  console.log(err);
   	  expect(d).to.be.an('object');
      done();
	});
  });

  it('Creating document with null id should return the document object', function(done){
    this.timeout(5000);	  
	collection.createDocument(null, {hello2:"world2"}, function(err, d){
   	  expect(d).to.be.an('object');
      done();
	});
  });
  
  it('Getting document with id should return the document object', function(done){
	collection.getDocument("helloWorld", function(err, d){
	  document = d
   	  expect(document).to.be.an('object');
      done();
	});
  });
  
  it('replacing content should return true', function(done){
	document.replace({ hello100: "world100"},function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });

  it('Clearing content should return true', function(done){
	document.replace({},function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });

  it('Adding content should return true', function(done){
	document.patch([{op:"add", path:"/hello200", value:"world200"}],function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });

  it('Removing content should return true', function(done){
	document.patch([{op:"remove", path:"/hello200"}],function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });
  
  it('Removing document should return true', function(done){
	document.remove(function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });
  
  it('Removing collection should return true', function(done){
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

describe('#testDomain document', function(){
  var
  client, rootDomain, testDomain, testCollection, testDocument;
 
  before(function(done){
	this.timeout(30000);
	notes.login("administrator","!QAZ)OKM", function(err, c){
	  client = c;
	  client.getDomain('localhost',function(err, d){
		rootDomain = d
        client.createDomain('www.notes.com',{}, function(err, d){
	  	  testDomain = d
	  	  testDomain.createCollection("policies", {hello:"world"}, function(err, c){
	  		testCollection = c;
	  		expect(testCollection).to.be.an('object');
	  		done();
	  	  });
	  	});
	  });	  
	});
  });

  it('Creating testDocument with id should return the document object', function(done){
	this.timeout(5000);	  
	testCollection.createDocument("helloWorld", {hello:"world"}, function(err, d){
   	  expect(d).to.be.an('object');
      done();
	});
  });

  it('Creating testDocument with null id should return the document object', function(done){
	this.timeout(5000);	  
	testCollection.createDocument(null, {hello2:"world2"}, function(err, d){
   	  expect(d).to.be.an('object');
      done();
	});
  });
  
  it('Getting testDocument with id should return the document object', function(done){
	testCollection.getDocument("helloWorld", function(err, d){
	  testDocument = d
   	  expect(testDocument).to.be.an('object');
      done();
	});
  });
  
  it('replacing content should return true', function(done){
	testDocument.replace({ hello100: "world100"},function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });

  it('Clearing content should return true', function(done){
	testDocument.replace({},function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });

  it('Adding content should return true', function(done){
	testDocument.patch([{op:"add", path:"/hello200", value:"world200"}],function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });

  it('Removing content should return true', function(done){
	testDocument.patch([{op:"remove", path:"/hello200"}],function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });
  
  it('Removing testDocument should return true', function(done){
	testDocument.remove(function(err, result){
	  expect(result).to.be.ok;
	  done();
	});
  });
  
  it('Removing testCollection should return true', function(done){
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