const
  development = require('./webpack.development.js'),
  production = require('./webpack.production.js');

module.exports = (env, argv) => {
  if(argv.mode === 'production'){
    return production;
  }
  
  return development;;
};