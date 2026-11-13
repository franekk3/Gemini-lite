/*
 * Gemini Lite - in-page enhancements for gemini.google.com
 *
 *  1. Hides paid-plan upsells and "get the app" promos (CSS + text heuristics).
 *  2. Focuses the composer and asks the native shell to show the keyboard.
 *  3. Starts a fresh conversation using the page's own "new chat" control.
 *  4. Reports the page background/theme so system bars blend in.
 *
 * The script is idempotent: injecting it again only rescans.
 */
(function () {
  'use strict';

  var APP_URL = 'https://gemini.google.com/app';
  var HIDDEN_ATTR = 'data-gl-hidden';
  var CONTENT_PROTECT =
    'rich-textarea, model-response, user-query, message-content, .response-container, ' +
    '.conversation-container, chat-window, .model-response-text, .markdown';

  var UPGRADE_RES = [
    /^\s*upgrade\s*$/i,
    /\bupgrade\b[^.!?]{0,40}\b(plan|advanced|pro|ultra|google\s+ai)\b/i,
    /\b(get|try|buy|unlock|subscribe\s+to)\b[^.!?]{0,30}\b(gemini\s+)?(advanced|pro|ultra)\b/i,
    /\bgoogle\s+ai\s+(pro|ultra)\b/i,
    /\b(ai\s+)?(pro|ultra)\s+plan\b/i,
    /\bget\s+more\s+(access|usage|generations|credits)\b/i,
    /\bpay\s+as\s+you\s+go\b/i,
    /\bupgrade\s+to\s+google\s+ai\b/i,
    /\bulepsz\b|\bkup\s+plan\b|\bprzejd[zź]\s+na\s+(gemini\s+)?(pro|ultra)\b/i
  ];

  var SHORT_UPGRADE_RE = /\bgemini\s+(advanced|pro|ultra)\b/i;

  var APP_RES = [
    /\b(get|download|install)\b[^.!?]{0,25}\bapp(lication)?\b/i,
    /\bopen\s+in\s+(the\s+)?(gemini\s+)?app\b/i,
    /\bcontinue\s+in\s+(the\s+)?app\b/i,
    /\buse\s+gemini\s+(in|on)\s+the\s+app\b/i,
    /\bscan\s+(the\s+)?qr\b/i,
    /\bqr\s*code\b/i,
    /\bget\s+it\s+on\s+google\s+play\b/i,
    /\bgoogle\s+play\b/i,
    /\bapp\s+store\b/i,
    /\bmobile\s+app\b/i,
    /\b(pobierz|zainstaluj)\s+aplikacj/i,
    /\botw[oó]rz\s+w\s+aplikacji\b/i,
    /\bprzejd[zź]\s+do\s+aplikacji\b/i,
    /\bzeskanuj\s+kod\s+qr\b/i
  ];

  var CSS = [
    'html{overscroll-behavior:none !important;-webkit-tap-highlight-color:transparent !important;}',
    '::-webkit-scrollbar{width:0 !important;height:0 !important;}',
    '[' + HIDDEN_ATTR + '="1"]{display:none !important;}',
    'g1-dynamic-upsell-button,',
    'upsell-button,',
    'desktop-get-app-button,',
    'div.buttons-container.adv-upsell,',
    '.adv-upsell,',
    '.upgrade-container,',
    '.upgrade-button-container,',
    '.g1-upsell-container,',
    '.tier1-upsell-container,',
    '.upsell-label,',
    '.announcement-banner-container,',
    '.gem-banner-container,',
    '[data-test-id*="upgrade" i],',
    '[data-testid*="upgrade" i],',
    '[data-test-id="g1-dynamic-upsell-button"],',
    '[data-test-id="bard-g1-dynamic-upsell-menu-button"],',
    'a[href*="play.google.com/store/apps/details?id=com.google.android.apps.bard"],',
    'a[href*="apps.apple.com/app/google-gemini"],',
    'a[href*="mweb_upsell"]{display:none !important;}'
  ].join('\n');

  var FOCUS_SELECTORS = [
    'rich-textarea .ql-editor[contenteditable="true"]',
    'rich-textarea [contenteditable="true"]',
    '.ql-editor[contenteditable="true"]',
    '[contenteditable="true"][role="textbox"]',
    'div[contenteditable="true"]',
    'textarea[aria-label]',
    'textarea'
  ];

  var NEW_CHAT_SELECTORS = [
    'button[aria-label="New chat" i]',
    'a[aria-label="New chat" i]',
    'button[aria-label="Nowy czat" i]',
    '[data-test-id="new-chat-button"]',
    '[data-test-id*="new-chat" i]',
    'a[href="/app"]',
    'a[href="https://gemini.google.com/app"]'
  ];

  var MIC_SELECTORS = [
    'button[aria-label*="dyktuj" i]',
    'button[aria-label*="dictate" i]',
    'button[aria-label*="microphone" i]',
    'button[aria-label*="mikrofon" i]',
    'button[aria-label*="voice input" i]',
    'button[aria-label*="głos" i]',
    'button[title*="microphone" i]',
    'button[title*="dyktuj" i]'
  ].join(',');

  var STOP_DICTATION_RE =
    /zatrzymaj dyktowanie|stop dictation|stop mikrofon|stop microphone|anuluj dyktowanie|cancel dictation/i;

  var lastThemeSignature = '';
  var scanQueued = false;
  var scanTimer = 0;
  var booted = false;

  function normalize(text) {
    return String(text || '').replace(/\s+/g, ' ').trim();
  }

  function matchesAny(text, patterns) {
    var value = normalize(text);
    if (!value) return false;
    for (var i = 0; i < patterns.length; i++) {
      if (patterns[i].test(value)) return true;
    }
    return false;
  }

  function isVisible(element) {
    if (!element) return false;
    var rect = element.getBoundingClientRect();
    if (rect.width < 2 || rect.height < 2) return false;
    var style = window.getComputedStyle(element);
    return style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0';
  }

  function protectedElement(element) {
    if (!element || !element.closest) return false;
    return !!element.closest(CONTENT_PROTECT);
  }

  function isLandmark(element) {
    if (!element || !element.matches) return true;
    return element.matches(
      'html, body, header, nav, main, aside, #app, app-root, [role="main"], [role="navigation"]'
    );
  }

  function hide(element) {
    if (!element || !element.setAttribute || isLandmark(element)) return;
    if (element.querySelector && element.querySelector(CONTENT_PROTECT)) return;
    element.setAttribute(HIDDEN_ATTR, '1');
    element.setAttribute('aria-hidden', 'true');
  }

  function isPromoText(text, shortOnly) {
    if (!text) return false;
    var value = normalize(text);
    if (value.length > (shortOnly ? 90 : 700)) return false;
    if (matchesAny(value, APP_RES)) return true;
    if (matchesAny(value, UPGRADE_RES)) return true;
    if (shortOnly && value.length <= 60 && matchesAny(value, [SHORT_UPGRADE_RE])) return true;
    return false;
  }

  function containerFor(element) {
    var node = element;
    for (var depth = 1; node && depth <= 3; depth++) {
      var parent = node.parentElement;
      if (!parent || parent === document.body) break;
      node = parent;
      if (isLandmark(node)) break;
      var className = String(node.className || '').toLowerCase();
      var testId = String(node.getAttribute('data-test-id') || '').toLowerCase();
      var looksPromotional =
        className.indexOf('upsell') >= 0 ||
        className.indexOf('upgrade') >= 0 ||
        className.indexOf('promo') >= 0 ||
        className.indexOf('banner') >= 0 ||
        className.indexOf('get-app') >= 0 ||
        className.indexOf('getapp') >= 0 ||
        testId.indexOf('upsell') >= 0 ||
        testId.indexOf('upgrade') >= 0;
      if (looksPromotional) return node;
    }
    return element;
  }

  function hidePromoDialog(dialog) {
    hide(dialog);
    var pane = dialog.closest ? dialog.closest('.cdk-overlay-pane') : null;
    if (pane && pane !== dialog) hide(pane);
    var backdrops = document.querySelectorAll('.cdk-overlay-backdrop');
    for (var i = 0; i < backdrops.length; i++) hide(backdrops[i]);
  }

  function scan(root) {
    var scope = root && root.querySelectorAll ? root : document.body;
    if (!scope) return;

    var nodes = scope.querySelectorAll(
      'button, a, [role="button"], [role="link"], [role="dialog"], [role="banner"], ' +
        '[role="complementary"], [class*="upsell" i], [class*="upgrade" i], ' +
        '[class*="promo" i], [class*="banner" i], [class*="get-app" i], [class*="getapp" i]'
    );

    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      if (node.getAttribute(HIDDEN_ATTR) === '1') continue;
      if (node.closest && node.closest('[' + HIDDEN_ATTR + '="1"]')) continue;

      var text = node.innerText || node.textContent || '';
      if (!text || text.length > 900) continue;

      var role = node.getAttribute('role');
      var isClickable = node.matches('button, a, [role="button"], [role="link"]');
      var isDialog = role === 'dialog' || role === 'alertdialog';
      var isBannerLike =
        role === 'banner' ||
        role === 'complementary' ||
        node.matches('[class*="banner" i], [class*="promo" i], [class*="upsell" i]');

      if (!isClickable && !isDialog && !isBannerLike) continue;
      if (protectedElement(node) && !isDialog) continue;

      var promo = isPromoText(text, isClickable);
      if (!promo) continue;

      if (isDialog) {
        if (normalize(text).length <= 900) hidePromoDialog(node);
        continue;
      }

      if (isClickable) {
        hide(node);
        continue;
      }

      var target = containerFor(node);
      if (!protectedElement(target) && !isLandmark(target)) hide(target);
    }
  }

  function scheduleScan() {
    if (scanQueued) return;
    scanQueued = true;
    window.clearTimeout(scanTimer);
    scanTimer = window.setTimeout(function () {
      scanQueued = false;
      if (!document.hidden) scan(document.body);
    }, 250);
  }

  function getComposer() {
    for (var i = 0; i < FOCUS_SELECTORS.length; i++) {
      var candidates = document.querySelectorAll(FOCUS_SELECTORS[i]);
      for (var j = 0; j < candidates.length; j++) {
        if (isVisible(candidates[j])) return candidates[j];
      }
    }
    return null;
  }

  function askForKeyboard() {
    try {
      if (window.GeminiHost && window.GeminiHost.keyboard) {
        window.GeminiHost.keyboard();
      }
    } catch (error) {
      /* bridge unavailable */
    }
  }

  function placeCaretAtEnd(element) {
    try {
      if (element.isContentEditable) {
        var range = document.createRange();
        range.selectNodeContents(element);
        range.collapse(false);
        var selection = window.getSelection();
        if (selection) {
          selection.removeAllRanges();
          selection.addRange(range);
        }
      } else if (typeof element.setSelectionRange === 'function') {
        var length = (element.value || '').length;
        element.setSelectionRange(length, length);
      }
    } catch (error) {
      /* not fatal */
    }
  }

  function focusComposer() {
    var composer = getComposer();
    if (!composer) return false;
    try {
      composer.focus({ preventScroll: true });
    } catch (error) {
      try {
        composer.focus();
      } catch (secondError) {
        return false;
      }
    }
    placeCaretAtEnd(composer);
    askForKeyboard();
    return true;
  }

  function focusWithRetry(timeoutMs) {
    // Gemini re-renders its composer shortly after load, which can drop focus
    // right after we set it. Keep re-asserting focus for a short window until
    // the page settles or the user starts editing somewhere else.
    var deadline = Date.now() + Math.min(timeoutMs || 9000, 4500);
    function attempt() {
      var composer = getComposer();
      if (composer) {
        var active = document.activeElement;
        var userEditing =
          active && active !== composer &&
          (active.isContentEditable || /^(INPUT|TEXTAREA)$/.test(active.tagName));
        if (!userEditing && active !== composer) {
          focusComposer();
        }
      }
      if (Date.now() < deadline) window.setTimeout(attempt, 250);
    }
    attempt();
  }

  function findNewChatButton() {
    for (var i = 0; i < NEW_CHAT_SELECTORS.length; i++) {
      var candidates = document.querySelectorAll(NEW_CHAT_SELECTORS[i]);
      for (var j = 0; j < candidates.length; j++) {
        if (isVisible(candidates[j])) return candidates[j];
      }
    }
    return null;
  }

  function currentPath() {
    return String(location.pathname || '').replace(/\/+$/, '');
  }

  function startNewChat() {
    focusWithRetry(9000);
    var path = currentPath();
    var button = findNewChatButton();

    if (button) {
      try {
        button.click();
      } catch (error) {
        /* fall through to focus only */
      }
      // If the click did not navigate (different control than expected),
      // reload the canonical new-chat route after a moment.
      window.setTimeout(function () {
        var now = currentPath();
        if (now !== '' && now !== '/app') {
          location.assign(APP_URL);
        }
      }, 1500);
      return true;
    }

    return path === '' || path === '/app';
  }

  function isDictating() {
    var buttons = document.querySelectorAll('button[aria-label], button[title]');
    for (var i = 0; i < buttons.length; i++) {
      var label =
        (buttons[i].getAttribute('aria-label') || '') + ' ' +
        (buttons[i].getAttribute('title') || '');
      if (STOP_DICTATION_RE.test(label) && isVisible(buttons[i])) return true;
    }
    return false;
  }

  function findMicButton() {
    var candidates = document.querySelectorAll(MIC_SELECTORS);
    for (var i = 0; i < candidates.length; i++) {
      var label =
        (candidates[i].getAttribute('aria-label') || '') + ' ' +
        (candidates[i].getAttribute('title') || '');
      if (STOP_DICTATION_RE.test(label)) continue;
      if (isVisible(candidates[i])) return candidates[i];
    }
    return null;
  }

  function startVoice() {
    if (isDictating()) return true;
    var deadline = Date.now() + 12000;
    function attempt() {
      if (isDictating()) return;
      var mic = findMicButton();
      if (mic) {
        try {
          mic.click();
        } catch (error) {
          /* keep trying until the deadline */
        }
        return;
      }
      if (Date.now() < deadline) window.setTimeout(attempt, 250);
    }
    attempt();
    return true;
  }

  function isTransparentColor(value) {
    if (!value) return true;
    var normalized = String(value).replace(/\s+/g, '').toLowerCase();
    return normalized === 'transparent' ||
      normalized === 'rgba(0,0,0,0)' ||
      /^rgba\([^)]*,0(\.0+)?\)$/.test(normalized);
  }

  function parseColor(value) {
    var match = String(value || '').match(/rgba?\(([^)]+)\)/);
    if (!match) return null;
    var parts = match[1].split(',').map(function (part) {
      return parseInt(part, 10);
    });
    if (parts.length < 3) return null;
    var alpha = parts.length > 3 ? parseFloat(parts[3]) : 1;
    if (!isFinite(alpha) || alpha <= 0.05) return null;
    return [
      Math.max(0, Math.min(255, parts[0] || 0)),
      Math.max(0, Math.min(255, parts[1] || 0)),
      Math.max(0, Math.min(255, parts[2] || 0))
    ];
  }

  function reportTheme() {
    try {
      var root = document.documentElement;
      var body = document.body;
      var background = body ? window.getComputedStyle(body).backgroundColor : '';
      var rgb = isTransparentColor(background) ? null : parseColor(background);
      if (!rgb && root) {
        rgb = parseColor(window.getComputedStyle(root).backgroundColor);
      }
      if (!rgb) rgb = [255, 255, 255];

      var isDark =
        (root && (root.classList.contains('dark-theme') ||
          root.getAttribute('data-theme') === 'dark')) ||
        (body && body.classList.contains('dark-theme')) ||
        rgb[0] * 0.299 + rgb[1] * 0.587 + rgb[2] * 0.114 < 128;

      var signature = (isDark ? 'd' : 'l') + rgb.join(',');
      if (signature === lastThemeSignature) return;
      lastThemeSignature = signature;

      if (window.GeminiHost && window.GeminiHost.theme) {
        window.GeminiHost.theme(isDark, rgb[0], rgb[1], rgb[2]);
      }
    } catch (error) {
      /* theme reporting is best effort */
    }
  }

  function isStoreLink(href) {
    var value = String(href || '').toLowerCase();
    if (value.indexOf('com.google.android.apps.bard') >= 0) return true;
    return value.indexOf('apps.apple.com') >= 0 && value.indexOf('google-gemini') >= 0;
  }

  function installClickBlocker() {
    if (document.__glClickBlocker) return;
    document.__glClickBlocker = true;
    document.addEventListener(
      'click',
      function (event) {
        var target = event.target;
        if (!target || !target.closest) return;
        var link = target.closest('a[href]');
        if (!link) return;
        if (isStoreLink(link.href)) {
          event.preventDefault();
          event.stopPropagation();
        }
      },
      true
    );
  }

  function injectStyles() {
    if (document.getElementById('gl-style')) return;
    var style = document.createElement('style');
    style.id = 'gl-style';
    style.textContent = CSS;
    (document.head || document.documentElement).appendChild(style);
  }

  function installObservers() {
    if (document.__glObserver) return;
    var observer = new MutationObserver(function (mutations) {
      var addedPromo = false;
      for (var i = 0; i < mutations.length; i++) {
        var mutation = mutations[i];
        if (mutation.type === 'childList' && mutation.addedNodes.length) {
          addedPromo = true;
          break;
        }
      }
      if (addedPromo) scheduleScan();
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });
    document.__glObserver = observer;

    var themeObserver = new MutationObserver(reportTheme);
    themeObserver.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['class', 'data-theme']
    });
    if (document.body) {
      themeObserver.observe(document.body, {
        attributes: true,
        attributeFilter: ['class', 'data-theme', 'style']
      });
    }
    document.__glThemeObserver = themeObserver;

    window.setInterval(function () {
      if (!document.hidden) scheduleScan();
    }, 3000);

    document.addEventListener('visibilitychange', function () {
      if (!document.hidden) {
        scheduleScan();
        reportTheme();
      }
    });
  }

  function boot() {
    if (booted) return;
    booted = true;
    injectStyles();
    installClickBlocker();
    installObservers();
    reportTheme();
    scan(document.body);
  }

  window.__gl = {
    version: 2,
    scan: function () {
      scan(document.body);
    },
    focus: focusWithRetry,
    newChat: startNewChat,
    voice: startVoice,
    reportTheme: reportTheme
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot, { once: true });
  } else {
    boot();
  }
})();
