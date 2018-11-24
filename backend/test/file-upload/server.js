process.chdir(__dirname);

var express = require('express');
var app = express();
var router = express.Router();
var path = require('path');
var fs = require('fs');
var formidable = require('formidable');
var StringDecoder = require('string_decoder').StringDecoder;
var File = require('./file');


//配置引擎模板以及静态文件访问文件夹
app.set('view engine', 'ejs');
app.set('views', path.join(__dirname, 'view'));
app.engine('html', require('ejs').__express);
app.use(express.static(path.join(__dirname, 'static')));

//首页
app.get(['/', '/index', '/index.html'], function(req, res, next) {
  res.render('index.html');
});

/**通过formdata上传图片
  * 参数1--req,express请求
  * 参数2--dirName,静态资源下的路径
  * 参数3--callback,回调函数，callback(err,fields,uploadPath),返回错误对象,表单字段和图片上传的地址
  */
function uploadFile(req, dirName, callback) {
  //上传文件
  // parse a file upload
  var form = new formidable.IncomingForm();
  form.uploadDir = path.join(__dirname, 'static', dirName);
  form.keepExtensions = true;
  form.encoding = 'utf-8';
  form.hash = 'sha1';

  form.onPart = function(part) {
    var self = this;

    // This MUST check exactly for undefined. You can not change it to !part.filename.
    if(part.filename === undefined) {
      var value = ''
        , decoder = new StringDecoder(this.encoding);

      part.on('data', function(buffer) {
        self._fieldsSize += buffer.length;
        if (self._fieldsSize > self.maxFieldsSize) {
          self._error(new Error('maxFieldsSize exceeded, received ' + self._fieldsSize + ' bytes of field data'));
          return;
        }
        value += decoder.write(buffer);
      });

      part.on('end', function() {
        self.emit('field', part.name, value);
      });
      return;
    }

    this._flushing++;

    var file = new File({
      path: this._uploadPath(part.filename),
      name: part.filename,
      type: part.mime,
      hash: self.hash
    });

    this.emit('fileBegin', part.name, file);

    file.open();
    this.openedFiles.push(file);

    part.on('data', function(buffer) {
      self._fileSize += buffer.length;
      if (self._fileSize > self.maxFileSize) {
        self._error(new Error('maxFileSize exceeded, received ' + self._fileSize + ' bytes of file data'));
        return;
      }
      if (buffer.length == 0) {
        return;
      }
      self.pause();
      file.write(buffer, function() {
        self.resume();
      });
    });

    part.on('end', function() {
      file.end(function() {
        self._flushing--;
        self.emit('file', part.name, file);
        self._maybeEnd();
      });
    });
  };

  // Emitted after each incoming chunk of data that has been parsed. Can be used to roll your own progress bar.
  form.on('progress', function(bytesReceived, bytesExpected) {
    console.log(arguments);
  });

  // Emitted whenever a field / value pair has been received.
  form.on('field', function(name, value) {
    console.log(arguments);
  });

  // Emitted whenever a new file is detected in the upload stream. Use this event if you want to stream 
  // the file to somewhere else while buffering the upload on the file system.
  form.on('fileBegin', function(name, file) {
    console.log(arguments);
  });

  // Emitted whenever a field / file pair has been received. file is an instance of File.
  form.on('file', function(name, file) {
    console.log(arguments);
  });

  // Emitted when there is an error processing the incoming form. A request that experiences an error 
  // is automatically paused, you will have to manually call request.resume() if you want the request 
  // to continue firing 'data' events.
  form.on('error', function(err) {
    console.log(arguments);
  });

  // Emitted when the entire request has been received, and all contained files have finished flushing to disk. 
  // This is a great place for you to send your response.
  form.on('end', function() {
    console.log(arguments);
  });

  // Emitted when the request was aborted by the user. Right now this can be due to a 'timeout' or 'close' event 
  // on the socket. After this event is emitted, an error event will follow. In the future there will be 
  // a separate 'timeout' event (needs a change in the node core).
  form.on('aborted', function() {
    console.log(arguments);
  });

  //通过formidable进行图片的上传
  form.parse(req, function(err, fields, files) {
    if (err) {
      callback && callback(err, null, null);
      return;
    }
    if (!files.userfile) {
      //前台上传表单的时候，文件使用的字段名是userfile,这里对文件是否为空进行判断
      var errMsg = {
        errCode: -10,
        errMsg: '文件为空'
      };
      callback && callback(errMsg, null, null);
      return;
    }

    //文件不为空的情况下，说明文件已经上传到我们的服务器中
    //这时，需要我们使用fs模块把我们已经上传的路径改为我们想要的位置
    var oldPath = files.userfile.path;
    var newPath = path.join(__dirname, 'static', dirName, files.userfile.name);
    //修改文件的名字
    fs.renameSync(oldPath, newPath);
    var finalPath = path.join('/', dirName, files.userfile.name).split('\\').join('/');
    //将路径中的'\'替换为'/'
    callback && callback(null, fields, finalPath);
    //回掉函数返回表单字段以及图片上传的相对路径
  });
}

//路由配置
router.post('/imgUpload', function(req, res) {
  /**设置响应头允许ajax跨域访问**/
  res.setHeader("Access-Control-Allow-Origin", "*");
  uploadFile(req, 'img', function(err, fields, uploadPath) {
    if (err) {
      return res.json({
        errCode: 0,
        errMsg: '上传图片错误'
      });
    }
    res.json({
      errCode: 1,
      errMsg: '上传成功',
      fields: fields,
      uploadPath: uploadPath
    });
  });
});
app.use(router);

var hostname = '127.0.0.1';
var port = 3003;
app.listen(port, hostname, function(err) {
  if (err)
    throw err;
  console.log('server running at http://' + hostname + ':' + port);
});
