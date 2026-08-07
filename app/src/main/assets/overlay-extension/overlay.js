(function () {
    'use strict';

    // =====================================================================
    // Minimal content script — receives commands from Android via Port
    // and dispatches keyboard/pointer events to VS Code's Monaco editor.
    // UI is in a native Android WebView (overlay-ui/overlay.html).
    // =====================================================================

    let cursorX = 0, cursorY = 0;
    let lastCursorType = 'default';
    let activePort = null;

    // ====================== TARGET ======================
    function getTarget() {
        // Use activeElement so keyboard input goes to whichever panel has focus
        // (editor, terminal, search box, etc.) — not hardcoded to editor textarea
        return document.activeElement || document.body;
    }

    // ====================== TEXT INSERTION ======================
    // For printable characters: ONLY InputEvent (no keydown).
    // Prevents double-typing — Monaco handles keydown AND InputEvent separately.
    function insertChar(ch) {
        const target = getTarget();
        if (target && target.focus) target.focus();
        if (target.tagName === 'TEXTAREA' || target.tagName === 'INPUT') {
            const start = target.selectionStart || 0;
            const end = target.selectionEnd || start;
            target.value = target.value.substring(0, start) + ch + target.value.substring(end);
            target.selectionStart = target.selectionEnd = start + ch.length;
        }
        target.dispatchEvent(new InputEvent('input', {
            inputType: 'insertText', data: ch,
            bubbles: true, isComposing: false
        }));
    }

    // ====================== SPECIAL KEY DISPATCH ======================
    // For non-printable keys and modified keys: ONLY keydown/keyup.
    const KEY_MAP = {
        Esc:  { key:'Escape',    code:'Escape',      kc:27 },
        Tab:  { key:'Tab',       code:'Tab',          kc:9  },
        Enter:{ key:'Enter',     code:'Enter',        kc:13 },
        Bksp: { key:'Backspace', code:'Backspace',    kc:8  },
        Del:  { key:'Delete',    code:'Delete',       kc:46 },
        Space:{ key:' ',         code:'Space',        kc:32 },
        Home: { key:'Home',      code:'Home',         kc:36 },
        End:  { key:'End',       code:'End',          kc:35 },
        PgUp: { key:'PageUp',    code:'PageUp',       kc:33 },
        PgDn: { key:'PageDown',  code:'PageDown',     kc:34 },
        Ins:  { key:'Insert',    code:'Insert',       kc:45 },
        Up:   { key:'ArrowUp',   code:'ArrowUp',      kc:38 },
        Down: { key:'ArrowDown', code:'ArrowDown',    kc:40 },
        Left: { key:'ArrowLeft', code:'ArrowLeft',    kc:37 },
        Right:{ key:'ArrowRight',code:'ArrowRight',   kc:39 },
    };
    for (let i = 1; i <= 12; i++) KEY_MAP['F'+i] = { key:'F'+i, code:'F'+i, kc:111+i };

    function dispatchSpecialKey(msg) {
        const target = getTarget();
        if (target && target.focus) target.focus();

        const mapped = KEY_MAP[msg.key];
        let key, code, kc;
        if (mapped) {
            key = mapped.key; code = mapped.code; kc = mapped.kc;
        } else if (msg.key.length === 1) {
            // Single character — generate proper code/keyCode for Monaco
            key = msg.key;
            const lo = msg.key.toLowerCase();
            const up = msg.key.toUpperCase();
            if (lo >= 'a' && lo <= 'z') {
                code = 'Key' + up;
                kc = up.charCodeAt(0); // 65-90
            } else if (lo >= '0' && lo <= '9') {
                code = 'Digit' + lo;
                kc = lo.charCodeAt(0); // 48-57
            } else {
                // Symbols: use charCode as keyCode
                code = msg.key;
                kc = msg.key.charCodeAt(0);
            }
        } else {
            key = msg.key; code = msg.key; kc = 0;
        }

        const opts = {
            key, code, keyCode: kc, which: kc,
            bubbles: true, cancelable: true,
            ctrlKey: !!msg.ctrl, altKey: !!msg.alt,
            shiftKey: !!msg.shift, metaKey: !!msg.meta,
        };
        target.dispatchEvent(new KeyboardEvent('keydown', opts));
        target.dispatchEvent(new KeyboardEvent('keyup', opts));
    }

    // ====================== POINTER / MOUSE ======================
    // Mouse events are now injected natively via GeckoView PanZoomController
    // (produces isTrusted:true events). No synthetic dispatch needed here.

    // ====================== CURSOR TYPE DETECTION ======================
    let cursorCheckRaf = 0;
    function scheduleCursorCheck() {
        if (cursorCheckRaf) return;
        // Defer to next frame so VS Code has time to process the pointer event
        // and update cursor styles before we read them
        cursorCheckRaf = requestAnimationFrame(() => {
            cursorCheckRaf = 0;
            const el = document.elementFromPoint(cursorX, cursorY);
            if (!el) return;
            // Walk up the DOM to find the effective cursor (some elements inherit)
            let cursor = 'default';
            let node = el;
            while (node && node !== document.documentElement) {
                const c = node.style.cursor || '';
                if (c && c !== 'auto' && c !== 'inherit') { cursor = c; break; }
                const computed = window.getComputedStyle(node).cursor;
                if (computed && computed !== 'auto') { cursor = computed; break; }
                node = node.parentElement;
            }
            // Normalize to categories
            let type = 'default';
            if (cursor === 'col-resize' || cursor === 'ew-resize' || cursor === 'w-resize' || cursor === 'e-resize') type = 'col-resize';
            else if (cursor === 'row-resize' || cursor === 'ns-resize' || cursor === 'n-resize' || cursor === 's-resize') type = 'row-resize';
            else if (cursor === 'text' || cursor === 'vertical-text') type = 'text';
            else if (cursor === 'pointer') type = 'pointer';
            if (type !== lastCursorType) {
                lastCursorType = type;
                if (activePort) {
                    try { activePort.postMessage({type: 'cursorType', cursor: type}); } catch(e) {}
                }
            }
        });
    }

    // ====================== IME SUPPRESSION ======================
    let imeObserver = null;

    function setInputModeNone(enable) {
        document.querySelectorAll('input, textarea, [contenteditable]').forEach(el => {
            if (enable) el.setAttribute('inputmode', 'none');
            else el.removeAttribute('inputmode');
        });
        if (enable && !imeObserver) {
            imeObserver = new MutationObserver(mutations => {
                for (const m of mutations) for (const node of m.addedNodes) {
                    if (node.nodeType !== 1) continue;
                    if (node.matches && node.matches('input,textarea,[contenteditable]'))
                        node.setAttribute('inputmode', 'none');
                    if (node.querySelectorAll)
                        node.querySelectorAll('input,textarea,[contenteditable]').forEach(
                            el => el.setAttribute('inputmode', 'none'));
                }
            });
            imeObserver.observe(document.body, { childList: true, subtree: true });
        } else if (!enable && imeObserver) {
            imeObserver.disconnect();
            imeObserver = null;
        }
    }

    // ====================== COLOR INVERT (sunlight readability) ======================
    // Invert whole page with a CSS filter. hue-rotate(180deg) keeps colors
    // roughly correct (blues stay blue-ish) while lightness is flipped.
    // Images/videos are un-inverted so they still look normal.
    function setColorInvert(enabled) {
        const id = 'vscode-sun-invert-style';
        let el = document.getElementById(id);
        if (!enabled) { if (el) el.remove(); return; }
        if (!el) {
            el = document.createElement('style');
            el.id = id;
            (document.head || document.documentElement).appendChild(el);
        }
        el.textContent = `
            html { filter: invert(1) hue-rotate(180deg) !important; background: #fff !important; }
            img, video, canvas, svg[class*="icon"], [style*="background-image"] {
                filter: invert(1) hue-rotate(180deg) !important;
            }
        `;
    }

    // ====================== KEEPALIVE (prevent idle disconnect) ======================
    // VSCode tunnel disconnects after prolonged inactivity. Dispatching
    // synthetic mousemove events at a configurable interval keeps the
    // user presence detection fresh without affecting focus or selection.
    let keepaliveTimer = null;
    function setKeepalive(seconds) {
        if (keepaliveTimer) { clearInterval(keepaliveTimer); keepaliveTimer = null; }
        if (!seconds || seconds <= 0) return;
        keepaliveTimer = setInterval(() => {
            try {
                // Synthetic mousemove at current cursor pos — not trusted but
                // sufficient to reset idle timers in vscode.dev's activity tracker
                document.dispatchEvent(new MouseEvent('mousemove', {
                    clientX: cursorX, clientY: cursorY,
                    bubbles: true, cancelable: true
                }));
            } catch (e) {}
        }, seconds * 1000);
    }

    // ====================== DIAGNOSTIC SNAPSHOT ======================
    // The native side can see that a load happened but not what the page ended up showing, which is
    // exactly the gap when vscode.dev renders blank or sits on "Reconnecting…" — see
    // docs/vscode-cache.md. This runs inside the page, so it can answer that directly.
    //
    // Everything here is best-effort by design: it inspects a third-party DOM we do not control, so
    // a selector that stops matching must yield a null field, never an exception that takes the rest
    // of the overlay down with it.

    // Places VS Code surfaces connection trouble. Ordered cheapest/most specific first; all optional.
    const DIAG_TEXT_SELECTORS = [
        '.monaco-dialog-box .dialog-message-text',      // "Cannot reconnect. Please reload the window."
        '.notification-list-item-message',              // reconnection toasts
        '.monaco-workbench .progress-container',        // indeterminate progress while reconnecting
        '.statusbar .remote-indicator',                 // remote/tunnel status ("Reconnecting…")
        '#status\\.host',                               // id of that same indicator in older builds
    ];

    function safe(fn) {
        // Every field is independently guarded: one broken probe must not void the whole snapshot.
        try { return fn(); } catch (e) { return null; }
    }

    // Script errors from the page. The device shows a document that loads cleanly — 200s,
    // readyState=complete, no load error — yet never mounts .monaco-workbench, and a healthy load
    // mounts it within 3s. That pattern means the bootstrap threw or is stuck, and neither is
    // visible from the native side. Capped hard: a broken page can throw in a loop, and this rides
    // the same channel as the rest of the diagnostics.
    const pageErrors = [];
    function noteError(what) {
        if (pageErrors.length >= 8) return;
        const s = String(what).replace(/\s+/g, ' ').slice(0, 300);
        if (!pageErrors.includes(s)) pageErrors.push(s);
    }
    try {
        window.addEventListener('error', e => {
            noteError(e.message + (e.filename ? ' @' + e.filename.split('/').pop() + ':' + e.lineno : ''));
        }, true);
        window.addEventListener('unhandledrejection', e => {
            noteError('unhandledrejection: ' + (e.reason && (e.reason.message || e.reason)));
        }, true);
    } catch (e) { /* listeners are best-effort; never break the overlay over diagnostics */ }

    // Everything measured about the page has to run in the page's own world, not the content
    // script's. Learned the hard way: a content-script fetch to auth.vscode.dev rejected with
    // TypeError in 0-3ms on every capture, which read like the page being blocked — but the
    // extension declares no host permissions, so the request never left the sandbox. The number was
    // an artefact of the probe. Anything read from an isolated world is suspect for the same reason,
    // resource timing included, so the probes live here instead and report what VS Code itself sees.
    //
    // Bridged over CustomEvents with JSON strings: structured clone across the world boundary is
    // the one thing that reliably survives.
    const PAGE_WORLD_PROBE = "(function(){try{" +
        // console.warn/error, which an isolated world cannot observe at all
        "var send=function(kind,args){try{var s=kind+': '+Array.prototype.map.call(args,function(a){" +
        "  try{return (a&&a.message)?a.message:(typeof a==='object'?JSON.stringify(a).slice(0,200):String(a));}" +
        "  catch(e){return '?';}}).join(' ');" +
        "  window.dispatchEvent(new CustomEvent('__vsct_console',{detail:s.slice(0,260)}));}catch(e){}};" +
        "['error','warn'].forEach(function(k){var o=console[k];console[k]=function(){" +
        "  send(k,arguments); try{return o.apply(console,arguments);}catch(e){}};});" +
        // on request, measure from in here and answer once
        "window.addEventListener('__vsct_probe_req',function(){" +
        "  var out={pw:true,hosts:{},res:0,auth:null};" +
        "  try{var es=performance.getEntriesByType('resource');out.res=es.length;" +
        "    for(var i=0;i<es.length;i++){try{var h=new URL(es[i].name).host;out.hosts[h]=(out.hosts[h]||0)+1;}catch(e){}}" +
        "  }catch(e){}" +
        "  var done=function(){try{window.dispatchEvent(new CustomEvent('__vsct_probe_res'," +
        "    {detail:JSON.stringify(out)}));}catch(e){}};" +
        "  var t0=Date.now(),fin=false,f=function(){if(!fin){fin=true;done();}};" +
        "  setTimeout(f,4500);" +
        "  try{var c=new AbortController();setTimeout(function(){try{c.abort();}catch(e){}},4000);" +
        "    fetch('https://auth.vscode.dev',{method:'POST',credentials:'include',signal:c.signal})" +
        "      .then(function(r){return r.text().then(function(b){return {s:r.status,n:b.length};}," +
        "                                            function(){return {s:r.status,n:-1};});})" +
        "      .then(function(r){out.auth='ok:'+r.s+' len='+r.n+' '+(Date.now()-t0)+'ms';}," +
        "            function(e){out.auth=((e&&e.name==='AbortError')?'timeout':'error:'+(e&&e.name))+" +
        "                                 ' '+(Date.now()-t0)+'ms';})" +
        "      .then(f);" +
        "  }catch(e){out.auth='threw:'+(e&&e.name);f();}" +
        "},true);" +
        "}catch(e){}})();";

    // Latest page-world answer, or null if the injection never reported — which is itself worth
    // knowing, since it would mean these numbers are simply unavailable rather than zero.
    let pageWorld = null;
    try {
        window.addEventListener('__vsct_probe_res', e => {
            try { pageWorld = JSON.parse(String(e.detail)); } catch (_) { pageWorld = null; }
        }, true);
    } catch (e) { /* best-effort */ }

    function requestPageWorldProbe() {
        try { window.dispatchEvent(new CustomEvent('__vsct_probe_req')); } catch (e) {}
    }

    // Console output from the page itself.
    //
    // This was the blind spot that made "errors=[]" misleading: a content script runs in an
    // isolated world and cannot see the page's console, and VS Code reports plenty of failures
    // that way — including the handler that wipes stored credentials, which only does
    // console.error. So a real failure could be happening with the snapshot reporting none.
    //
    // Only warn/error are taken, each clamped, few kept: this is a third-party page and its error
    // text is not ours to hoard. It does mean the log can contain page error strings.
    try {
        window.addEventListener('__vsct_console', e => {
            try { noteError('console: ' + String(e.detail).slice(0, 260)); } catch (_) {}
        }, true);
        const inject = document.createElement('script');
        inject.textContent = PAGE_WORLD_PROBE;
        (document.head || document.documentElement).appendChild(inject);
        inject.remove();
    } catch (e) { /* page-world injection is best-effort too */ }

    function collectDiagText() {
        const found = [];
        for (const sel of DIAG_TEXT_SELECTORS) {
            const hit = safe(() => {
                const el = document.querySelector(sel);
                if (!el) return null;
                const txt = (el.textContent || '').trim().replace(/\s+/g, ' ');
                return txt ? {sel: sel, text: txt.slice(0, 200)} : null;
            });
            if (hit) found.push(hit);
        }
        return found;
    }

    function buildDiag(reason) {
        const wb = safe(() => document.querySelector('.monaco-workbench'));
        const diag = {
            type: 'diag',
            reason: reason,
            readyState: safe(() => document.readyState),
            href: safe(() => location.href),
            title: safe(() => document.title),
            online: safe(() => navigator.onLine),
            visibility: safe(() => document.visibilityState),
            // Present-but-empty is the signature of the blank-workbench case, so record both.
            workbench: !!wb,
            workbenchChildren: wb ? safe(() => wb.childElementCount) : null,
            bodyChildren: safe(() => document.body ? document.body.childElementCount : null),
            swController: safe(() => !!(navigator.serviceWorker && navigator.serviceWorker.controller)),
            texts: safe(collectDiagText) || [],
            errors: pageErrors.slice(),
            // displayDensityOverride is applied natively for the zoom setting, and is a prime
            // suspect for a bootstrap that never lays out. It is invisible from the native side, so
            // report what the page actually sees.
            dpr: safe(() => window.devicePixelRatio),
            inner: safe(() => window.innerWidth + 'x' + window.innerHeight),
            // Which body children exist. A healthy load ends with DIV.vs-dark — the workbench
            // container — after the DIV.loading splash. The device reports exactly one child fewer,
            // so naming them says which stage it stopped at rather than just counting.
            bodyKids: safe(() => Array.prototype.slice.call(document.body.children, 0, 12)
                .map(c => c.tagName + '.' + String(c.className || '').split(' ')[0])),
            // What is actually on screen. Everything so far probed VS Code's own selectors, so a
            // sign-in prompt, a consent wall or an unsupported-browser notice would have been
            // invisible to all of it.
            text: safe(() => (document.body.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 220)),
            // Per-host completed-request counts and the auth reachability check both come from
            // the page's own world (see PAGE_WORLD_PROBE) — read from here they would be
            // meaningless. `pw` says whether that answer actually arrived.
            pw: null,
            hosts: null,
            res: null,
            auth: null,
            caches: null,
            idb: null,
            idbNames: null,
        };
        // Both probes are async and either may be unavailable, reject, or — the case that matters —
        // never settle at all. The whole thing is capped so a stuck page still gets a snapshot out.
        return new Promise(resolve => {
            let settled = false;
            let pending = 3;
            const finish = () => { if (!settled) { settled = true; resolve(diag); } };
            const part = () => { if (--pending <= 0) finish(); };
            setTimeout(finish, 5000);

            try {
                if (!window.caches || !caches.keys) part();
                else caches.keys().then(keys => { diag.caches = keys; part(); }, part);
            } catch (e) { part(); }

            // Can this origin open IndexedDB at all?
            //
            // This is the decisive probe. The bootstrap awaits `validateDbIsOpen` and never mounts
            // the workbench, with no error anywhere — and microsoft/vscode#145647 ("Firefox: blank
            // page opening vscode.dev", open since 2022) has a VS Code maintainer concluding
            // "the overall issue is this where we fail to open IndexedDB", with one reporter
            // tracing it to enhanced tracking protection. An open request that neither succeeds nor
            // errors explains every observation here, and it is invisible from outside.
            //
            // 'timeout' is therefore the interesting answer, not a failure of the probe.
            try {
                if (!window.indexedDB) { diag.idb = 'unavailable'; part(); }
                else {
                    const NAME = '__vscodetunnel_idb_probe__';
                    const req = indexedDB.open(NAME, 1);
                    const t = setTimeout(() => { if (diag.idb === null) { diag.idb = 'timeout'; part(); } }, 3000);
                    const settle = (v) => {
                        if (diag.idb !== null) return;
                        clearTimeout(t); diag.idb = v; part();
                    };
                    req.onsuccess = () => {
                        try { req.result.close(); indexedDB.deleteDatabase(NAME); } catch (e) {}
                        settle('ok');
                    };
                    req.onerror = () => settle('error:' + (req.error && req.error.name));
                    req.onblocked = () => settle('blocked');
                    // Separate from the open probe: enumerating tells us whether VS Code's own
                    // databases were ever created (msal.db, vscode-web-db, vscode-web-state-db-*).
                    if (indexedDB.databases) {
                        indexedDB.databases().then(
                            dbs => { diag.idbNames = dbs.map(d => d.name); }, () => {});
                    }
                }
            } catch (e) { diag.idb = 'threw:' + (e && e.name); part(); }

            // Fold in the page-world answer once it lands.
            requestPageWorldProbe();
            let waited = 0;
            const poll = setInterval(() => {
                waited += 250;
                if (pageWorld || waited >= 4800) {
                    clearInterval(poll);
                    if (pageWorld) {
                        diag.pw = true;
                        diag.hosts = pageWorld.hosts;
                        diag.res = pageWorld.res;
                        diag.auth = pageWorld.auth;
                    } else {
                        diag.pw = false;   // injection never answered; the fields are unknown, not zero
                    }
                    part();
                }
            }, 250);
        });
    }

    let lastAutoDiag = 0;
    function sendDiag(reason) {
        buildDiag(reason).then(diag => {
            if (!activePort) return;
            try { activePort.postMessage(diag); } catch (e) {}
        }, () => {});
    }

    // ====================== PORT CONNECTION ======================
    function connect() {
        try {
            const port = browser.runtime.connectNative('browser');
            activePort = port;
            port.onMessage.addListener(msg => {
                switch (msg.type) {
                    // Keyboard input — still via content script (no native API for this)
                    case 'char':
                        insertChar(msg.char);
                        break;
                    case 'key':
                        dispatchSpecialKey(msg);
                        break;
                    case 'overlayActive':
                        setInputModeNone(!!msg.active);
                        break;
                    case 'keepalive':
                        setKeepalive(msg.seconds);
                        break;
                    case 'colorInvert':
                        setColorInvert(!!msg.enabled);
                        break;
                    case 'diagRequest':
                        sendDiag(msg.reason || 'request');
                        break;
                }
            });

            // One shot per connection, after a settle delay so the workbench has had a chance to
            // render — a snapshot taken at document_idle would say "blank" for every healthy load
            // too. Throttled because onDisconnect retries every second: a flapping port must not
            // turn a hung page into a log spammer. No interval anywhere; on-demand covers the rest.
            const now = Date.now();
            if (now - lastAutoDiag > 30000) {
                lastAutoDiag = now;
                setTimeout(() => sendDiag('connect'), 3000);
            }
            port.onDisconnect.addListener(() => {
                // Reconnect after a delay (page navigation, etc.)
                setTimeout(connect, 1000);
            });
        } catch (e) {
            // Extension API not available yet, retry
            setTimeout(connect, 500);
        }
    }

    // ====================== INIT ======================
    cursorX = window.innerWidth / 2;
    cursorY = window.innerHeight / 2;

    // Cursor type detection: listen for real (trusted) mousemove events
    // from native PanZoomController injection
    document.addEventListener('mousemove', e => {
        cursorX = e.clientX; cursorY = e.clientY;
        scheduleCursorCheck();
    }, {passive: true});

    connect();
})();
