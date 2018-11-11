const 
  path = require('path'),
  webpack  = require('webpack'),
  CleanWebpackPlugin = require('clean-webpack-plugin'),
  HtmlWebpackPlugin = require('html-webpack-plugin'),
  CaseSensitivePathsPlugin = require('case-sensitive-paths-webpack-plugin'),
  CopyWebpackPlugin = require('copy-webpack-plugin'),  
  MiniCssExtractPlugin = require("mini-css-extract-plugin"),
  WorkboxPlugin = require('workbox-webpack-plugin');

const titles = {
  'index': 'Dashboard',
  'blank': 'Blank',
  'buttons': 'Buttons',
  'calendar': 'Calendar',   
  'charts': 'Charts',
  'chat': 'Chat',           
  'compose': 'Compose',
  'datatable': 'Datatable',
  'email': 'Email',
  'forms': 'Forms',
  'google-maps': 'Google Maps',
  'signin': 'Signin',
  'signup': 'Signup',
  'ui': 'UI',
  'vector-maps': 'Vector Maps',
  '404': '404',
  '500': '500',
  'basic-table': 'Basic Table',
};

const htmlWebpackPlugins = Object.keys(titles).map(title => {
  return new HtmlWebpackPlugin({
    template: path.join(__dirname, `src/${title}.html`),
    path: path.join(__dirname, 'dist'),
    filename: `${title}.html`,
    inject: true,
    minify: {
      collapseWhitespace: true,
      minifyCSS: true,
      minifyJS: true,
      removeComments: true,
      useShortDoctype: true,
    },
  });
});

module.exports = {
  entry: {
    context: ['jquery', 'bootstrap', 'lodash'],
    index: path.join(__dirname, 'src/index.js')    
  },
  output: {
    path: path.resolve(__dirname, 'dist'),
    publicPath: '/',
    filename: '[name].[hash].js',
    chunkFilename: '[name].[hash].js'
  },
  resolve: {
    extensions: ['.webpack-loader.js', '.web-loader.js', '.loader.js', '.js'],
    alias: {
      src: path.resolve(__dirname, 'src'),
      test: path.resolve(__dirname, 'test')
    },    
    modules: [
      path.join(__dirname, 'src'), 
      path.resolve(__dirname, 'node_modules')
    ]
  },
  module: {
    rules: [{
      test: /\.(eot|svg|ttf|otf|woff|woff2)$/,
      exclude: /(node_modules)/,
      use: ['file-loader']
    },{
      test: /\.(png|gif|jpg|svg)$/,
      exclude: /(node_modules)/,
      use: [{
        loader: 'file-loader',
        options: {
          outputPath: 'assets'
        }
      }]      
    },{
      test: /\.(js)$/,
      exclude: /(node_modules)/,
      use: ['babel-loader']
    },{
      test: /\.html$/,
      use: [{
        loader: 'html-loader',
        options: {
          minimize: true
        }
      }]      
    },{
      test: require.resolve('jquery'),
      use: [{
        loader: 'expose-loader',
        options: 'jQuery'
      },{
        loader: 'expose-loader',
        options: '$'
      }]
    },{
      test: require.resolve('lodash'),
      use: [{
        loader: 'expose-loader',
        options: '_'
      }]
    }]
  },
  plugins: [
    new CleanWebpackPlugin(['dist']),
    new HtmlWebpackPlugin({
      template: path.join(__dirname, 'src/index.html'),
      path: path.join(__dirname, 'dist'),
      filename: 'index.html',
      inject: true,
      minify: {
        collapseWhitespace: true,
        minifyCSS: true,
        minifyJS: true,
        removeComments: true,
        useShortDoctype: true
      },
    }),
    new HtmlWebpackPlugin({
      template: path.join(__dirname, 'src/404.html'),
      path: path.join(__dirname, 'dist'),
      filename: '404.html',
      inject: true,
      minify: {
        collapseWhitespace: true,
        minifyCSS: true,
        minifyJS: true,
        removeComments: true,
        useShortDoctype: true
      },
    }),
    new HtmlWebpackPlugin({
      template: path.join(__dirname, 'src/500.html'),
      path: path.join(__dirname, 'dist'),
      filename: '500.html',
      inject: true,
      minify: {
        collapseWhitespace: true,
        minifyCSS: true,
        minifyJS: true,
        removeComments: true,
        useShortDoctype: true
      },
    }),
    new webpack.ProvidePlugin({
      moment: 'moment',
      'window.moment': 'moment',
      jiff: 'jiff',
      'window.jiff': 'jiff',
      Popper: ['popper.js', 'default']
    }),
    new CaseSensitivePathsPlugin(),
    new MiniCssExtractPlugin({
      // Options similar to the same options in webpackOptions.output both options are optional
      filename: "[name].[chunkhash].css",
      chunkFilename: "[name].[chunkhash].css"
    }),
    new CopyWebpackPlugin([{
      from : path.join(__dirname, 'src/assets/static'),
      to   : path.join(__dirname, 'dist/assets/static')
    }]),
    new WorkboxPlugin.GenerateSW({
      // these options encourage the ServiceWorkers to get in there fast 
      // and not allow any straggling "old" SWs to hang around
      clientsClaim: true,
      skipWaiting: true
    })
  ],//.concat(htmlWebpackPlugins),
  optimization: {
    runtimeChunk: 'single',
    splitChunks: {
//       chunks: 'all'
      cacheGroups: {
        vendor: {
          test: /[\\/]node_modules[\\/]/,
          name: 'vendors',
          chunks: 'all'
        }
      }
    }
  }
};