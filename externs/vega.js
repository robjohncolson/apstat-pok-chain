/**
 * @fileoverview Externs for Vega-Lite library to prevent advanced compilation issues
 */

var vega = {};
var vegaLite = {};

/**
 * @param {Object} spec
 * @param {Element} element
 * @return {Object}
 */
vegaLite.embed = function(element, spec) {};

/**
 * @param {Object} data
 * @return {Object}
 */
vega.loader = function() {};

/**
 * @param {string} type
 * @return {Object}
 */
vega.read = function(data, type) {};
