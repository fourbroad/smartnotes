// Karma configuration
// Generated on Tue Jul 24 2018 11:17:50 GMT+0800 (CST)

const webpackConfig = require('./webpack.test');

module.exports = function(config) {
  config.set({

    // base path that will be used to resolve all patterns (eg. files, exclude)
    basePath: '',


    // frameworks to use
    // available frameworks: https://npmjs.org/browse/keyword/karma-adapter
    frameworks: ['mocha', 'sinon-chai', 'phantomjs-shim'],


    // list of files / patterns to load in the browser
    files: [
      // only specify one entry point and require all tests in there
      'test/index.test.js'      
    ],


    // list of files / patterns to exclude
    exclude: [
    ],


    // preprocess matching files before serving them to the browser
    // available preprocessors: https://npmjs.org/browse/keyword/karma-preprocessor
    preprocessors: {
      'test/index.test.js': [ 'webpack', 'sourcemap' ]
    },


    // test results reporter to use
    // possible values: 'dots', 'progress'
    // available reporters: https://npmjs.org/browse/keyword/karma-reporter
    reporters: ['spec', 'coverage'],


    // web server port
    port: 9876,


    // enable / disable colors in the output (reporters and logs)
    colors: true,


    // level of logging
    // possible values: config.LOG_DISABLE || config.LOG_ERROR || config.LOG_WARN || config.LOG_INFO || config.LOG_DEBUG
    logLevel: config.LOG_INFO,


    // enable / disable watching file and executing tests whenever any file changes
    autoWatch: true,


    // start these browsers
    // available browser launchers: https://npmjs.org/browse/keyword/karma-launcher
//     browsers: ['Chrome', 'Firefox', 'Safari', 'ChromeCanary', 'PhantomJS', 'Opera', 'IE'],
     browsers: ['PhantomJS'],
     
    // Continuous Integration mode
    // if true, Karma captures browsers, runs the tests and exits
    singleRun: false,

    // Concurrency level
    // how many browser should be started simultaneous
    concurrency: Infinity,

    webpackMiddleware: {
      stats: 'errors-only'
    },

    // 不显示 `webpack` 打包日志信息
    webpackServer: {
      noInfo: true
    },

    webpack: webpackConfig,

    coverageReporter: {
       dir: './test/coverage',
       reporters: [
         { type: 'lcov', subdir: '.' },
         { type: 'text-summary' }
       ]
    }    
  })
}
