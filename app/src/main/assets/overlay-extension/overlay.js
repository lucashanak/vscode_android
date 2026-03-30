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
        return document.querySelector('.monaco-editor .inputarea') ||
               document.activeElement || document.body;
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
    // buttons bitmask: 1=left, 2=right, 4=middle
    function btnMask(button) { return button===0?1:button===1?4:button===2?2:0; }

    function dispatchPointer(type, button, pressed) {
        const t = document.elementFromPoint(cursorX, cursorY); if (!t) return;
        t.dispatchEvent(new PointerEvent(type, {
            clientX:cursorX, clientY:cursorY,
            button:button||0, buttons:pressed ? btnMask(button) : 0,
            bubbles:true, cancelable:true, composed:true, view:window,
            pointerId:1, pointerType:'mouse', isPrimary:true,
            width:1, height:1, pressure:pressed?0.5:0
        }));
    }
    function dispatchMouse(type, button, pressed) {
        const t = document.elementFromPoint(cursorX, cursorY); if (!t) return;
        t.dispatchEvent(new MouseEvent(type, {
            clientX:cursorX, clientY:cursorY,
            button:button||0, buttons:pressed ? btnMask(button) : 0,
            bubbles:true, cancelable:true, view:window
        }));
    }
    function clickAt(button) {
        const t = document.elementFromPoint(cursorX, cursorY);
        dispatchPointer('pointermove',0,false); dispatchMouse('mousemove',0,false);
        dispatchPointer('pointerdown',button,true); dispatchMouse('mousedown',button,true);
        dispatchPointer('pointerup',button,false); dispatchMouse('mouseup',button,false);
        if (button === 0) {
            dispatchMouse('click',0,false);
            // Focus the clicked element (synthetic events don't trigger native focus)
            if (t) {
                const focusable = t.closest('[tabindex],input,textarea,button,a,select') || t;
                try { focusable.focus(); } catch(e) {}
            }
        } else {
            // Non-primary buttons: browser uses 'auxclick', not 'click'
            if (t) t.dispatchEvent(new MouseEvent('auxclick', {
                clientX:cursorX, clientY:cursorY,
                button:button, buttons:0,
                bubbles:true, cancelable:true, view:window
            }));
            // Right-click: also dispatch contextmenu
            if (button === 2 && t) {
                t.dispatchEvent(new MouseEvent('contextmenu', {
                    clientX:cursorX, clientY:cursorY,
                    button:2, buttons:0,
                    bubbles:true, cancelable:true, view:window
                }));
            }
        }
    }
    function doubleClickAt() {
        clickAt(0); clickAt(0);
        const t = document.elementFromPoint(cursorX, cursorY);
        if (t) t.dispatchEvent(new MouseEvent('dblclick',{
            clientX:cursorX, clientY:cursorY, bubbles:true, view:window
        }));
    }
    function scrollAt(deltaY) {
        const t = document.elementFromPoint(cursorX, cursorY);
        if (t) t.dispatchEvent(new WheelEvent('wheel',{
            deltaY, deltaX:0, clientX:cursorX, clientY:cursorY,
            bubbles:true, cancelable:true, view:window
        }));
    }

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

    // ====================== PORT CONNECTION ======================
    function connect() {
        try {
            const port = browser.runtime.connectNative('browser');
            activePort = port;
            port.onMessage.addListener(msg => {
                switch (msg.type) {
                    case 'char':
                        insertChar(msg.char);
                        break;
                    case 'key':
                        dispatchSpecialKey(msg);
                        break;
                    case 'pointerMove':
                        cursorX = msg.x; cursorY = msg.y;
                        dispatchPointer('pointermove', 0);
                        dispatchMouse('mousemove', 0);
                        scheduleCursorCheck();
                        break;
                    case 'mouseDown':
                        cursorX = msg.x; cursorY = msg.y;
                        dispatchPointer('pointerdown', msg.button || 0, true);
                        dispatchMouse('mousedown', msg.button || 0, true);
                        break;
                    case 'mouseUp':
                        cursorX = msg.x; cursorY = msg.y;
                        dispatchPointer('pointerup', msg.button || 0, false);
                        dispatchMouse('mouseup', msg.button || 0, false);
                        break;
                    case 'click':
                        cursorX = msg.x; cursorY = msg.y;
                        clickAt(msg.button || 0);
                        break;
                    case 'doubleClick':
                        cursorX = msg.x; cursorY = msg.y;
                        doubleClickAt();
                        break;
                    case 'scroll':
                        cursorX = msg.x; cursorY = msg.y;
                        scrollAt(msg.deltaY);
                        break;
                    case 'overlayActive':
                        setInputModeNone(!!msg.active);
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
    connect();
})();
