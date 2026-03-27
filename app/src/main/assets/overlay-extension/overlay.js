(function () {
    'use strict';

    // Already injected and present in DOM? Skip.
    if (document.getElementById('vsc-overlay')) return;

    // ========================================================================
    // STATE
    // ========================================================================
    const state = {
        modifiers: { ctrl: false, alt: false, shift: false, meta: false },
        cursor: { x: window.innerWidth / 2, y: window.innerHeight / 2 },
        touchpad: { tracking: false, lastX: 0, lastY: 0, fingers: 0, scrollLastY: 0 },
        overlayVisible: true,
        keyboardExpanded: false,
        activeTab: 'keyboard', // 'keyboard' | 'touchpad'
        sensitivity: 1.5,
        scrollSensitivity: 3,
        tapStart: 0,
        tapTimeout: null,
        lastTap: 0,
        sysKeyboardVisible: false
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
    };

    // F-keys
    for (let i = 1; i <= 12; i++) {
        KEYS['F' + i] = { key: 'F' + i, code: 'F' + i, keyCode: 111 + i };
    }

    // ========================================================================
    // KEY DISPATCH
    // ========================================================================
    function getTarget() {
        // Try to find Monaco's input textarea first
        return document.querySelector('.monaco-editor .inputarea') ||
               document.activeElement ||
               document.body;
    }

    function dispatchKey(keyDef, extraOpts) {
        const target = getTarget();
        const opts = {
            key: keyDef.key,
            code: keyDef.code,
            keyCode: keyDef.keyCode,
            which: keyDef.keyCode,
            bubbles: true,
            cancelable: true,
            ctrlKey: state.modifiers.ctrl,
            altKey: state.modifiers.alt,
            shiftKey: state.modifiers.shift || (keyDef.shift || false),
            metaKey: state.modifiers.meta,
            ...extraOpts
        };

        target.dispatchEvent(new KeyboardEvent('keydown', opts));

        // For printable characters, also use execCommand
        if (keyDef.key.length === 1 && !opts.ctrlKey && !opts.altKey && !opts.metaKey) {
            document.execCommand('insertText', false, keyDef.key);
        }

        target.dispatchEvent(new KeyboardEvent('keyup', opts));

        // Auto-release modifiers after key press (one-shot mode)
        resetModifiers();
    }

    function dispatchCharKey(char) {
        const upper = char.toUpperCase();
        const lower = char.toLowerCase();
        const isUpper = char === upper && char !== lower;
        const code = 'Key' + upper;
        const keyCode = upper.charCodeAt(0);

        dispatchKey({
            key: char,
            code: code,
            keyCode: keyCode,
            shift: isUpper
        });
    }

    function resetModifiers() {
        state.modifiers.ctrl = false;
        state.modifiers.alt = false;
        state.modifiers.shift = false;
        state.modifiers.meta = false;
        updateModifierButtons();
    }

    function toggleModifier(mod) {
        state.modifiers[mod] = !state.modifiers[mod];
        updateModifierButtons();
    }

    function updateModifierButtons() {
        document.querySelectorAll('.vsc-mod-btn').forEach(btn => {
            const mod = btn.dataset.mod;
            btn.classList.toggle('vsc-active', state.modifiers[mod]);
        });
    }

    // ========================================================================
    // POINTER + MOUSE DISPATCH
    // ========================================================================
    let pointerId = 1;

    function dispatchPointer(type, button, extra) {
        const target = document.elementFromPoint(state.cursor.x, state.cursor.y);
        if (!target) return;
        const opts = {
            clientX: state.cursor.x,
            clientY: state.cursor.y,
            screenX: state.cursor.x,
            screenY: state.cursor.y,
            button: button || 0,
            buttons: button === 2 ? 2 : (button === 0 ? 1 : 0),
            bubbles: true,
            cancelable: true,
            composed: true,
            view: window,
            pointerId: pointerId,
            pointerType: 'mouse',
            isPrimary: true,
            width: 1,
            height: 1,
            pressure: type === 'pointerdown' ? 0.5 : 0,
            ...extra
        };
        target.dispatchEvent(new PointerEvent(type, opts));
    }

    function dispatchMouse(type, button, extra) {
        const target = document.elementFromPoint(state.cursor.x, state.cursor.y);
        if (!target) return;
        const opts = {
            clientX: state.cursor.x,
            clientY: state.cursor.y,
            screenX: state.cursor.x,
            screenY: state.cursor.y,
            button: button || 0,
            buttons: button === 2 ? 2 : (button === 0 ? 1 : 0),
            bubbles: true,
            cancelable: true,
            view: window,
            ...extra
        };
        target.dispatchEvent(new MouseEvent(type, opts));
    }

    function clickAt(button) {
        // Pointer events first (VS Code listens to these), then mouse events as fallback
        dispatchPointer('pointermove', 0);
        dispatchMouse('mousemove', 0);
        dispatchPointer('pointerdown', button);
        dispatchMouse('mousedown', button);
        dispatchPointer('pointerup', button);
        dispatchMouse('mouseup', button);
        dispatchMouse('click', button);
    }

    function doubleClickAt() {
        clickAt(0);
        clickAt(0);
        const target = document.elementFromPoint(state.cursor.x, state.cursor.y);
        if (target) {
            target.dispatchEvent(new MouseEvent('dblclick', {
                clientX: state.cursor.x, clientY: state.cursor.y,
                bubbles: true, cancelable: true, view: window
            }));
        }
    }

    function scrollAt(deltaY) {
        const target = document.elementFromPoint(state.cursor.x, state.cursor.y);
        if (!target) return;
        target.dispatchEvent(new WheelEvent('wheel', {
            deltaY: deltaY,
            deltaX: 0,
            clientX: state.cursor.x,
            clientY: state.cursor.y,
            bubbles: true,
            cancelable: true,
            view: window
        }));
    }

    // ========================================================================
    // BUILD OVERLAY DOM
    // ========================================================================
    function buildOverlay() {
        const overlay = document.createElement('div');
        overlay.id = 'vsc-overlay';

        // Prevent focus stealing from the editor
        overlay.addEventListener('touchstart', e => e.preventDefault(), { passive: false });
        overlay.addEventListener('mousedown', e => e.preventDefault());

        overlay.innerHTML = `
            <div class="vsc-toolbar">
                <button class="vsc-tool-btn vsc-tab-btn vsc-active" data-tab="keyboard">KB</button>
                <button class="vsc-tool-btn vsc-tab-btn" data-tab="touchpad">TP</button>
                <button class="vsc-tool-btn" id="vsc-syskb-btn">SysKB</button>
                <button class="vsc-tool-btn" id="vsc-expand-btn">More</button>
                <div class="vsc-spacer"></div>
                <button class="vsc-tool-btn" id="vsc-hide-btn">Hide</button>
            </div>

            <div class="vsc-panels-container">
                <div class="vsc-panel vsc-keyboard-panel vsc-active-panel">
                    <!-- Row 1: Essential keys -->
                    <div class="vsc-row vsc-row-main">
                        <button class="vsc-key" data-key="Esc">Esc</button>
                        <button class="vsc-key" data-key="Tab">Tab</button>
                        <button class="vsc-key vsc-mod-btn" data-mod="ctrl">Ctrl</button>
                        <button class="vsc-key vsc-mod-btn" data-mod="alt">Alt</button>
                        <button class="vsc-key vsc-mod-btn" data-mod="shift">Shift</button>
                        <button class="vsc-key vsc-mod-btn" data-mod="meta">Meta</button>
                        <button class="vsc-key vsc-arrow" data-key="Up">\u25B2</button>
                        <button class="vsc-key vsc-arrow" data-key="Down">\u25BC</button>
                        <button class="vsc-key vsc-arrow" data-key="Left">\u25C0</button>
                        <button class="vsc-key vsc-arrow" data-key="Right">\u25B6</button>
                    </div>

                    <!-- Row 2: F-keys (expandable) -->
                    <div class="vsc-row vsc-row-extra vsc-hidden">
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

                    <!-- Row 3: Symbols -->
                    <div class="vsc-row vsc-row-extra vsc-hidden">
                        <button class="vsc-key" data-key="\`">\`</button>
                        <button class="vsc-key" data-key="~">~</button>
                        <button class="vsc-key" data-key="|">|</button>
                        <button class="vsc-key" data-key="\\">\\</button>
                        <button class="vsc-key" data-key="{">{</button>
                        <button class="vsc-key" data-key="}">}</button>
                        <button class="vsc-key" data-key="[">[</button>
                        <button class="vsc-key" data-key="]">]</button>
                        <button class="vsc-key" data-key="(">(</button>
                        <button class="vsc-key" data-key=")">)</button>
                        <button class="vsc-key" data-key=";">;</button>
                        <button class="vsc-key" data-key=":">:</button>
                    </div>

                    <!-- Row 4: More symbols -->
                    <div class="vsc-row vsc-row-extra vsc-hidden">
                        <button class="vsc-key" data-key="'">'</button>
                        <button class="vsc-key" data-key='"'>"</button>
                        <button class="vsc-key" data-key="-">-</button>
                        <button class="vsc-key" data-key="=">=</button>
                        <button class="vsc-key" data-key="_">_</button>
                        <button class="vsc-key" data-key="+">+</button>
                        <button class="vsc-key" data-key="&">&amp;</button>
                        <button class="vsc-key" data-key="*">*</button>
                        <button class="vsc-key" data-key="#">#</button>
                        <button class="vsc-key" data-key="@">@</button>
                        <button class="vsc-key" data-key="!">!</button>
                        <button class="vsc-key" data-key="/">/</button>
                    </div>

                    <!-- Row 5: Navigation keys -->
                    <div class="vsc-row vsc-row-extra vsc-hidden">
                        <button class="vsc-key" data-key="Home">Home</button>
                        <button class="vsc-key" data-key="End">End</button>
                        <button class="vsc-key" data-key="PgUp">PgUp</button>
                        <button class="vsc-key" data-key="PgDn">PgDn</button>
                        <button class="vsc-key" data-key="Ins">Ins</button>
                        <button class="vsc-key" data-key="Del">Del</button>
                        <button class="vsc-key" data-key="Enter">Enter</button>
                        <button class="vsc-key" data-key="Bksp">Bksp</button>
                        <button class="vsc-key" data-key="Space">Space</button>
                    </div>
                </div>

                <div class="vsc-panel vsc-touchpad-panel">
                    <div class="vsc-touchpad-area" id="vsc-touchpad">
                        <div class="vsc-touchpad-hint">Drag to move cursor</div>
                    </div>
                    <div class="vsc-touchpad-buttons">
                        <button class="vsc-tp-btn" id="vsc-tp-left">Left Click</button>
                        <button class="vsc-tp-btn" id="vsc-tp-middle">Middle</button>
                        <button class="vsc-tp-btn" id="vsc-tp-right">Right Click</button>
                    </div>
                </div>
            </div>
        `;

        document.body.appendChild(overlay);

        // Cursor indicator
        const cursor = document.createElement('div');
        cursor.id = 'vsc-cursor';
        document.body.appendChild(cursor);

        return overlay;
    }

    // ========================================================================
    // SYSTEM KEYBOARD (hidden input trick)
    // ========================================================================
    function setupSysKeyboard() {
        const input = document.createElement('input');
        input.id = 'vsc-hidden-input';
        input.setAttribute('autocapitalize', 'none');
        input.setAttribute('autocomplete', 'off');
        input.setAttribute('autocorrect', 'off');
        input.setAttribute('spellcheck', 'false');
        input.style.cssText = 'position:fixed;top:-9999px;left:-9999px;opacity:0;width:1px;height:1px;';
        document.body.appendChild(input);

        input.addEventListener('input', (e) => {
            const data = e.data;
            if (data) {
                const target = getTarget();
                if (target) target.focus();
                for (const ch of data) {
                    dispatchCharKey(ch);
                }
            }
            input.value = '';
        });

        input.addEventListener('keydown', (e) => {
            // Forward special keys from system keyboard
            if (['Enter', 'Backspace', 'Tab', 'Escape'].includes(e.key)) {
                e.preventDefault();
                const keyDef = KEYS[e.key === 'Backspace' ? 'Bksp' : e.key] || KEYS[e.key];
                if (keyDef) dispatchKey(keyDef);
            }
        });

        return input;
    }

    // ========================================================================
    // EVENT HANDLERS
    // ========================================================================
    function setupKeyboardEvents(overlay) {
        // Key buttons
        overlay.querySelectorAll('.vsc-key:not(.vsc-mod-btn)').forEach(btn => {
            btn.addEventListener('pointerdown', (e) => {
                e.preventDefault();
                e.stopPropagation();
                const keyName = btn.dataset.key;
                const keyDef = KEYS[keyName];
                if (keyDef) {
                    dispatchKey(keyDef);
                    btn.classList.add('vsc-pressed');
                    setTimeout(() => btn.classList.remove('vsc-pressed'), 100);
                }
            });
        });

        // Modifier buttons
        overlay.querySelectorAll('.vsc-mod-btn').forEach(btn => {
            btn.addEventListener('pointerdown', (e) => {
                e.preventDefault();
                e.stopPropagation();
                toggleModifier(btn.dataset.mod);
            });
        });
    }

    function setupTouchpad(overlay) {
        const pad = overlay.querySelector('#vsc-touchpad');

        pad.addEventListener('touchstart', (e) => {
            e.preventDefault();
            const touches = e.touches;
            state.touchpad.fingers = touches.length;
            state.touchpad.tracking = true;
            state.touchpad.lastX = touches[0].clientX;
            state.touchpad.lastY = touches[0].clientY;
            if (touches.length >= 2) {
                state.touchpad.scrollLastY = touches[0].clientY;
            }
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
                // Two-finger scroll
                const scrollDy = touch.clientY - state.touchpad.scrollLastY;
                state.touchpad.scrollLastY = touch.clientY;
                scrollAt(scrollDy * state.scrollSensitivity);
            } else {
                // Single finger - move cursor
                state.cursor.x = Math.max(0, Math.min(window.innerWidth, state.cursor.x + dx * state.sensitivity));
                state.cursor.y = Math.max(0, Math.min(window.innerHeight - 200, state.cursor.y + dy * state.sensitivity));
                updateCursor();
                dispatchPointer('pointermove', 0);
                dispatchMouse('mousemove', 0);
            }
            state.tapStart = 0; // moved, not a tap
        }, { passive: false });

        pad.addEventListener('touchend', (e) => {
            e.preventDefault();
            state.touchpad.tracking = false;

            // Detect tap (short touch without much movement)
            const elapsed = Date.now() - state.tapStart;
            if (state.tapStart > 0 && elapsed < 200) {
                const now = Date.now();
                if (state.touchpad.fingers >= 2) {
                    // Two-finger tap = right click
                    clickAt(2);
                } else if (now - state.lastTap < 300) {
                    // Double tap
                    doubleClickAt();
                    state.lastTap = 0;
                } else {
                    // Single tap = left click
                    state.lastTap = now;
                    clearTimeout(state.tapTimeout);
                    state.tapTimeout = setTimeout(() => {
                        if (state.lastTap > 0) {
                            clickAt(0);
                            state.lastTap = 0;
                        }
                    }, 300);
                }
            }

            state.touchpad.fingers = 0;
        }, { passive: false });

        // Explicit click buttons
        overlay.querySelector('#vsc-tp-left').addEventListener('pointerdown', (e) => {
            e.preventDefault();
            clickAt(0);
        });
        overlay.querySelector('#vsc-tp-middle').addEventListener('pointerdown', (e) => {
            e.preventDefault();
            clickAt(1);
        });
        overlay.querySelector('#vsc-tp-right').addEventListener('pointerdown', (e) => {
            e.preventDefault();
            clickAt(2);
        });
    }

    function setupToolbar(overlay, hiddenInput) {
        // Tab switching
        overlay.querySelectorAll('.vsc-tab-btn').forEach(btn => {
            btn.addEventListener('pointerdown', (e) => {
                e.preventDefault();
                const tab = btn.dataset.tab;
                state.activeTab = tab;

                overlay.querySelectorAll('.vsc-tab-btn').forEach(b => b.classList.remove('vsc-active'));
                btn.classList.add('vsc-active');

                overlay.querySelector('.vsc-keyboard-panel').classList.toggle('vsc-active-panel', tab === 'keyboard');
                overlay.querySelector('.vsc-touchpad-panel').classList.toggle('vsc-active-panel', tab === 'touchpad');
            });
        });

        // Expand/collapse extra rows
        overlay.querySelector('#vsc-expand-btn').addEventListener('pointerdown', (e) => {
            e.preventDefault();
            state.keyboardExpanded = !state.keyboardExpanded;
            overlay.querySelectorAll('.vsc-row-extra').forEach(row => {
                row.classList.toggle('vsc-hidden', !state.keyboardExpanded);
            });
            e.target.textContent = state.keyboardExpanded ? 'Less' : 'More';
            requestAnimationFrame(() => updatePadding(overlay, 0));
        });

        // System keyboard toggle
        overlay.querySelector('#vsc-syskb-btn').addEventListener('pointerdown', (e) => {
            e.preventDefault();
            state.sysKeyboardVisible = !state.sysKeyboardVisible;
            if (state.sysKeyboardVisible) {
                hiddenInput.style.top = '0px';
                hiddenInput.style.opacity = '0';
                hiddenInput.focus();
            } else {
                hiddenInput.blur();
                hiddenInput.style.top = '-9999px';
                // Reset transform when manually closing sysKB
                overlay.style.transform = '';
                updatePadding(overlay, 0);
            }
            e.target.classList.toggle('vsc-active', state.sysKeyboardVisible);
        });

        // Hide overlay
        overlay.querySelector('#vsc-hide-btn').addEventListener('pointerdown', (e) => {
            e.preventDefault();
            state.overlayVisible = false;
            overlay.classList.add('vsc-collapsed');
            updatePadding(overlay, 0);
            showFloatingToggle(overlay);
        });
    }

    function showFloatingToggle(overlay) {
        let toggle = document.getElementById('vsc-float-toggle');
        if (!toggle) {
            toggle = document.createElement('button');
            toggle.id = 'vsc-float-toggle';
            toggle.textContent = '⟨/⟩';
            toggle.addEventListener('pointerdown', (e) => {
                e.preventDefault();
                state.overlayVisible = true;
                overlay.classList.remove('vsc-collapsed');
                toggle.style.display = 'none';
                // Recalculate padding after overlay is visible again
                requestAnimationFrame(() => updatePadding(overlay, 0));
            });
            // Prevent focus stealing
            toggle.addEventListener('touchstart', e => e.preventDefault(), { passive: false });
            toggle.addEventListener('mousedown', e => e.preventDefault());
            document.body.appendChild(toggle);
        }
        toggle.style.display = 'block';
    }

    // ========================================================================
    // CURSOR VISUAL
    // ========================================================================
    function updateCursor() {
        const el = document.getElementById('vsc-cursor');
        if (el) {
            el.style.transform = `translate(${state.cursor.x}px, ${state.cursor.y}px)`;
        }
    }

    // ========================================================================
    // RESIZE — shrink VS Code viewport so overlay + sysKB don't cover it
    // ========================================================================
    function getWorkbenchEl() {
        // VS Code workbench uses position:absolute with bottom:0
        // We need to push its bottom edge up above the overlay
        return document.querySelector('.monaco-workbench') ||
               document.querySelector('#workbench\\.parts\\.editor') ||
               document.body;
    }

    function updatePadding(overlay, sysKBOffset) {
        const wb = getWorkbenchEl();
        const isFallback = (wb === document.body);

        if (overlay.classList.contains('vsc-collapsed')) {
            if (isFallback) {
                wb.style.height = '';
                wb.style.overflow = '';
            } else {
                wb.style.bottom = '';
            }
            return;
        }

        const overlayHeight = overlay.getBoundingClientRect().height;
        const sysKB = sysKBOffset > 0 ? sysKBOffset : 0;
        const reserved = overlayHeight + sysKB;

        if (isFallback) {
            // No VS Code workbench found — fall back to body height
            wb.style.height = (window.innerHeight - reserved) + 'px';
            wb.style.overflow = 'hidden';
        } else {
            // Push workbench bottom edge above overlay + sysKB
            wb.style.bottom = reserved + 'px';
        }
    }

    // ========================================================================
    // VIEWPORT ADJUSTMENT (when system keyboard appears)
    // ========================================================================
    function setupViewportListener(overlay, hiddenInput) {
        const SYSKB_THRESHOLD = 100; // px — viewport shrinks more than this = sysKB visible

        function updateOverlayPosition() {
            if (!window.visualViewport) return;
            const vv = window.visualViewport;
            const offset = window.innerHeight - vv.height - vv.offsetTop;
            const sysKBNow = offset > SYSKB_THRESHOLD;

            if (sysKBNow !== state.sysKeyboardVisible) {
                state.sysKeyboardVisible = sysKBNow;
                const btn = document.getElementById('vsc-syskb-btn');
                if (btn) btn.classList.toggle('vsc-active', sysKBNow);
            }

            overlay.style.transform = offset > 0 ? `translateY(-${offset}px)` : '';
            updatePadding(overlay, offset);
        }

        if (window.visualViewport) {
            window.visualViewport.addEventListener('resize', updateOverlayPosition);
            window.visualViewport.addEventListener('scroll', updateOverlayPosition);
        }

        // When hidden input loses focus, sysKB is gone — reset position
        hiddenInput.addEventListener('focusout', () => {
            state.sysKeyboardVisible = false;
            const btn = document.getElementById('vsc-syskb-btn');
            if (btn) btn.classList.remove('vsc-active');
            overlay.style.transform = '';
            updatePadding(overlay, 0);
        });
    }

    // ========================================================================
    // WIDE DISPLAY — side-by-side KB + TP
    // ========================================================================
    function updateWideLayout(overlay) {
        const isWide = window.innerWidth > 900;
        overlay.classList.toggle('vsc-wide', isWide);

        if (isWide) {
            // In wide mode, both panels are always visible
            overlay.querySelector('.vsc-keyboard-panel').classList.add('vsc-active-panel');
            overlay.querySelector('.vsc-touchpad-panel').classList.add('vsc-active-panel');
        } else {
            // Restore tab-based switching
            const activeTab = state.activeTab;
            overlay.querySelector('.vsc-keyboard-panel').classList.toggle('vsc-active-panel', activeTab === 'keyboard');
            overlay.querySelector('.vsc-touchpad-panel').classList.toggle('vsc-active-panel', activeTab === 'touchpad');
        }

        requestAnimationFrame(() => updatePadding(overlay, 0));
    }

    // ========================================================================
    // INIT
    // ========================================================================
    function init() {
        if (!document.body) return;

        // Clean up any stale elements (from previous injection that lost event handlers)
        for (const id of ['vsc-overlay', 'vsc-cursor', 'vsc-hidden-input', 'vsc-float-toggle']) {
            const el = document.getElementById(id);
            if (el) el.remove();
        }

        const overlay = buildOverlay();
        const hiddenInput = setupSysKeyboard();

        setupKeyboardEvents(overlay);
        setupTouchpad(overlay);
        setupToolbar(overlay, hiddenInput);
        setupViewportListener(overlay, hiddenInput);
        updateCursor();

        // Enable body height transition for resize
        document.body.classList.add('vsc-overlay-active');
        updateWideLayout(overlay);
        window.addEventListener('resize', () => updateWideLayout(overlay));
    }

    // Run immediately if body exists, otherwise wait
    if (document.body) {
        init();
    } else {
        document.addEventListener('DOMContentLoaded', init);
    }
})();
