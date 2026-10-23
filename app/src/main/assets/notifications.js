// Discord's web client uses the browser Notification API, which Android WebView lacks.
// This stands in for it and forwards notifications to the native AndroidNotifier bridge.
(function () {
  if (window.__dnInstalled || !window.AndroidNotifier) return;
  window.__dnInstalled = true;

  var live = {};
  var permCallbacks = {};
  var nextId = 1;

  function DiscordNotification(title, options) {
    options = options || {};
    var self = this;
    var target = document.createDocumentFragment();
    this.addEventListener = target.addEventListener.bind(target);
    this.removeEventListener = target.removeEventListener.bind(target);
    this.dispatchEvent = target.dispatchEvent.bind(target);
    this.title = String(title);
    this.body = options.body || '';
    this.tag = options.tag || '';
    this.icon = options.icon || '';
    this.data = options.data;
    this.silent = !!options.silent;
    this.onclick = this.onshow = this.onclose = this.onerror = null;
    this.__id = nextId++;
    live[this.__id] = this;
    try {
      AndroidNotifier.show(this.__id, this.title, String(this.body), String(this.tag), String(this.icon), this.silent);
    } catch (e) {}
    setTimeout(function () { fire(self, 'show'); }, 0);
  }

  function fire(n, type) {
    var ev = new Event(type, { cancelable: true });
    n.dispatchEvent(ev);
    var handler = n['on' + type];
    if (typeof handler === 'function') handler.call(n, ev);
  }

  DiscordNotification.prototype.close = function () {
    try { AndroidNotifier.close(this.__id, String(this.tag)); } catch (e) {}
    if (live[this.__id]) { delete live[this.__id]; fire(this, 'close'); }
  };

  Object.defineProperty(DiscordNotification, 'permission', {
    get: function () { try { return AndroidNotifier.permission(); } catch (e) { return 'denied'; } }
  });
  DiscordNotification.maxActions = 0;
  DiscordNotification.requestPermission = function (callback) {
    return new Promise(function (resolve) {
      var id = nextId++;
      permCallbacks[id] = function (p) { if (typeof callback === 'function') callback(p); resolve(p); };
      try { AndroidNotifier.requestPermission(id); } catch (e) { permCallbacks[id]('denied'); }
    });
  };

  window.__dnClick = function (id) {
    var n = live[id];
    if (!n) return;
    try { window.focus(); } catch (e) {}
    fire(n, 'click');
  };
  window.__dnPerm = function (id, p) {
    var cb = permCallbacks[id];
    delete permCallbacks[id];
    if (cb) cb(p);
  };

  // Discord skips notifications while the window has focus. Report the app as unfocused in the background.
  var realHasFocus = document.hasFocus.bind(document);
  window.__dnHidden = false;
  document.hasFocus = function () { return !window.__dnHidden && realHasFocus(); };
  window.__dnSetHidden = function (hidden) {
    window.__dnHidden = hidden;
    window.dispatchEvent(new Event(hidden ? 'blur' : 'focus'));
  };

  Object.defineProperty(window, 'Notification', { value: DiscordNotification, writable: true, configurable: true });
})();
