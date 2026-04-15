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
                }
            });
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
