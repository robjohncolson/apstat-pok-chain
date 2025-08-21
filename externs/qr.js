/**
 * @fileoverview Externs for QR code libraries to prevent advanced compilation issues
 */

var QRCode = {};
var jsQR = {};

/**
 * @param {Element} element
 * @param {string} text
 * @param {Object=} options
 * @constructor
 */
QRCode = function(element, text, options) {};

/**
 * @param {Uint8ClampedArray} data
 * @param {number} width
 * @param {number} height
 * @param {Object=} options
 * @return {Object}
 */
jsQR = function(data, width, height, options) {};
