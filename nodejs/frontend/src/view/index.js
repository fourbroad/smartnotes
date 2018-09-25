import './view.scss';

// import 'datatables.net';
import 'datatables.net-bs4';
import 'datatables.net-bs4/css/dataTables.bootstrap4.css';
import validate from "validate.js";
import utils from 'utils';
import Keywords from 'search/keywords';
import NumericRange from 'search/numeric-range';
import DatetimeRange from 'search/datetime-range';
import FullText from 'search/full-text'
import viewHtml from './view.html';

function create(opts) {
  const
    domain = opts.domain,
    viewId = opts.viewId,
    constraints = {
      title: {
        presence: true
      }
    },
    viewLinkTemplate = _.template('<a href="#!module=${module}:viewId,${viewId}">${text}</a>'),
    docLinkTemplate = _.template('<a href="#!module=${module}:docId,${docId}">${text}</a>');

  var
    view, table, columns, searchColumns,
    $container = opts.$container, $view, $viewHeader, $viewTitle, $modified, $dropdownToggle, 
    $searchContainer, $saveBtn, $itemSaveAs, $itemDiscard, $form, $submitBtn,
    $newViewModel, $viewTable, $titleInput,
    _init, _initSearchBar, _armSearchCol, _searchColumnType, _kvMap, _buildSearch, _isDirty, 
    _refreshHeader, _onSubmit, _setRowActive, _clearRowActive, _isRowActive, _showDocMenu,
    save, saveAs, onDiscard;

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
            view: view
          });
          break;
        case 'numericRange':
          NumericRange.create({
            title: sc.title,
            name: sc.name,
            lowestValue: sc.lowestValue,
            highestValue: sc.highestValue,
            $container: $searchContainer,
            view: view
          });
          break;
        case 'datetimeRange':
          DatetimeRange.create({
            title: sc.title,
            name: sc.name,
            earliest: sc.earliest,
            latest: sc.latest,
            $container: $searchContainer,
            view: view
          });
          break;
      }
    });

    FullText.create({
      $container: $searchContainer,
      view: view
    });
  };

  _armSearchCol = function(){
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
      query:{bool:{must:mustArray}},
      sort:sort,
      from:kvMap['iDisplayStart'],
      size:kvMap['iDisplayLength']
    };
  };

  _onSubmit = function(evt){
    evt.preventDefault();
    evt.stopPropagation();

    var errors = validate($form, constraints);
    if (errors) {
      utils.showErrors($form, errors);
    } else {
      var values = validate.collectFormValues($form, {trim: true}), title = values.title, viewInfo = _.cloneDeep(view);
      delete viewInfo.id;
      delete viewInfo.collectionId;
      delete viewInfo.domainId;
      viewInfo.title = title;
      currentDomain.createView(viewInfo, function(err, v){
        view.refresh(function(){
          table.draw(false);
          $newViewModel.modal('toggle')
        })
      })
      utils.clearErrors($form);
    }
  };

  _showDocMenu = function($dropdownMenu, doc){
    $('<li class="dropdown-item delete"><span>Delete</span></li>').appendTo($dropdownMenu.empty());
  };

  _setRowActive = function($row){
    table.$('tr.table-active').removeClass('table-active');
    $row.addClass('table-active');
  };

  _clearRowActive = function($row){
    $row.removeClass('table-active');
  };

  _isRowActive = function($row){
    return $row.hasClass('table-active');
  };

  _init = function(v){
    view = v;
    columns = _.cloneDeep(view.columns);
    searchColumns = _.cloneDeep(view.searchColumns);
    $view = $(viewHtml);
    $viewHeader = ('.view-header', $view);
    $viewTable = $('#viewTable', $view);
    $modified = $('.modified', $viewHeader);
    $dropdownToggle = $('.dropdown-toggle', $viewHeader);
    $saveBtn =$('.save.btn', $viewHeader);
    $viewTitle = $('h4', $viewHeader);
    $viewTitle.html(view.title||view.id);
    $itemSaveAs = $('.dropdown-item.save-as', $viewHeader);
    $itemDiscard = $('.dropdown-item.discard', $viewHeader);
    $newViewModel = $('#newView', $viewHeader);
    $titleInput = $('input[name="title"]', $newViewModel);
    $form = $('form.new-view', $newViewModel);
    $submitBtn = $('.btn.submit', $newViewModel);
    $searchContainer = $('.search-container', $view);
    $container.empty();
    $view.appendTo($container);

    _refreshHeader();
    _initSearchBar();

    table = $viewTable.DataTable({
      dom: '<"top"i>rt<"bottom"lp><"clear">',
      lengthMenu: [[10, 25, 50, -1], [10, 25, 50, "All"]],      
      processing: true,
      serverSide: true,
      columns: columns,
      searchCols: _armSearchCol(),
      order: JSON.parse(view.order)||[],
      columnDefs : [{
        targets:'_all',
        render:function(data, type, row, meta){
          var column = meta.settings.aoColumns[meta.col], text = data;
          switch(column.className){
            case 'id':
            case 'title':
              text = row._metadata.type =='view' ? viewLinkTemplate({module:'view', viewId: row.id, text: data}) : docLinkTemplate({module:'document', docId: row.id, text: data});
              break;
            case 'datetime':
              var date = moment.utc(data);
              text = (date && date.isValid()) ? date.format('YYYY-MM-DD HH:mm:ss') : '';
              break;
            default:
              break;
          }
          return text;
        }
      },{
        targets: -1,
        width: "30px",
        data: null,
        defaultContent: '<button type="button" class="btn btn-outline-secondary btn-sm btn-light" data-toggle="dropdown"><i class="fa fa-ellipsis-h"></i></button><ul class="dropdown-menu dropdown-menu-right"></ul>'
      }],
      sAjaxSource: "view",
      fnServerData: function (sSource, aoData, fnCallback, oSettings ) {
        var kvMap = _kvMap(aoData), query = _buildSearch(kvMap);
        view.findDocuments(query, function(err, docs){
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

    $('tbody', $viewTable).on('click show.bs.dropdown', 'tr', function(evt){
      var $this = $(this);
      if(evt.type == 'show'){
        if(!_isRowActive($this)){
          _setRowActive($this);
        }
        _showDocMenu($(evt.target).find('.dropdown-menu'), table.row(this).data());
      } else {
        if(_isRowActive($this)){
          _clearRowActive($this);
        } else {
          _setRowActive($this);
        }
      }
    });

    $('tbody', $viewTable).on('click','li.dropdown-item', function(evt){
      var $this = $(this), $tr = $this.parents('tr'), v = table.row($tr).data();

//       table.row('.table-active').remove().draw( false );
      v.remove(function(err, result){
        setTimeout(function(){
          table.draw(false);
        }, 1000);
      });

      evt.stopPropagation();
    });


    $newViewModel.on('shown.bs.modal', function () {
      $titleInput.val('');
      $titleInput.trigger('focus')
    })    

    $saveBtn.on('click', save);
    $itemDiscard.on('click', onDiscard);
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

  domain.getView(viewId, function(err, view){
    _init(view);
  });

  save = function(){
    if(_isDirty()){
      var newView = _.assignIn({}, view, {columns: columns, searchColumns: searchColumns});
      view.patch(jiff.diff(view, newView), function(err, result){
        columns = _.cloneDeep(view.columns);
        searchColumns = _.cloneDeep(view.searchColumns);
        _refreshHeader();
      });
    }
  };

  saveAs = function(){

  };

  onDiscard = function(){
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

  return {save: save, saveAs:saveAs};
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
