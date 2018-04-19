const
  notes = require('../lib/notes.js'),
  assert = require('assert');

process.on('SIGWINCH', () => {
    asyncCallback();
});

var client;

describe('#notes.js', () => {
  describe('#login()', () => {
	it('login() should return true', () => {
	  notes.login("administrator","!QAZ)OKM", function(err, c){
	    client = c
	    console.log("========>"+c)
	    assert.strictEqual(c, true);			
	  });
    });
  
    it('logout() should return true', () => {
   	  client.logout(function(error, result){
  	    assert.strictEqual(sum(1), 1);
      });
    });
    
  });
});