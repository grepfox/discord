// Discord's web client lays out servers | channels | chat as a CSS grid sized for desktop.
// On a phone the chat column ends up 0px wide, so opening a chat shows nothing.
// This shows one pane at a time, like the mobile app:
//   "list" mode: servers + channel/DM list fill the screen
//   "page" mode: the chat (or friends, etc.) fills the screen
// The grid is found by its "page" grid area rather than by Discord's hashed class names.
(function () {
  if (window.__dmInstalled) return;
  window.__dmInstalled = true;

  var WIDE = 900; // at or above this width, leave Discord's own layout alone
  var CHAT_ROUTE = /^\/channels\/[^/]+\/\d+/;

  var style = document.createElement('style');
  style.textContent =
    '[data-dm-base="list"]{grid-template-columns:[start] var(--dm-guilds,72px) [guildsEnd] minmax(0,1fr) [channelsEnd] 0px [end]!important}' +
    '[data-dm-base="page"]{grid-template-columns:[start] 0px [guildsEnd] 0px [channelsEnd] minmax(0,1fr) [end]!important}' +
    '[data-dm-base="page"] [data-dm-side]{display:none!important}' +
    '[data-dm-base="list"] [data-dm-side],[data-dm-base="list"] [data-dm-fill]{width:auto!important;max-width:none!important;min-width:0!important}' +
    '[data-dm-base] [data-dm-resize]{display:none!important}';
  document.documentElement.appendChild(style);

  var mode = CHAT_ROUTE.test(location.pathname) ? 'page' : 'list';
  var loadedAt = Date.now();
  var lastClick = { at: 0, inGuilds: false };
  var base = null;

  function findBase() {
    var start = document.querySelector('[data-list-id="chat-messages"]') ||
      document.querySelector('main') ||
      document.querySelector('[class^="page_"],[class*=" page_"]');
    var page = null;
    for (var el = start; el && el !== document.body; el = el.parentElement) {
      var cs = getComputedStyle(el);
      if (!page && cs.gridArea.split(' ')[0] === 'page') { page = el; continue; }
      if (page && cs.display.indexOf('grid') >= 0 && cs.gridTemplateAreas.indexOf('page') >= 0) {
        return { base: el, page: page };
      }
    }
    return null;
  }

  function apply() {
    document.querySelectorAll('[data-dm-base],[data-dm-side],[data-dm-resize],[data-dm-guilds],[data-dm-fill]').forEach(function (el) {
      el.removeAttribute('data-dm-fill');
      el.removeAttribute('data-dm-base');
      el.removeAttribute('data-dm-side');
      el.removeAttribute('data-dm-resize');
      el.removeAttribute('data-dm-guilds');
    });
    base = null;
    if (window.innerWidth >= WIDE) return;
    var found = findBase();
    if (!found) return;
    base = found.base;

    // Keep the server rail at its natural width.
    var firstTrack = parseFloat(getComputedStyle(base).gridTemplateColumns.replace(/\[[^\]]*\]/g, '').trim().split(/\s+/)[0]);
    if (firstTrack > 0) base.style.setProperty('--dm-guilds', firstTrack + 'px');

    // Everything in the grid other than the page column and the title bar is sidebar.
    var content = found.page.parentElement;
    for (var i = 0; i < content.children.length; i++) {
      var c = content.children[i];
      if (c === found.page) continue;
      if (/resize/i.test(String(c.className))) c.setAttribute('data-dm-resize', '');
      else c.setAttribute('data-dm-side', '');
    }
    var guilds = document.querySelector('[data-list-id="guildsnav"]');
    if (guilds) guilds.setAttribute('data-dm-guilds', '');

    // Inside the sidebar, the channel list has a saved fixed width; let it fill the screen instead.
    document.querySelectorAll('[data-dm-side]').forEach(function (side) {
      for (var j = 0; j < side.children.length; j++) {
        var k = side.children[j];
        if (/resize/i.test(String(k.className))) k.setAttribute('data-dm-resize', '');
        else if (!(guilds && k.contains(guilds)) && !/absolute|fixed/.test(getComputedStyle(k).position)) {
          k.setAttribute('data-dm-fill', '');
        }
      }
    });
    base.setAttribute('data-dm-base', mode);
  }

  function intact() {
    if (window.innerWidth >= WIDE) return base === null;
    return base && base.isConnected && base.getAttribute('data-dm-base') === mode &&
      document.querySelector('[data-dm-side]');
  }

  function setMode(m) {
    mode = m;
    if (base && base.isConnected && window.innerWidth < WIDE) base.setAttribute('data-dm-base', m);
    else apply();
  }

  // Returns true if it handled the back press (closed a dialog or went back to the list).
  window.__dmBack = function () {
    var dialog = document.querySelector('[role="dialog"]');
    if (dialog) {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true }));
      return true;
    }
    if (window.innerWidth < WIDE && mode === 'page') { setMode('list'); return true; }
    return false;
  };

  document.addEventListener('click', function (e) {
    var inGuilds = false;
    for (var el = e.target; el && el !== document.body; el = el.parentElement) {
      if (el.hasAttribute && el.hasAttribute('data-dm-guilds')) { inGuilds = true; break; }
    }
    lastClick = { at: Date.now(), inGuilds: inGuilds };

    // Tapping a channel or DM in the sidebar opens it, even if it's already the selected one.
    if (!inGuilds && mode === 'list' && window.innerWidth < WIDE) {
      var link = e.target.closest && e.target.closest('a[href^="/channels/"]');
      if (link && link.closest('[data-dm-side]')) setTimeout(function () { setMode('page'); }, 0);
    }
  }, true);

  var lastPath = location.pathname;
  function onRoute() {
    var path = location.pathname;
    if (path === lastPath) return;
    lastPath = path;
    if (Date.now() - lastClick.at < 1500) {
      // Tapping a server shows its channel list; tapping anything else opens it.
      setMode(lastClick.inGuilds ? 'list' : 'page');
    } else if (Date.now() - loadedAt < 4000) {
      setMode(CHAT_ROUTE.test(path) ? 'page' : 'list'); // startup redirects
    } else {
      setMode('page'); // e.g. tapping a notification
    }
  }
  ['pushState', 'replaceState'].forEach(function (fn) {
    var orig = history[fn];
    history[fn] = function () {
      var r = orig.apply(this, arguments);
      setTimeout(onRoute, 0);
      return r;
    };
  });
  window.addEventListener('popstate', function () { setTimeout(onRoute, 0); });
  window.addEventListener('resize', function () { setTimeout(apply, 50); });

  // React can remount the layout; re-apply when our markers disappear.
  var pending = false;
  function check() {
    pending = false;
    if (!intact()) apply();
  }
  function start() {
    new MutationObserver(function () {
      if (!pending) { pending = true; setTimeout(check, 200); }
    }).observe(document.body, { childList: true, subtree: true });
    setInterval(check, 1000);
    apply();
  }
  if (document.body) start(); else document.addEventListener('DOMContentLoaded', start);
})();
