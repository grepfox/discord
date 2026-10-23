// Reports the colors at the top and bottom edges of the page so the app can paint
// the status and navigation bars to match, like the official app does.
(function () {
  if (window.__dtInstalled || !window.AndroidTheme) return;
  window.__dtInstalled = true;

  var canvas = document.createElement('canvas');
  canvas.width = canvas.height = 1;
  var ctx = canvas.getContext('2d', { willReadFrequently: true });

  // Discord uses oklab() colors; let the canvas convert them to sRGB hex.
  function toHex(color) {
    ctx.clearRect(0, 0, 1, 1);
    ctx.fillStyle = '#000';
    ctx.fillStyle = color;
    ctx.fillRect(0, 0, 1, 1);
    var d = ctx.getImageData(0, 0, 1, 1).data;
    return '#' + [d[0], d[1], d[2]].map(function (v) { return ('0' + v.toString(16)).slice(-2); }).join('');
  }

  function solidBackgroundAt(x, y) {
    for (var el = document.elementFromPoint(x, y); el; el = el.parentElement) {
      var c = getComputedStyle(el).backgroundColor;
      if (c && c !== 'transparent' && !/rgba\(0, 0, 0, 0\)/.test(c) && !/\/ 0(\.\d+)?\)$/.test(c)) return c;
    }
    return getComputedStyle(document.body).backgroundColor;
  }

  var last = '';
  function report() {
    if (!document.body) return;
    var w = window.innerWidth;
    var top = toHex(solidBackgroundAt(w / 2, 1));
    var bottom = toHex(solidBackgroundAt(w / 2, window.innerHeight - 1));
    var key = top + bottom;
    if (key === last) return;
    last = key;
    AndroidTheme.setBarColors(top, bottom);
  }
  setInterval(report, 1000);
  window.addEventListener('load', report);
})();
