import './view.scss';

// import 'datatables.net';
import 'datatables.net-bs4';
import 'datatables.net-bs4/css/dataTables.bootstrap4.css';
import utils from 'utils';
import Keywords from 'search/keywords';
import NumericRange from 'search/numeric-range';
import DatetimeRange from 'search/datetime-range';
import FullText from 'search/full-text'
import viewHtml from './view.html';

function create(opts) {
  const 
    constraints = {
      username: {
        presence: true
      }
    };

  var
    domain = opts.domain, viewId = opts.viewId, view, collection, table, columns, searchColumns,
    $container = opts.$container, $view, $viewHeader, $title, $modified, $dropdownToggle, 
    $searchContainer, $searchGroup, $saveBtn, $itemSaveAs, $itemDiscard, $form, $submitBtn,
    _init, _initSearchBar, _armSearchColOpts, _searchColumnType, _kvMap, _buildSearch, _isDirty, 
    _refreshHeader, _onSubmit,
    save, saveAs, discard;

  _initSearchBar = function(){
    $searchContainer.empty();
    _.each(searchColumns, function(sc){
      switch(sc.type){
        case 'keywords':
          Keywords.create({
            title: sc.title,
            name: sc.name,
            selectedItems: sc.selectedItems,
            $container: $searchContainer,
            collection: collection
          });
          break;
        case 'numericRange':
          NumericRange.create({
            title: sc.title,
            name: sc.name,
            lowestValue: sc.lowestValue,
            highestValue: sc.highestValue,
            $container: $searchContainer,
            collection: collection
          });
          break;
        case 'datetimeRange':
          DatetimeRange.create({
            title: sc.title,
            name: sc.name,
            earliest: sc.earliest,
            latest: sc.latest,
            $container: $searchContainer,
            collection: collection
          });
          break;
      }
    });

    FullText.create({
      $container: $searchContainer,
      collection: collection
    });

    $searchGroup.detach().appendTo($searchContainer);

  };

  _armSearchColOpts = function(){
    return _.reduce(columns, function(searchCols, col){
      var sc = _.find(searchColumns, {'name': col.name});
      if(sc){
        switch(sc.type){
          case 'keywords':
            searchCols.push({search:_(sc.selectedItems).map("value").filter().flatMap().value().join(',')});
            break;
          case 'numericRange':
            searchCols.push({search: sc.lowestValue||sc.highestValue ? [sc.lowestValue, sc.highestValue].join(','):''});
            break;
          case 'datetimeRange':
            searchCols.push({search: sc.earliest||sc.latest ? [sc.earliest, sc.latest].join(','):''});
            break;
        }
      }else{
        searchCols.push(null);        
      }

      return searchCols;            
    },[]);
  };

  _refreshHeader = function(){
    if(_isDirty()){
      $saveBtn.html("Save");
      $saveBtn.removeAttr('data-toggle');
      $modified.show();
      $dropdownToggle.show();
    }else{
      $saveBtn.html("Save as ...");
      $saveBtn.attr({'data-toggle':'modal'});      
      $modified.hide();
      $dropdownToggle.hide();
    }   
  };

  _isDirty = function(){
    return jiff.diff(columns, view.columns).length > 0 || jiff.diff(searchColumns, view.searchColumns).length > 0
  };

  _searchColumnType = function(name){
    var index = _.findIndex(searchColumns, function(sc) { return sc.name == name;});
    return searchColumns[index].type;
  };

  _kvMap = function(aoData){
    return _.reduce(aoData, function(result, kv){result[kv.name] = kv.value;return result;},{});
  }

  _buildSearch = function(kvMap){
    var 
      iColumns = kvMap['iColumns'], iDisplayStart = kvMap['iDisplayStart'], iSortingCols = kvMap["iSortingCols"],
      iDisplayLength = kvMap['iDisplayLength'], sSearch = kvMap['sSearch'], mustArray=[], 
      searchNames = (view.search&&view.search.names)||[], sort = [];

    for(var i=0; i<iColumns; i++){
      var mDataProp_i=kvMap['mDataProp_'+i], sSearch_i=kvMap['sSearch_'+i], shouldArray = [], range;

      if(sSearch_i.trim() != ''){
        switch(_searchColumnType(mDataProp_i)){
          case 'keywords':
            _.each(sSearch_i.split(','), function(token){
              if(token.trim() !=''){
                var term = {};
                term[mDataProp_i+'.keyword'] = token;
                shouldArray.push({term:term});
              }
            });
            break;
         case 'datetimeRange':
         case 'numericRange':
            var ra = sSearch_i.split(','), lv = ra[0], hv = ra[1];
            range = {};
            range[mDataProp_i]={};
            if(lv){
              range[mDataProp_i].gte = lv;
            }
            if(hv){
              range[mDataProp_i].lte = hv;
            }
            break;         
        }

        if(shouldArray.length > 0){
          mustArray.push({bool:{should:shouldArray}});
        }
        if(range){
          mustArray.push({bool:{filter:{range:range}}});
        }
      }
    }

    if(sSearch != ''){
      mustArray.push({bool:{must:{query_string:{fields:searchNames||[], query:'*'+sSearch+'*'}}}});
    }

    if(iSortingCols > 0){
      sort= new Array(iSortingCols);
      for(var i = 0; i<iSortingCols; i++){
        var current = {}, colName = kvMap['mDataProp_'+kvMap['iSortCol_'+i]];
        switch(_searchColumnType(colName)){
          case 'keywords':
            current[colName+'.keyword'] = kvMap['sSortDir_'+i];
            break;
          case 'numericRange':
          default:
            current[colName] = kvMap['sSortDir_'+i];
            break;
        }
        sort[i] = current;
      }
      sort = sort.reverse();
    }

    return {
      query:mustArray.length > 0 ? {bool:{must:mustArray}}:{match_all:{}},
      sort:sort,
      from:kvMap['iDisplayStart'],
      size:kvMap['iDisplayLength']
    };
  };

  _onSubmit = function(evt){
    var errors = validate($form, constraints);
    if (errors) {
      utils.showErrors($form, errors);
    } else {
      var values = validate.collectFormValues($form, {trim: true}), title = values.title;
      
      utils.clearErrors($form);
    }        
  };

  _init = function(v, c){
    view = v;
    collection = c;
    columns = _.cloneDeep(view.columns);
    searchColumns = _.cloneDeep(view.searchColumns);
    $view = $(viewHtml);
    $viewHeader = ('.view-header', $view);
    $modified = $('.modified', $viewHeader);
    $dropdownToggle = $('.dropdown-toggle', $viewHeader);
    $saveBtn =$('.save.btn', $viewHeader);
    $title = $('h4', $viewHeader);
    $title.html(view.title||view.id);
    $itemSaveAs = $('.dropdown-item.save-as', $viewHeader);
    $itemDiscard = $('.dropdown-item.discard', $viewHeader);
    $form = $('form.new-view', $viewHeader);
    $submitBtn = $('.btn.submit', $viewHeader);
    $searchContainer = $('.search-container', $view);
    $searchGroup = $('.search-group', $searchContainer);
    $container.empty();
    $view.appendTo($container);

    _refreshHeader();
    _initSearchBar();

    table = $('#dataTable').DataTable({
      dom: '<"top"i>rt<"bottom"lp><"clear">',
      lengthMenu: [[10, 25, 50, -1], [10, 25, 50, "All"]],      
      processing: true,
      serverSide: true,
      columns: columns,
      searchCols: _armSearchColOpts(),
      order: [[ 4, "desc" ],[ 5, "desc" ]],
      columnDefs : [{
        targets : [4,5],
        searchable: false,
        type : 'date',
        render: function(data, type, row){
          if(data){
            var date = moment.utc(data);
            return (date && date.isValid()) ? date.format('YYYY-MM-DD HH:mm:ss') : '';
          }
          return "";
        }
      },{
        targets: -1,
        data: null,
        defaultContent: "<button>Click!</button>"
      }],
      sAjaxSource: "view",
      fnServerData: function (sSource, aoData, fnCallback, oSettings ) {
        var kvMap = _kvMap(aoData), query = _buildSearch(kvMap);
        collection.findDocuments(query, function(err, docs){
          if(err) return console.log(err);
          fnCallback({
            "sEcho": kvMap['sEcho'],
            "iTotalRecords": docs.total,
            "iTotalDisplayRecords": docs.total,
            "aaData": docs.documents
          });
        });
      }
    });

    $saveBtn.on('click', save);
    $itemDiscard.on('click', discard);
    $submitBtn.on('click', _onSubmit);
    $form.bind('submit', _onSubmit);

    $searchContainer.on('valueChanged', function(event, data){
      var sc;
      switch(data.type){
        case 'fullText':
          table.search(data.keyword).draw();
          break;
        case 'keywords':
          sc = _.find(searchColumns, {'name': data.name});
          sc.selectedItems = data.selectedItems;
          table.column(sc.name+':name')
               .search(_(sc.selectedItems).map("value").filter().flatMap().value().join(','))
               .draw();
          break;
        case 'numericRange':
          sc = _.find(searchColumns, {'name': data.name});
          sc.lowestValue = data.lowestValue;
          sc.highestValue = data.highestValue;
          table.column(sc.name+':name')
               .search(sc.lowestValue||sc.highestValue ? [sc.lowestValue, sc.highestValue].join(','):'')
               .draw();
          break;
        case 'datetimeRange':
          sc = _.find(searchColumns, {'name': data.name});
          sc.earliest = data.earliest;
          sc.latest = data.latest;
          table.column(sc.name+':name')
               .search(sc.earliest||sc.latest ? [sc.earliest, sc.latest].join(','):'')
               .draw();              
          break;
      }

      _refreshHeader();
    });
  };

  domain.getCollection('.views', function(err1, views){
    views.getDocument(viewId, function(err2, viewDoc){
      domain.getCollection(viewDoc.collections[0], function(err3, collection){
        _init(viewDoc, collection);
      });

//       if(err) return _showAlertMessage(err.message);
//       _.each(collections, function(collection){
//         $(_armListGroupItem(collection.id)).data('item', collection).appendTo($listGroup);
//       });

    });
  });

  save = function(){
    if(_isDirty()){
      var newView = _.assignIn({}, view, {columns: columns, searchColumns: searchColumns});
      view.patch(jiff.diff(view, newView), function(err, result){
        columns = _.cloneDeep(view.columns);
        searchColumns = _.cloneDeep(view.searchColumns);
        console.log(arguments);
        _refreshHeader();
      });
    }
  };

  saveAs = function(){

  };

  discard = function(){
    columns = _.cloneDeep(view.columns);
    searchColumns = _.cloneDeep(view.searchColumns);
    _refreshHeader();
    _initSearchBar();

    _.each(searchColumns, function(sc){
      switch(sc.type){
        case 'keywords':
          table.column(sc.name+':name')
               .search(_(sc.selectedItems).map("value").filter().flatMap().value().join(','));
          break;
        case 'numericRange':
          table.column(sc.name+':name')
               .search(sc.lowestValue||sc.highestValue ? [sc.lowestValue, sc.highestValue].join(','):'');
          break;
        case 'datetimeRange':
          table.column(sc.name+':name')
               .search(sc.earliest||sc.latest ? [sc.earliest, sc.latest].join(','):'');
          break;
      }
    });

    table.draw();
  };

  return {save: save, saveAs:saveAs, discard: discard};
};

export default {
  create: create
}

//   var table = $('#dataTable').DataTable({
//     "bProcessing": true,
//     "bServerSide": true,
//     "sAjaxSource": "view",
//     "fnServerData": function ( sSource, aoData, fnCallback, oSettings ) {
//       console.log(arguments);
//       oSettings.jqXHR = $.ajax( {
//         "dataType": 'json',
//         "type": "POST",
//         "url": sSource,
//         "data": aoData,
//         "success": fnCallback
//       } );
//     },
//     initComplete: function () {
//       var api = this.api();
//       api.columns().indexes().flatten().each( function ( i ) {
//         var column = api.column( i );
//         var select = $('<select><option value=""></option></select>')
//           .appendTo( $(column.footer()).empty() )
//           .on( 'change', function () {
//             var val = $.fn.dataTable.util.escapeRegex($(this).val());
//             column.search( val ? '^'+val+'$' : '', true, false ).draw();
//           });
//         column.data().unique().sort().each( function ( d, j ) {
//           select.append( '<option value="'+d+'">'+d+'</option>' )
//         });
//       });
//     }    
//   });

//   var data = table.rows().data();
//   console.log(JSON.stringify(data));
  
//   table.columns().flatten().each( function ( colIdx ) {
//     // Create the select list and search operation
//     var select = $('<select />').appendTo(table.column(colIdx).footer())
//         .on('change', function(){
//           table.column( colIdx ).search($(this).val()).draw();
//          });
//     // Get the search data for the first column and add to the select list
//     table.column(colIdx).cache('search').sort().unique().each(function(d){
//       select.append($('<option value="'+d+'">'+d+'</option>'));
//     });
//   });
