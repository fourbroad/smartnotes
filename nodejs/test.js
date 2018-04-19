const express = require('express'), app = express();

var clientProto = {
	registerUser : function(userName, password, callback) {
		domainWrapper.register(this.token, userName, password, function(err,
				result) {
			if (err)
				return callback(err);
			callback(null, result);
		});
	},
	joinDomain : function(domainName, userName, permission, callback) {
		domainWrapper.joinDomain(domainName, this.token, userName, permission,
				function(err, result) {
					if (err)
						return callback(err);
					callback(null, result);
				});
	},
	logout : function(callback) {
		domainWrapper.logout(this.token, function(err, result) {
			if (err)
				return callback(err);
			callback(null, result);
		});
	},
	createDomain : function(domainName, callback) {
		domainWrapper.createDomain(domainName, this.token,
				function(err, domain) {
					if (err)
						return callback(err);
					callback(null, domain);
				});
	},
	getDomain : function(domainName, callback) {
		domainWrapper.getDomain(domainName, this.token, function(err, domain) {
			if (err)
				return callback(err);
			callback(null, domain);
		});
	},
	replaceDomain : function(domainName, content, callback) {
		domainWrapper.replaceDomain(domainName, this.token, content, function(
				err, domain) {
			if (err)
				return callback(err);
			callback(null, domain);
		});
	},
	patchDomain : function(domainName, patch, callback) {
		domainWrapper.patchDomain(domainName, this.token, patch, function(err,
				domain) {
			if (err)
				return callback(err);
			callback(null, domain);
		});
	},
	deleteDomain : function(domainName, callback) {
		domainWrapper.deleteDomain(domainName, this.token,
				function(err, result) {
					if (err)
						return callback(err);
					callback(null, result);
				});
	},
	authorizeDomain : function(domainName, acl, callback) {
		domainWrapper.authorizeDomain(domainName, this.token, acl, function(
				err, result) {
			if (err)
				return callback(err);
			callback(null, result);
		});
	}
};

var client = Object.create(clientProto);

app.get('/', function(req, res) {
	res.send('Hello World!');
});

app.get('/login', function(req, res) {
	notes.login("administrator", "!QAZ)OKM", function(err, c) {
		client = c
		res.send(err || c);
	});
});

app.get('/test', function(req, res) {
	res.send(client);
});

var server = app.listen(8000, function() {
	var host = server.address().address;
	var port = server.address().port;

	console.log('Example app listening at http://%s:%s', host, port);
});