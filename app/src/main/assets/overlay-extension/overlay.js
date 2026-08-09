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

    // Reaching into the page without injecting a script.
    //
    // The previous approach appended an inline <script> to run in the page world. That can never
    // work here: vscode.dev serves `script-src 'self' 'sha256-...' 'unsafe-eval' <cdn hosts>` with
    // no 'unsafe-inline', so the element is refused by CSP — which is exactly what pw=false was
    // reporting. Gecko gives content scripts `wrappedJSObject` for this instead: it is the page's
    // own object graph, no element, no CSP involvement.
    //
    // The probe fetch now runs with extension privileges (the manifest grants auth.vscode.dev),
    // which answers the question that matters — can this device reach the host at all. The page's
    // CSP is not the obstacle for the page itself: connect-src explicitly lists auth.vscode.dev.
    let pageWorld = null;

    function pageConsoleHook() {
        // console.warn/error happen in the page's world, invisible to an isolated content script.
        // exportFunction is what lets a content-script function be called from page code.
        try {
            if (typeof exportFunction !== 'function' || !window.wrappedJSObject) return false;
            const w = window.wrappedJSObject;
            // early.js hooks the same console at document_start and its capture is strictly better —
            // it is in place before the page's own scripts run. Wrapping again would only duplicate
            // every message, so defer to it and say so in the report.
            try { if (sessionStorage.getItem('__vsct_conhook') === '1') return 'early'; } catch (_) {}
            ['error', 'warn'].forEach(kind => {
                const original = w.console[kind];
                exportFunction(function () {
                    try {
                        const parts = [];
                        for (let i = 0; i < arguments.length && i < 4; i++) {
                            const a = arguments[i];
                            parts.push(a && a.message ? String(a.message) : String(a));
                        }
                        noteError('console.' + kind + ': ' + parts.join(' '));
                    } catch (e) {}
                    try { return original.apply(w.console, arguments); } catch (e) {}
                }, w.console, { defineAs: kind });
            });
            return true;
        } catch (e) { return false; }
    }
    const consoleHooked = pageConsoleHook();

    function requestPageWorldProbe() {
        // Resource timing, read through the page's own performance object.
        const out = { pw: true, hooked: consoleHooked, hosts: {}, res: 0, auth: null };
        try {
            const perf = (window.wrappedJSObject && window.wrappedJSObject.performance) || performance;
            const es = perf.getEntriesByType('resource');
            out.res = es.length;
            for (let i = 0; i < es.length; i++) {
                try { const h = new URL(es[i].name).host; out.hosts[h] = (out.hosts[h] || 0) + 1; }
                catch (e) {}
            }
            // Which resources, not just how many per host.
            //
            // Every account of this bug so far has asserted that the workbench bundle downloads and
            // then fails to run, on the strength of a count: three entries, all on the CDN. The
            // count cannot support that. It never showed the bundle was one of the three, and a
            // fetch that failed can leave an entry behind too. So name them, and say whether each
            // one actually finished — a zero responseEnd is a request that never settled.
            //
            // Sizes are deliberately absent: this CDN sends no Timing-Allow-Origin, so every
            // cross-origin size field reads 0 and would only look like evidence of truncation.
            out.resList = [];
            for (let i = 0; i < es.length && i < 8; i++) {
                try {
                    const e = es[i];
                    const path = new URL(e.name).pathname;
                    out.resList.push(
                        path.slice(path.lastIndexOf('/') + 1).slice(0, 34) +
                        '|' + (e.initiatorType || '?') +
                        '|' + Math.round(e.duration) + 'ms' +
                        (e.responseEnd ? '' : '|UNFINISHED'));
                } catch (e2) {}
            }
        } catch (e) { out.res = -1; }

        const t0 = Date.now();
        const ctl = new AbortController();
        const abort = setTimeout(() => { try { ctl.abort(); } catch (e) {} }, 2500);
        return fetch('https://auth.vscode.dev', {
            method: 'POST', credentials: 'include', signal: ctl.signal
        }).then(
            r => r.text().then(b => ({ s: r.status, n: b.length }), () => ({ s: r.status, n: -1 }))
        ).then(
            r => { out.auth = 'ok:' + r.s + ' len=' + r.n + ' ' + (Date.now() - t0) + 'ms'; },
            e => { out.auth = ((e && e.name === 'AbortError') ? 'timeout' : 'error:' + (e && e.name)) +
                              ' ' + (Date.now() - t0) + 'ms'; }
        ).then(() => { clearTimeout(abort); pageWorld = out; return out; });
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
    // The hook itself is installed by pageConsoleHook() above, via wrappedJSObject.

    // Anything early.js caught before this script attached.
    //
    // This is where the real evidence should be: the page's module bootstrap runs while the
    // document is still parsing, so a failure there happens long before document_idle. Draining
    // rather than peeking, so a stale entry from a previous load cannot be mistaken for a fresh one.
    // Drained at every snapshot, not once at load: early.js keeps its console hook installed for
    // the life of the page, so a message logged after this script attached would otherwise sit in
    // storage unread. The count accumulates across drains.
    let earlyCount = null;
    function drainEarly() {
        try {
            const raw = sessionStorage.getItem('__vsct_early');
            if (raw !== null) {
                const list = JSON.parse(raw);
                if (earlyCount === null || earlyCount < 0) earlyCount = 0;
                earlyCount += list.length;
                list.forEach(x => noteError(String(x)));
                sessionStorage.removeItem('__vsct_early');
            } else if (earlyCount === null) {
                earlyCount = -1;   // early.js never ran, or storage is unavailable
            }
        } catch (e) { if (earlyCount === null) earlyCount = -2; }
    }
    drainEarly();

    // Content Security Policy violations.
    //
    // These never reach the page's `console` object — the browser logs them itself — so the console
    // hook is blind to them and `errors=[]` cannot rule them out. Given the page enforces
    // `require-trusted-types-for 'script'` and loads its workbench bundle dynamically, a blocked
    // script creation would stop the bootstrap exactly where it stops: after the three initial
    // downloads, silently.
    try {
        document.addEventListener('securitypolicyviolation', e => {
            try {
                noteError('csp: ' + e.violatedDirective + ' blocked=' +
                          String(e.blockedURI || '').slice(0, 80) +
                          (e.sample ? ' sample=' + String(e.sample).slice(0, 60) : ''));
            } catch (_) {}
        }, true);
    } catch (e) { /* best-effort */ }

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
        // Before anything is read, so console output early.js captured since the last snapshot
        // lands in this one's `errors` rather than the next.
        drainEarly();
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
            // the page's own world (see requestPageWorldProbe) — read from here they would be
            // meaningless. `pw` says whether that answer actually arrived.
            pw: null,
            hooked: null,
            early: safe(() => earlyCount),
            tt: safe(() => typeof window.trustedTypes),
            globals: safe(() => {
                const w = (window.wrappedJSObject || window);
                const out = [];
                for (const k of ['define', '_VSCODE_WEB_BOOTSTRAP_', '_VSCODE_FILE_ROOT',
                                 '_VSCODE_NLS_MESSAGES', '_VSCODE_WEB_PACKAGE_TTP',
                                 'MonacoPerformanceMarks', 'MonacoEnvironment']) {
                    let t = 'missing';
                    try { t = typeof w[k]; } catch (e) { t = 'err'; }
                    if (t !== 'undefined') out.push(k);
                }
                return out;
            }),
            hosts: null,
            res: null,
            resList: null,
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
            setTimeout(finish, 4500);

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

            // Fold in the probe result. It is a promise now, not an event, so there is no
            // cross-world handshake left to mistime — the previous two attempts failed on exactly
            // that (an unreachable poll threshold, then a CSP-refused script element).
            {
                let folded = false;
                const fold = () => {
                    if (folded) return;
                    folded = true;
                    if (pageWorld) {
                        diag.pw = true;
                        diag.hooked = pageWorld.hooked;
                        diag.hosts = pageWorld.hosts;
                        diag.res = pageWorld.res;
                        diag.resList = pageWorld.resList;
                        diag.auth = pageWorld.auth;
                    } else {
                        diag.pw = false;   // never produced a result: unknown, not zero
                    }
                    part();
                };
                setTimeout(fold, 3500);
                try { requestPageWorldProbe().then(fold, fold); } catch (e) { fold(); }
            }
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
