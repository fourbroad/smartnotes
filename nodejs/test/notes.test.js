const
  notes = require('../lib/notes.js'),
  expect = require('chai').expect;

process.on('SIGWINCH', function(){
    asyncCallback();
});

describe('#notes.js', function(){
  var
	client;
  
  before(function(done){
	this.timeout(10000);
    notes.login("administrator","!QAZ)OKM", function(err, c){
      console.log(err);
      client = c;
      done();
    });
  });

  it('getDomain() should return domain', function(done){
    client.getDomain('localhost',function(err, domain){
  	  expect(domain).to.be.an('object');
      done();
    });
  });
  
  it('replaceDomain() should return domain', function(done){
    client.replaceDomain('localhost',{hello:"world"},function(err, domain){
   	  expect(domain).to.be.an('object');
      done();
	});
  });

  it('patchDomain() should return domain', function(done){
    client.patchDomain('localhost',[{op:"add",path:"/hello2",value:"world2"}],function(err, domain){
   	  expect(domain).to.be.an('object');
      done();
	});
  });

});