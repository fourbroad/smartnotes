const path = require("path")
  , webpack = require("webpack")
  , ExtractTextPlugin = require('extract-text-webpack-plugin');

function resolve(dir) {
  return path.join(__dirname, dir)
}

module.exports = {
  devtool: 'inline-source-map',
  module: {
    rules: [{
      test: /\.js$/,
      use: 'babel-loader',
      include: [resolve('src'), resolve('test')]
    }, {
      // 为了统计代码覆盖率，对 js 文件加入 istanbul-instrumenter-loader
      test: /\.(js)$/,
      exclude: /node_modules/,
      include: /src|packages/,
      enforce: 'post',
      use: [{
        loader: "istanbul-instrumenter-loader",
        options: {
          esModules: true
        },
      }]
    }, {
      test: /\.css$/,
      use: ['style-loader', 'css-loader']
    }, {
      test: /\.scss$/,
      use: [{
        loader: 'style-loader',
      }, {
        loader: 'css-loader',
        options: {
          sourceMap: true,
          minimize: false
        }
      }, {
        loader: 'postcss-loader',
        options: {
          sourceMap: true,
          plugins: ()=>[cssNext()]
        }
      }, {
        loader: 'sass-loader',
        options: {
          sourceMap: true,
          includePaths: [path.join(__dirname, 'node_modules'), path.join(__dirname, 'src', 'assets', 'styles'), path.join(__dirname, 'src')]
        }
      }]
    }, {
      test: /\.(eot|svg|ttf|otf|woff|woff2)$/,
      exclude: /(node_modules)/,
      use: ['file-loader']
    }]
  },

  resolve: {
    extensions: ['.js', '.json'],
    alias: {
      'src': resolve('src'),
    }

  },

  plugins: [new webpack.DefinePlugin({
    'process.env': {
      NODE_ENV: '"production"'
    }
  })]
};
