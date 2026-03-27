(function () {
    'use strict';

    if (document.getElementById('vsc-overlay')) return;

    // ========================================================================
    // STATE
    // ========================================================================
    const state = {
        modifiers: { ctrl: false, alt: false, shift: false, meta: false },
        cursor: { x: window.innerWidth / 2, y: window.innerHeight / 2 },
        touchpad: { tracking: false, lastX: 0, lastY: 0, fingers: 0, scrollLastY: 0 },
        overlayVisible: false,
        sensitivity: 1.5,
        scrollSensitivity: 3,
        tapStart: 0,
        tapTimeout: null,
        lastTap: 0
    };

    // ========================================================================
    // KEY MAP
    // ========================================================================
    const KEYS = {
        Esc:       { key: 'Escape',    code: 'Escape',       keyCode: 27  },
        Tab:       { key: 'Tab',       code: 'Tab',          keyCode: 9   },
        Enter:     { key: 'Enter',     code: 'Enter',        keyCode: 13  },
        Bksp:      { key: 'Backspace', code: 'Backspace',    keyCode: 8   },
        Del:       { key: 'Delete',    code: 'Delete',       keyCode: 46  },
        Space:     { key: ' ',         code: 'Space',        keyCode: 32  },
        Home:      { key: 'Home',      code: 'Home',         keyCode: 36  },
        End:       { key: 'End',       code: 'End',          keyCode: 35  },
        PgUp:      { key: 'PageUp',    code: 'PageUp',       keyCode: 33  },
        PgDn:      { key: 'PageDown',  code: 'PageDown',     keyCode: 34  },
        Ins:       { key: 'Insert',    code: 'Insert',       keyCode: 45  },
        Up:        { key: 'ArrowUp',   code: 'ArrowUp',      keyCode: 38  },
        Down:      { key: 'ArrowDown', code: 'ArrowDown',    keyCode: 40  },
        Left:      { key: 'ArrowLeft', code: 'ArrowLeft',    keyCode: 37  },
        Right:     { key: 'ArrowRight',code: 'ArrowRight',   keyCode: 39  },
        '`':       { key: '`',         code: 'Backquote',    keyCode: 192 },
        '~':       { key: '~',         code: 'Backquote',    keyCode: 192, shift: true },
        '|':       { key: '|',         code: 'Backslash',    keyCode: 220, shift: true },
        '\\':      { key: '\\',        code: 'Backslash',    keyCode: 220 },
        '[':       { key: '[',         code: 'BracketLeft',  keyCode: 219 },
        ']':       { key: ']',         code: 'BracketRight', keyCode: 221 },
        '{':       { key: '{',         code: 'BracketLeft',  keyCode: 219, shift: true },
        '}':       { key: '}',         code: 'BracketRight', keyCode: 221, shift: true },
        '(':       { key: '(',         code: 'Digit9',       keyCode: 57,  shift: true },
        ')':       { key: ')',         code: 'Digit0',       keyCode: 48,  shift: true },
        ';':       { key: ';',         code: 'Semicolon',    keyCode: 186 },
        ':':       { key: ':',         code: 'Semicolon',    keyCode: 186, shift: true },
        "'":       { key: "'",         code: 'Quote',        keyCode: 222 },
        '"':       { key: '"',         code: 'Quote',        keyCode: 222, shift: true },
        ',':       { key: ',',         code: 'Comma',        keyCode: 188 },
        '.':       { key: '.',         code: 'Period',       keyCode: 190 },
        '/':       { key: '/',         code: 'Slash',        keyCode: 191 },
        '?':       { key: '?',         code: 'Slash',        keyCode: 191, shift: true },
        '-':       { key: '-',         code: 'Minus',        keyCode: 189 },
        '_':       { key: '_',         code: 'Minus',        keyCode: 189, shift: true },
        '=':       { key: '=',         code: 'Equal',        keyCode: 187 },
        '+':       { key: '+',         code: 'Equal',        keyCode: 187, shift: true },
        '&':       { key: '&',         code: 'Digit7',       keyCode: 55,  shift: true },
        '*':       { key: '*',         code: 'Digit8',       keyCode: 56,  shift: true },
        '#':       { key: '#',         code: 'Digit3',       keyCode: 51,  shift: true },
        '@':       { key: '@',         code: 'Digit2',       keyCode: 50,  shift: true },
        '!':       { key: '!',         code: 'Digit1',       keyCode: 49,  shift: true },
        '%':       { key: '%',         code: 'Digit5',       keyCode: 53,  shift: true },
        '^':       { key: '^',         code: 'Digit6',       keyCode: 54,  shift: true },
        '$':       { key: '$',         code: 'Digit4',       keyCode: 52,  shift: true },
        '<':       { key: '<',         code: 'Comma',        keyCode: 188, shift: true },
        '>':       { key: '>', code: 'Period',       keyCode: 190, shift: true },
    };
    for (let i = 1; i <= 12; i++) KEYS['F' + i] = { key: 'F' + i, code: 'F' + i, keyCode: 111 + i };
    for (let i = 0; i <= 9; i++) KEYS[String(i)] = { key: String(i), code: 'Digit' + i, keyCode: 48 + i };

    // ========================================================================
    // KEY DISPATCH
    // ========================================================================
    function getTarget() {
        return document.querySelector('.monaco-editor .inputarea') ||
               document.activeElement || document.body;
    }

    function dispatchKey(keyDef) {
        const target = getTarget();
        const opts = {
            key: keyDef.key, code: keyDef.code,
            keyCode: keyDef.keyCode, which: keyDef.keyCode,
            bubbles: true, cancelable: true,
            ctrlKey: state.modifiers.ctrl, altKey: state.modifiers.alt,
            shiftKey: state.modifiers.shift || (keyDef.shift || false),
            metaKey: state.modifiers.meta,
        };
        target.dispatchEvent(new KeyboardEvent('keydown', opts));
        if (keyDef.key.length === 1 && !opts.ctrlKey && !opts.altKey && !opts.metaKey) {
            document.execCommand('insertText', false, keyDef.key);
        }
        target.dispatchEvent(new KeyboardEvent('keyup', opts));
        resetModifiers();
    }

    function dispatchCharKey(char) {
        const upper = char.toUpperCase(), lower = char.toLowerCase();
        dispatchKey({ key: char, code: 'Key' + upper, keyCode: upper.charCodeAt(0), shift: char === upper && char !== lower });
    }

    function resetModifiers() {
        state.modifiers.ctrl = state.modifiers.alt = state.modifiers.shift = state.modifiers.meta = false;
        document.querySelectorAll('.vsc-mod-btn').forEach(btn => btn.classList.toggle('vsc-active', false));
        const kp = document.querySelector('.vsc-keyboard-panel');
        if (kp) kp.classList.remove('vsc-shifted');
    }

    function toggleModifier(mod) {
        state.modifiers[mod] = !state.modifiers[mod];
        document.querySelectorAll('.vsc-mod-btn').forEach(btn => btn.classList.toggle('vsc-active', state.modifiers[btn.dataset.mod]));
    }

    // ========================================================================
    // POINTER / MOUSE DISPATCH
    // ========================================================================
    let pointerId = 1;

    function dispatchPointer(type, button, extra) {
        const target = document.elementFromPoint(state.cursor.x, state.cursor.y);
        if (!target) return;
        target.dispatchEvent(new PointerEvent(type, {
            clientX: state.cursor.x, clientY: state.cursor.y,
            screenX: state.cursor.x, screenY: state.cursor.y,
            button: button || 0, buttons: button === 2 ? 2 : (button === 0 ? 1 : 0),
            bubbles: true, cancelable: true, composed: true, view: window,
            pointerId, pointerType: 'mouse', isPrimary: true,
            width: 1, height: 1, pressure: type === 'pointerdown' ? 0.5 : 0,
            ...extra
        }));
    }

    function dispatchMouse(type, button) {
        const target = document.elementFromPoint(state.cursor.x, state.cursor.y);
        if (!target) return;
        target.dispatchEvent(new MouseEvent(type, {
            clientX: state.cursor.x, clientY: state.cursor.y,
            button: button || 0, buttons: button === 2 ? 2 : (button === 0 ? 1 : 0),
            bubbles: true, cancelable: true, view: window
        }));
    }

    function clickAt(button) {
        dispatchPointer('pointermove', 0); dispatchMouse('mousemove', 0);
        dispatchPointer('pointerdown', button); dispatchMouse('mousedown', button);
        dispatchPointer('pointerup', button); dispatchMouse('mouseup', button);
        dispatchMouse('click', button);
    }

    function doubleClickAt() {
        clickAt(0); clickAt(0);
        const t = document.elementFromPoint(state.cursor.x, state.cursor.y);
        if (t) t.dispatchEvent(new MouseEvent('dblclick', {
            clientX: state.cursor.x, clientY: state.cursor.y, bubbles: true, view: window
        }));
    }

    function scrollAt(deltaY) {
        const t = document.elementFromPoint(state.cursor.x, state.cursor.y);
        if (t) t.dispatchEvent(new WheelEvent('wheel', {
            deltaY, deltaX: 0, clientX: state.cursor.x, clientY: state.cursor.y,
            bubbles: true, cancelable: true, view: window
        }));
    }

    // ========================================================================
    // BUILD OVERLAY
    // ========================================================================
    function buildOverlay() {
        const overlay = document.createElement('div');
        overlay.id = 'vsc-overlay';
        overlay.classList.add('vsc-collapsed'); // hidden by default

        overlay.addEventListener('touchstart', e => e.preventDefault(), { passive: false });
        overlay.addEventListener('mousedown', e => e.preventDefault());

        overlay.innerHTML = `
            <div class="vsc-toolbar">
                <button class="vsc-tool-btn vsc-tab-btn" data-tab="touchpad" id="vsc-tp-toggle">TP</button>
                <div class="vsc-spacer"></div>
                <button class="vsc-tool-btn" id="vsc-hide-btn">Hide</button>
            </div>

            <div class="vsc-panels-container">
                <div class="vsc-panel vsc-keyboard-panel vsc-active-panel">
                    <!-- F-keys -->
                    <div class="vsc-row">
                        <button class="vsc-key vsc-fkey" data-key="Esc">Esc</button>
                        <button class="vsc-key vsc-fkey" data-key="F1">F1</button>
                        <button class="vsc-key vsc-fkey" data-key="F2">F2</button>
                        <button class="vsc-key vsc-fkey" data-key="F3">F3</button>
                        <button class="vsc-key vsc-fkey" data-key="F4">F4</button>
                        <button class="vsc-key vsc-fkey" data-key="F5">F5</button>
                        <button class="vsc-key vsc-fkey" data-key="F6">F6</button>
                        <button class="vsc-key vsc-fkey" data-key="F7">F7</button>
                        <button class="vsc-key vsc-fkey" data-key="F8">F8</button>
                        <button class="vsc-key vsc-fkey" data-key="F9">F9</button>
                        <button class="vsc-key vsc-fkey" data-key="F10">F10</button>
                        <button class="vsc-key vsc-fkey" data-key="F11">F11</button>
                        <button class="vsc-key vsc-fkey" data-key="F12">F12</button>
                    </div>
                    <!-- Numbers + symbols -->
                    <div class="vsc-row">
                        <button class="vsc-key" data-key="\`">\`</button>
                        <button class="vsc-key" data-key="1">1</button>
                        <button class="vsc-key" data-key="2">2</button>
                        <button class="vsc-key" data-key="3">3</button>
                        <button class="vsc-key" data-key="4">4</button>
                        <button class="vsc-key" data-key="5">5</button>
                        <button class="vsc-key" data-key="6">6</button>
                        <button class="vsc-key" data-key="7">7</button>
                        <button class="vsc-key" data-key="8">8</button>
                        <button class="vsc-key" data-key="9">9</button>
                        <button class="vsc-key" data-key="0">0</button>
                        <button class="vsc-key" data-key="-">-</button>
                        <button class="vsc-key" data-key="=">=</button>
                    </div>
                    <!-- QWERTY -->
                    <div class="vsc-row">
                        <button class="vsc-key" data-key="Tab">Tab</button>
                        <button class="vsc-key vsc-char-key" data-char="q">q</button>
                        <button class="vsc-key vsc-char-key" data-char="w">w</button>
                        <button class="vsc-key vsc-char-key" data-char="e">e</button>
                        <button class="vsc-key vsc-char-key" data-char="r">r</button>
                        <button class="vsc-key vsc-char-key" data-char="t">t</button>
                        <button class="vsc-key vsc-char-key" data-char="y">y</button>
                        <button class="vsc-key vsc-char-key" data-char="u">u</button>
                        <button class="vsc-key vsc-char-key" data-char="i">i</button>
                        <button class="vsc-key vsc-char-key" data-char="o">o</button>
                        <button class="vsc-key vsc-char-key" data-char="p">p</button>
                        <button class="vsc-key" data-key="[">[</button>
                        <button class="vsc-key" data-key="]">]</button>
                        <button class="vsc-key" data-key="\\">\\</button>
                    </div>
                    <!-- Home row -->
                    <div class="vsc-row">
                        <button class="vsc-key vsc-mod-btn vsc-wide-key" data-mod="ctrl">Ctrl</button>
                        <button class="vsc-key vsc-char-key" data-char="a">a</button>
                        <button class="vsc-key vsc-char-key" data-char="s">s</button>
                        <button class="vsc-key vsc-char-key" data-char="d">d</button>
                        <button class="vsc-key vsc-char-key" data-char="f">f</button>
                        <button class="vsc-key vsc-char-key" data-char="g">g</button>
                        <button class="vsc-key vsc-char-key" data-char="h">h</button>
                        <button class="vsc-key vsc-char-key" data-char="j">j</button>
                        <button class="vsc-key vsc-char-key" data-char="k">k</button>
                        <button class="vsc-key vsc-char-key" data-char="l">l</button>
                        <button class="vsc-key" data-key=";">;</button>
                        <button class="vsc-key" data-key="'">'</button>
                        <button class="vsc-key vsc-wide-key" data-key="Enter">\u21B5</button>
                    </div>
                    <!-- Bottom row -->
                    <div class="vsc-row">
                        <button class="vsc-key vsc-mod-btn vsc-wide-key" data-mod="shift">\u21E7</button>
                        <button class="vsc-key vsc-char-key" data-char="z">z</button>
                        <button class="vsc-key vsc-char-key" data-char="x">x</button>
                        <button class="vsc-key vsc-char-key" data-char="c">c</button>
                        <button class="vsc-key vsc-char-key" data-char="v">v</button>
                        <button class="vsc-key vsc-char-key" data-char="b">b</button>
                        <button class="vsc-key vsc-char-key" data-char="n">n</button>
                        <button class="vsc-key vsc-char-key" data-char="m">m</button>
                        <button class="vsc-key" data-key=",">,</button>
                        <button class="vsc-key" data-key=".">.</button>
                        <button class="vsc-key" data-key="/">/</button>
                        <button class="vsc-key vsc-wide-key" data-key="Bksp">\u232B</button>
                    </div>
                    <!-- Modifiers + space + arrows -->
                    <div class="vsc-row">
                        <button class="vsc-key vsc-mod-btn" data-mod="alt">Alt</button>
                        <button class="vsc-key vsc-mod-btn" data-mod="meta">Meta</button>
                        <button class="vsc-key vsc-space-key" data-key="Space">&nbsp;</button>
                        <button class="vsc-key vsc-arrow" data-key="Left">\u25C0</button>
                        <button class="vsc-key vsc-arrow" data-key="Down">\u25BC</button>
                        <button class="vsc-key vsc-arrow" data-key="Up">\u25B2</button>
                        <button class="vsc-key vsc-arrow" data-key="Right">\u25B6</button>
                    </div>
                    <!-- Extra symbols (scrollable) -->
                    <div class="vsc-row vsc-row-symbols">
                        <button class="vsc-key" data-key="~">~</button>
                        <button class="vsc-key" data-key="!">!</button>
                        <button class="vsc-key" data-key="@">@</button>
                        <button class="vsc-key" data-key="#">#</button>
                        <button class="vsc-key" data-key="$">$</button>
                        <button class="vsc-key" data-key="%">%</button>
                        <button class="vsc-key" data-key="^">^</button>
                        <button class="vsc-key" data-key="&">&amp;</button>
                        <button class="vsc-key" data-key="*">*</button>
                        <button class="vsc-key" data-key="(">(</button>
                        <button class="vsc-key" data-key=")">)</button>
                        <button class="vsc-key" data-key="_">_</button>
                        <button class="vsc-key" data-key="+">+</button>
                        <button class="vsc-key" data-key="{">{</button>
                        <button class="vsc-key" data-key="}">}</button>
                        <button class="vsc-key" data-key="|">|</button>
                        <button class="vsc-key" data-key=":">:</button>
                        <button class="vsc-key" data-key='"'>"</button>
                        <button class="vsc-key" data-key="<">&lt;</button>
                        <button class="vsc-key" data-key=">">&gt;</button>
                        <button class="vsc-key" data-key="?">?</button>
                        <button class="vsc-key" data-key="Home">Hm</button>
                        <button class="vsc-key" data-key="End">End</button>
                        <button class="vsc-key" data-key="PgUp">PU</button>
                        <button class="vsc-key" data-key="PgDn">PD</button>
                        <button class="vsc-key" data-key="Ins">Ins</button>
                        <button class="vsc-key" data-key="Del">Del</button>
                    </div>
                </div>

                <div class="vsc-panel vsc-touchpad-panel">
                    <div class="vsc-touchpad-area" id="vsc-touchpad">
                        <div class="vsc-touchpad-hint">Drag to move cursor</div>
                    </div>
                    <div class="vsc-touchpad-buttons">
                        <button class="vsc-tp-btn" id="vsc-tp-left">Left</button>
                        <button class="vsc-tp-btn" id="vsc-tp-middle">Mid</button>
                        <button class="vsc-tp-btn" id="vsc-tp-right">Right</button>
                    </div>
                </div>
            </div>
        `;

        document.body.appendChild(overlay);

        const cursor = document.createElement('div');
        cursor.id = 'vsc-cursor';
        document.body.appendChild(cursor);

        return overlay;
    }

    // ========================================================================
    // EVENT HANDLERS
    // ========================================================================
    function setupKeyboardEvents(overlay) {
        overlay.querySelectorAll('.vsc-char-key').forEach(btn => {
            btn.addEventListener('pointerdown', (e) => {
                e.preventDefault(); e.stopPropagation();
                dispatchCharKey(state.modifiers.shift ? btn.dataset.char.toUpperCase() : btn.dataset.char);
                btn.classList.add('vsc-pressed');
                setTimeout(() => btn.classList.remove('vsc-pressed'), 100);
            });
        });

        overlay.querySelectorAll('.vsc-key[data-key]:not(.vsc-mod-btn)').forEach(btn => {
            btn.addEventListener('pointerdown', (e) => {
                e.preventDefault(); e.stopPropagation();
                const keyDef = KEYS[btn.dataset.key];
                if (keyDef) {
                    dispatchKey(keyDef);
                    btn.classList.add('vsc-pressed');
                    setTimeout(() => btn.classList.remove('vsc-pressed'), 100);
                }
            });
        });

        overlay.querySelectorAll('.vsc-mod-btn').forEach(btn => {
            btn.addEventListener('pointerdown', (e) => {
                e.preventDefault(); e.stopPropagation();
                toggleModifier(btn.dataset.mod);
                if (btn.dataset.mod === 'shift') {
                    overlay.querySelector('.vsc-keyboard-panel').classList.toggle('vsc-shifted', state.modifiers.shift);
                }
            });
        });
    }

    function setupTouchpad(overlay) {
        const pad = overlay.querySelector('#vsc-touchpad');

        pad.addEventListener('touchstart', (e) => {
            e.preventDefault();
            const t = e.touches;
            state.touchpad.fingers = t.length;
            state.touchpad.tracking = true;
            state.touchpad.lastX = t[0].clientX;
            state.touchpad.lastY = t[0].clientY;
            if (t.length >= 2) state.touchpad.scrollLastY = t[0].clientY;
            state.tapStart = Date.now();
        }, { passive: false });

        pad.addEventListener('touchmove', (e) => {
            e.preventDefault();
            if (!state.touchpad.tracking) return;
            const touch = e.touches[0];
            const dx = touch.clientX - state.touchpad.lastX;
            const dy = touch.clientY - state.touchpad.lastY;
            state.touchpad.lastX = touch.clientX;
            state.touchpad.lastY = touch.clientY;
            if (e.touches.length >= 2) {
                const scrollDy = touch.clientY - state.touchpad.scrollLastY;
                state.touchpad.scrollLastY = touch.clientY;
                scrollAt(scrollDy * state.scrollSensitivity);
            } else {
                state.cursor.x = Math.max(0, Math.min(window.innerWidth, state.cursor.x + dx * state.sensitivity));
                state.cursor.y = Math.max(0, Math.min(window.innerHeight - 200, state.cursor.y + dy * state.sensitivity));
                updateCursor();
                dispatchPointer('pointermove', 0);
                dispatchMouse('mousemove', 0);
            }
            state.tapStart = 0;
        }, { passive: false });

        pad.addEventListener('touchend', (e) => {
            e.preventDefault();
            state.touchpad.tracking = false;
            const elapsed = Date.now() - state.tapStart;
            if (state.tapStart > 0 && elapsed < 200) {
                const now = Date.now();
                if (state.touchpad.fingers >= 2) { clickAt(2); }
                else if (now - state.lastTap < 300) { doubleClickAt(); state.lastTap = 0; }
                else {
                    state.lastTap = now;
                    clearTimeout(state.tapTimeout);
                    state.tapTimeout = setTimeout(() => { if (state.lastTap > 0) { clickAt(0); state.lastTap = 0; } }, 300);
                }
            }
            state.touchpad.fingers = 0;
        }, { passive: false });

        overlay.querySelector('#vsc-tp-left').addEventListener('pointerdown', (e) => { e.preventDefault(); clickAt(0); });
        overlay.querySelector('#vsc-tp-middle').addEventListener('pointerdown', (e) => { e.preventDefault(); clickAt(1); });
        overlay.querySelector('#vsc-tp-right').addEventListener('pointerdown', (e) => { e.preventDefault(); clickAt(2); });
    }

    // ========================================================================
    // TOOLBAR + SHOW/HIDE
    // ========================================================================
    function sendToAndroid(msg) {
        try { browser.runtime.sendNativeMessage('browser', msg); } catch (_) {}
    }

    function showOverlay(overlay) {
        state.overlayVisible = true;
        overlay.classList.remove('vsc-collapsed');
        const toggle = document.getElementById('vsc-float-toggle');
        if (toggle) toggle.style.display = 'none';
        sendToAndroid({ type: 'overlayVisibility', visible: true });
        requestAnimationFrame(() => {
            sendToAndroid({ type: 'resize', height: Math.round(overlay.getBoundingClientRect().height) });
        });
    }

    function hideOverlay(overlay) {
        state.overlayVisible = false;
        overlay.classList.add('vsc-collapsed');
        sendToAndroid({ type: 'overlayVisibility', visible: false });
        sendToAndroid({ type: 'resize', height: 0 });
        showFloatingToggle(overlay);
    }

    function setupToolbar(overlay) {
        // TP toggle (narrow screens only — on wide, both panels always visible)
        overlay.querySelector('#vsc-tp-toggle').addEventListener('pointerdown', (e) => {
            e.preventDefault();
            const kp = overlay.querySelector('.vsc-keyboard-panel');
            const tp = overlay.querySelector('.vsc-touchpad-panel');
            const showTP = !tp.classList.contains('vsc-active-panel');
            kp.classList.toggle('vsc-active-panel', !showTP);
            tp.classList.toggle('vsc-active-panel', showTP);
            e.target.classList.toggle('vsc-active', showTP);
            requestAnimationFrame(() => {
                sendToAndroid({ type: 'resize', height: Math.round(overlay.getBoundingClientRect().height) });
            });
        });

        overlay.querySelector('#vsc-hide-btn').addEventListener('pointerdown', (e) => {
            e.preventDefault();
            hideOverlay(overlay);
        });
    }

    function showFloatingToggle(overlay) {
        let toggle = document.getElementById('vsc-float-toggle');
        if (!toggle) {
            toggle = document.createElement('button');
            toggle.id = 'vsc-float-toggle';
            toggle.textContent = '\u27E8/\u27E9';
            toggle.addEventListener('pointerdown', (e) => { e.preventDefault(); showOverlay(overlay); });
            toggle.addEventListener('touchstart', e => e.preventDefault(), { passive: false });
            toggle.addEventListener('mousedown', e => e.preventDefault());
            document.body.appendChild(toggle);
        }
        toggle.style.display = 'block';
    }

    // ========================================================================
    // CURSOR
    // ========================================================================
    function updateCursor() {
        const el = document.getElementById('vsc-cursor');
        if (el) el.style.transform = `translate(${state.cursor.x}px, ${state.cursor.y}px)`;
    }

    // ========================================================================
    // WIDE LAYOUT — auto side-by-side
    // ========================================================================
    function updateWideLayout(overlay) {
        const isWide = window.innerWidth > 900;
        overlay.classList.toggle('vsc-wide', isWide);
        if (isWide) {
            overlay.querySelector('.vsc-keyboard-panel').classList.add('vsc-active-panel');
            overlay.querySelector('.vsc-touchpad-panel').classList.add('vsc-active-panel');
        }
        if (state.overlayVisible) {
            requestAnimationFrame(() => {
                sendToAndroid({ type: 'resize', height: Math.round(overlay.getBoundingClientRect().height) });
            });
        }
    }

    // ========================================================================
    // INIT
    // ========================================================================
    function init() {
        if (!document.body) return;

        for (const id of ['vsc-overlay', 'vsc-cursor', 'vsc-float-toggle']) {
            const el = document.getElementById(id);
            if (el) el.remove();
        }

        const overlay = buildOverlay();
        setupKeyboardEvents(overlay);
        setupTouchpad(overlay);
        setupToolbar(overlay);
        updateCursor();

        // Start hidden — show floating toggle
        showFloatingToggle(overlay);

        updateWideLayout(overlay);
        window.addEventListener('resize', () => updateWideLayout(overlay));
    }

    if (document.body) init();
    else document.addEventListener('DOMContentLoaded', init);
})();
