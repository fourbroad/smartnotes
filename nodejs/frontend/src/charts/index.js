import './easyPieChart.scss';

import * as $ from 'jquery';
import 'jquery-sparkline';
import { debounce } from 'lodash';
import Masonry from 'masonry-layout';
import chartsdHtml from './charts.html';
import { COLORS } from '../constants/colors';
import 'easy-pie-chart/dist/jquery.easypiechart.min.js';
import Chart from 'chart.js';

var
  init;

init = function () {

  $('#mainContent').html(chartsdHtml);

  if ($('.masonry').length > 0) {
    new Masonry('.masonry', {
      itemSelector: '.masonry-item',
      columnWidth: '.masonry-sizer',
      percentPosition: true,
    });
  }


  // ------------------------------------------------------
  // @Dashboard Sparklines
  // ------------------------------------------------------

  const drawSparklines = () => {
    if ($('#sparklinedash').length > 0) {
      $('#sparklinedash').sparkline([0, 5, 6, 10, 9, 12, 4, 9], {
        type: 'bar',
        height: '20',
        barWidth: '3',
        resize: true,
        barSpacing: '3',
        barColor: '#4caf50',
      });
    }

    if ($('#sparklinedash2').length > 0) {
      $('#sparklinedash2').sparkline([0, 5, 6, 10, 9, 12, 4, 9], {
        type: 'bar',
        height: '20',
        barWidth: '3',
        resize: true,
        barSpacing: '3',
        barColor: '#9675ce',
      });
    }

    if ($('#sparklinedash3').length > 0) {
      $('#sparklinedash3').sparkline([0, 5, 6, 10, 9, 12, 4, 9], {
        type: 'bar',
        height: '20',
        barWidth: '3',
        resize: true,
        barSpacing: '3',
        barColor: '#03a9f3',
      });
    }

    if ($('#sparklinedash4').length > 0) {
      $('#sparklinedash4').sparkline([0, 5, 6, 10, 9, 12, 4, 9], {
        type: 'bar',
        height: '20',
        barWidth: '3',
        resize: true,
        barSpacing: '3',
        barColor: '#f96262',
      });
    }
  };

  drawSparklines();

  // Redraw sparklines on resize
  $(window).resize(debounce(drawSparklines, 150));

  // ------------------------------------------------------
  // @Other Sparklines
  // ------------------------------------------------------

  $('#sparkline').sparkline(
    [5, 6, 7, 9, 9, 5, 3, 2, 2, 4, 6, 7],
    {
      type: 'line',
      resize: true,
      height: '20',
    }
  );

  $('#compositebar').sparkline(
    'html',
    {
      type: 'bar',
      resize: true,
      barColor: '#aaf',
      height: '20',
    }
  );

  $('#compositebar').sparkline(
    [4, 1, 5, 7, 9, 9, 8, 7, 6, 6, 4, 7, 8, 4, 3, 2, 2, 5, 6, 7],
    {
      composite: true,
      fillColor: false,
      lineColor: 'red',
      resize: true,
      height: '20',
    }
  );

  $('#normalline').sparkline(
    'html',
    {
      fillColor: false,
      normalRangeMin: -1,
      resize: true,
      normalRangeMax: 8,
      height: '20',
    }
  );

  $('.sparktristate').sparkline(
    'html',
    {
      type: 'tristate',
      resize: true,
      height: '20',
    }
  );

  $('.sparktristatecols').sparkline(
    'html',
    {
      type: 'tristate',
      colorMap: {
        '-2': '#fa7',
        resize: true,
        '2': '#44f',
        height: '20',
      },
    }
  );

  const values    = [5, 4, 5, -2, 0, 3, -5, 6, 7, 9, 9, 5, -3, -2, 2, -4];
  const valuesAlt = [1, 1, 0, 1, -1, -1, 1, -1, 0, 0, 1, 1];

  $('.sparkline').sparkline(values, {
    type: 'line',
    barWidth: 4,
    barSpacing: 5,
    fillColor: '',
    lineColor: COLORS['red-500'],
    lineWidth: 2,
    spotRadius: 3,
    spotColor: COLORS['red-500'],
    maxSpotColor: COLORS['red-500'],
    minSpotColor: COLORS['red-500'],
    highlightSpotColor: COLORS['red-500'],
    highlightLineColor: '',
    tooltipSuffix: ' Bzzt',
    tooltipPrefix: 'Hello ',
    width: 100,
    height: undefined,
    barColor: '9f0',
    negBarColor: 'ff0',
    stackedBarColor: ['ff0', '9f0', '999', 'f60'],
    sliceColors: ['ff0', '9f0', '000', 'f60'],
    offset: '30',
    borderWidth: 1,
    borderColor: '000',
  });

  $('.sparkbar').sparkline(values, {
    type: 'bar',
    barWidth: 4,
    barSpacing: 1,
    fillColor: '',
    lineColor: COLORS['deep-purple-500'],
    tooltipSuffix: 'Celsius',
    width: 100,
    barColor: '39f',
    negBarColor: COLORS['deep-purple-500'],
    stackedBarColor: ['ff0', '9f0', '999', 'f60'],
    sliceColors: ['ff0', '9f0', '000', 'f60'],
    offset: '30',
    borderWidth: 1,
    borderColor: '000',
  });

  $('.sparktri').sparkline(valuesAlt, {
    type: 'tristate',
    barWidth: 4,
    barSpacing: 1,
    fillColor: '',
    lineColor: COLORS['light-blue-500'],
    tooltipSuffix: 'Celsius',
    width: 100,
    barColor: COLORS['light-blue-500'],
    posBarColor: COLORS['light-blue-500'],
    negBarColor: 'f90',
    zeroBarColor: '000',
    stackedBarColor: ['ff0', '9f0', '999', 'f60'],
    sliceColors: ['ff0', '9f0', '000', 'f60'],
    offset: '30',
    borderWidth: 1,
    borderColor: '000',
  });

  $('.sparkdisc').sparkline(values, {
    type: 'discrete',
    barWidth: 4,
    barSpacing: 5,
    fillColor: '',
    lineColor: '9f0',
    tooltipSuffix: 'Celsius',
    width: 100,
    barColor: '9f0',

    negBarColor: 'f90',

    stackedBarColor: ['ff0', '9f0', '999', 'f60'],
    sliceColors: ['ff0', '9f0', '000', 'f60'],
    offset: '30',
    borderWidth: 1,
    borderColor: '000',
  });

  $('.sparkbull').sparkline(values, {
    type: 'bullet',
    barWidth: 4,
    barSpacing: 5,
    fillColor: '',
    lineColor: COLORS['amber-500'],
    tooltipSuffix: 'Celsius',
    height: 'auto',
    width: 'auto',
    targetWidth: 'auto',
    barColor: COLORS['amber-500'],
    negBarColor: 'ff0',
    stackedBarColor: ['ff0', '9f0', '999', 'f60'],
    sliceColors: ['ff0', '9f0', '000', 'f60'],
    offset: '30',
    borderWidth: 1,
    borderColor: '000',
  });

  $('.sparkbox').sparkline(values, {
    type: 'box',
    barWidth: 4,
    barSpacing: 5,
    fillColor: '',
    lineColor: '9f0',
    tooltipSuffix: 'Celsius',
    width: 100,
    barColor: '9f0',
    negBarColor: 'ff0',
    stackedBarColor: ['ff0', '9f0', '999', 'f60'],
    sliceColors: ['ff0', '9f0', '000', 'f60'],
    offset: '30',
    borderWidth: 1,
    borderColor: '000',
  });


  if ($('.easy-pie-chart').length > 0) {
    $('.easy-pie-chart').easyPieChart({
      onStep(from, to, percent) {
        this.el.children[0].innerHTML = `${Math.round(percent)} %`;
      },
    });
  }


  // ------------------------------------------------------
  // @Line Charts
  // ------------------------------------------------------

  const lineChartBox = document.getElementById('line-chart');

  if (lineChartBox) {
    const lineCtx = lineChartBox.getContext('2d');
    lineChartBox.height = 80;

    new Chart(lineCtx, {
      type: 'line',
      data: {
        labels: ['January', 'February', 'March', 'April', 'May', 'June', 'July'],
        datasets: [{
          label                : 'Series A',
          backgroundColor      : 'rgba(237, 231, 246, 0.5)',
          borderColor          : COLORS['deep-purple-500'],
          pointBackgroundColor : COLORS['deep-purple-700'],
          borderWidth          : 2,
          data                 : [60, 50, 70, 60, 50, 70, 60],
        }, {
          label                : 'Series B',
          backgroundColor      : 'rgba(232, 245, 233, 0.5)',
          borderColor          : COLORS['blue-500'],
          pointBackgroundColor : COLORS['blue-700'],
          borderWidth          : 2,
          data                 : [70, 75, 85, 70, 75, 85, 70],
        }],
      },

      options: {
        legend: {
          display: false,
        },
      },

    });
  }

  // ------------------------------------------------------
  // @Bar Charts
  // ------------------------------------------------------

  const barChartBox = document.getElementById('bar-chart');

  if (barChartBox) {
    const barCtx = barChartBox.getContext('2d');

    new Chart(barCtx, {
      type: 'bar',
      data: {
        labels: ['January', 'February', 'March', 'April', 'May', 'June', 'July'],
        datasets: [{
          label           : 'Dataset 1',
          backgroundColor : COLORS['deep-purple-500'],
          borderColor     : COLORS['deep-purple-800'],
          borderWidth     : 1,
          data            : [10, 50, 20, 40, 60, 30, 70],
        }, {
          label           : 'Dataset 2',
          backgroundColor : COLORS['light-blue-500'],
          borderColor     : COLORS['light-blue-800'],
          borderWidth     : 1,
          data            : [10, 50, 20, 40, 60, 30, 70],
        }],
      },

      options: {
        responsive: true,
        legend: {
          position: 'bottom',
        },
      },
    });
  }

  // ------------------------------------------------------
  // @Area Charts
  // ------------------------------------------------------

  const areaChartBox = document.getElementById('area-chart');

  if (areaChartBox) {
    const areaCtx = areaChartBox.getContext('2d');

    new Chart(areaCtx, {
      type: 'line',
      data: {
        labels: ['January', 'February', 'March', 'April', 'May', 'June', 'July'],
        datasets: [{
          backgroundColor : 'rgba(3, 169, 244, 0.5)',
          borderColor     : COLORS['light-blue-800'],
          data            : [10, 50, 20, 40, 60, 30, 70],
          label           : 'Dataset',
          fill            : 'start',
        }],
      },
    });
  }

  // ------------------------------------------------------
  // @Scatter Charts
  // ------------------------------------------------------

  const scatterChartBox = document.getElementById('scatter-chart');

  if (scatterChartBox) {
    const scatterCtx = scatterChartBox.getContext('2d');

    Chart.Scatter(scatterCtx, {
      data: {
        datasets: [{
          label           : 'My First dataset',
          borderColor     : COLORS['red-500'],
          backgroundColor : COLORS['red-500'],
          data: [
            { x: 10, y: 20 },
            { x: 30, y: 40 },
            { x: 50, y: 60 },
            { x: 70, y: 80 },
            { x: 90, y: 100 },
            { x: 110, y: 120 },
            { x: 130, y: 140 },
          ],
        }, {
          label           : 'My Second dataset',
          borderColor     : COLORS['green-500'],
          backgroundColor : COLORS['green-500'],
          data: [
            { x: 150, y: 160 },
            { x: 170, y: 180 },
            { x: 190, y: 200 },
            { x: 210, y: 220 },
            { x: 230, y: 240 },
            { x: 250, y: 260 },
            { x: 270, y: 280 },
          ],
        }],
      },
    });
  }
};

export default {
  init: init
}