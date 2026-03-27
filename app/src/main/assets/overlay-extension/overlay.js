(function () {
    'use strict';
    if (document.getElementById('vsc-overlay')) return;

    // ====================== STATE ======================
    const state = {
        modifiers: { ctrl: false, alt: false, shift: false, meta: false },
        cursor: { x: window.innerWidth / 2, y: window.innerHeight / 2 },
        touchpad: { tracking: false, lastX: 0, lastY: 0, fingers: 0, scrollLastY: 0 },
        overlayVisible: false,
        sensitivity: 1.5,
        scrollSensitivity: 3,
        tapStart: 0, tapTimeout: null, lastTap: 0
    };

    // ====================== KEY MAP ======================
    const KEYS = {
        Esc:  { key:'Escape',   code:'Escape',      keyCode:27 },
        Tab:  { key:'Tab',      code:'Tab',          keyCode:9  },
        Enter:{ key:'Enter',    code:'Enter',        keyCode:13 },
        Bksp: { key:'Backspace',code:'Backspace',    keyCode:8  },
        Del:  { key:'Delete',   code:'Delete',       keyCode:46 },
        Space:{ key:' ',        code:'Space',        keyCode:32 },
        Home: { key:'Home',     code:'Home',         keyCode:36 },
        End:  { key:'End',      code:'End',          keyCode:35 },
        PgUp: { key:'PageUp',   code:'PageUp',       keyCode:33 },
        PgDn: { key:'PageDown', code:'PageDown',     keyCode:34 },
        Ins:  { key:'Insert',   code:'Insert',       keyCode:45 },
        Up:   { key:'ArrowUp',  code:'ArrowUp',      keyCode:38 },
        Down: { key:'ArrowDown',code:'ArrowDown',    keyCode:40 },
        Left: { key:'ArrowLeft',code:'ArrowLeft',    keyCode:37 },
        Right:{ key:'ArrowRight',code:'ArrowRight',  keyCode:39 },
        '`':  { key:'`', code:'Backquote',  keyCode:192 },
        '~':  { key:'~', code:'Backquote',  keyCode:192, shift:true },
        '|':  { key:'|', code:'Backslash',  keyCode:220, shift:true },
        '\\': { key:'\\',code:'Backslash',  keyCode:220 },
        '[':  { key:'[', code:'BracketLeft', keyCode:219 },
        ']':  { key:']', code:'BracketRight',keyCode:221 },
        '{':  { key:'{', code:'BracketLeft', keyCode:219, shift:true },
        '}':  { key:'}', code:'BracketRight',keyCode:221, shift:true },
        '(':  { key:'(', code:'Digit9',      keyCode:57,  shift:true },
        ')':  { key:')', code:'Digit0',      keyCode:48,  shift:true },
        ';':  { key:';', code:'Semicolon',   keyCode:186 },
        ':':  { key:':', code:'Semicolon',   keyCode:186, shift:true },
        "'":  { key:"'", code:'Quote',       keyCode:222 },
        '"':  { key:'"', code:'Quote',       keyCode:222, shift:true },
        ',':  { key:',', code:'Comma',       keyCode:188 },
        '.':  { key:'.', code:'Period',      keyCode:190 },
        '/':  { key:'/', code:'Slash',       keyCode:191 },
        '?':  { key:'?', code:'Slash',       keyCode:191, shift:true },
        '-':  { key:'-', code:'Minus',       keyCode:189 },
        '_':  { key:'_', code:'Minus',       keyCode:189, shift:true },
        '=':  { key:'=', code:'Equal',       keyCode:187 },
        '+':  { key:'+', code:'Equal',       keyCode:187, shift:true },
        '&':  { key:'&', code:'Digit7', keyCode:55, shift:true },
        '*':  { key:'*', code:'Digit8', keyCode:56, shift:true },
        '#':  { key:'#', code:'Digit3', keyCode:51, shift:true },
        '@':  { key:'@', code:'Digit2', keyCode:50, shift:true },
        '!':  { key:'!', code:'Digit1', keyCode:49, shift:true },
        '%':  { key:'%', code:'Digit5', keyCode:53, shift:true },
        '^':  { key:'^', code:'Digit6', keyCode:54, shift:true },
        '$':  { key:'$', code:'Digit4', keyCode:52, shift:true },
        '<':  { key:'<', code:'Comma',  keyCode:188, shift:true },
        '>':  { key:'>', code:'Period', keyCode:190, shift:true },
    };
    for (let i = 1; i <= 12; i++) KEYS['F'+i] = { key:'F'+i, code:'F'+i, keyCode:111+i };
    for (let i = 0; i <= 9; i++) KEYS[String(i)] = { key:String(i), code:'Digit'+i, keyCode:48+i };

    // ====================== KEY DISPATCH ======================
    function getTarget() {
        return document.querySelector('.monaco-editor .inputarea') || document.activeElement || document.body;
    }

    function dispatchKey(keyDef) {
        const target = getTarget();
        const opts = {
            key:keyDef.key, code:keyDef.code, keyCode:keyDef.keyCode, which:keyDef.keyCode,
            bubbles:true, cancelable:true,
            ctrlKey:state.modifiers.ctrl, altKey:state.modifiers.alt,
            shiftKey:state.modifiers.shift||(keyDef.shift||false), metaKey:state.modifiers.meta,
        };
        target.dispatchEvent(new KeyboardEvent('keydown', opts));
        if (keyDef.key.length === 1 && !opts.ctrlKey && !opts.altKey && !opts.metaKey)
            document.execCommand('insertText', false, keyDef.key);
        target.dispatchEvent(new KeyboardEvent('keyup', opts));
        resetModifiers();
    }

    function dispatchCharKey(ch) {
        const u = ch.toUpperCase(), l = ch.toLowerCase();
        dispatchKey({ key:ch, code:'Key'+u, keyCode:u.charCodeAt(0), shift:ch===u&&ch!==l });
    }

    function resetModifiers() {
        state.modifiers.ctrl = state.modifiers.alt = state.modifiers.shift = state.modifiers.meta = false;
        document.querySelectorAll('.vsc-mod-btn').forEach(b => b.classList.remove('vsc-active'));
        const kp = document.querySelector('.vsc-keyboard-panel');
        if (kp) kp.classList.remove('vsc-shifted');
    }

    function toggleModifier(mod) {
        state.modifiers[mod] = !state.modifiers[mod];
        document.querySelectorAll('.vsc-mod-btn').forEach(b => b.classList.toggle('vsc-active', state.modifiers[b.dataset.mod]));
    }

    // ====================== POINTER / MOUSE ======================
    let pointerId = 1;
    function dispatchPointer(type, btn) {
        const t = document.elementFromPoint(state.cursor.x, state.cursor.y); if (!t) return;
        t.dispatchEvent(new PointerEvent(type, { clientX:state.cursor.x,clientY:state.cursor.y, button:btn||0, buttons:btn===2?2:btn===0?1:0, bubbles:true,cancelable:true,composed:true,view:window, pointerId,pointerType:'mouse',isPrimary:true, width:1,height:1,pressure:type==='pointerdown'?0.5:0 }));
    }
    function dispatchMouse(type, btn) {
        const t = document.elementFromPoint(state.cursor.x, state.cursor.y); if (!t) return;
        t.dispatchEvent(new MouseEvent(type, { clientX:state.cursor.x,clientY:state.cursor.y, button:btn||0, buttons:btn===2?2:btn===0?1:0, bubbles:true,cancelable:true,view:window }));
    }
    function clickAt(b) {
        dispatchPointer('pointermove',0); dispatchMouse('mousemove',0);
        dispatchPointer('pointerdown',b); dispatchMouse('mousedown',b);
        dispatchPointer('pointerup',b); dispatchMouse('mouseup',b); dispatchMouse('click',b);
    }
    function doubleClickAt() {
        clickAt(0); clickAt(0);
        const t = document.elementFromPoint(state.cursor.x, state.cursor.y);
        if (t) t.dispatchEvent(new MouseEvent('dblclick',{clientX:state.cursor.x,clientY:state.cursor.y,bubbles:true,view:window}));
    }
    function scrollAt(dy) {
        const t = document.elementFromPoint(state.cursor.x, state.cursor.y);
        if (t) t.dispatchEvent(new WheelEvent('wheel',{deltaY:dy,deltaX:0,clientX:state.cursor.x,clientY:state.cursor.y,bubbles:true,cancelable:true,view:window}));
    }

    // ====================== HELPERS ======================
    function sendToAndroid(msg) { try { browser.runtime.sendNativeMessage('browser', msg); } catch(_){} }

    // ====================== VIEWPORT RESIZE ======================
    // Send overlay height to Android → Kotlin sets GeckoView bottomMargin
    // + dark spacer fills the gap below. Viewport actually shrinks.
    let _lastResize = -1;

    function updateViewportResize(overlay) {
        const h = overlay.classList.contains('vsc-collapsed') ? 0 :
                  Math.round(overlay.getBoundingClientRect().height);
        if (h === _lastResize) return;
        _lastResize = h;
        sendToAndroid({ type: 'resize', height: h });
    }

    // ====================== IME SUPPRESSION ======================
    // Set inputmode="none" on all inputs/textareas to prevent GeckoView
    // from requesting the software keyboard. Standard web API, no flicker.
    let _imeObserver = null;

    function setInputModeNone(enable) {
        const val = enable ? 'none' : '';
        document.querySelectorAll('input, textarea, [contenteditable]').forEach(el => {
            if (enable) el.setAttribute('inputmode', 'none');
            else el.removeAttribute('inputmode');
        });

        if (enable && !_imeObserver) {
            _imeObserver = new MutationObserver(mutations => {
                for (const m of mutations) {
                    for (const node of m.addedNodes) {
                        if (node.nodeType !== 1) continue;
                        if (node.matches && node.matches('input, textarea, [contenteditable]')) {
                            node.setAttribute('inputmode', 'none');
                        }
                        if (node.querySelectorAll) {
                            node.querySelectorAll('input, textarea, [contenteditable]').forEach(el => {
                                el.setAttribute('inputmode', 'none');
                            });
                        }
                    }
                }
            });
            _imeObserver.observe(document.body, { childList: true, subtree: true });
        } else if (!enable && _imeObserver) {
            _imeObserver.disconnect();
            _imeObserver = null;
        }
    }

    function K(key, label) { return `<button class="vsc-key" data-key="${key}">${label||key}</button>`; }
    function C(ch) { return `<button class="vsc-key vsc-char-key" data-char="${ch}">${ch}</button>`; }
    function M(mod, label) { return `<button class="vsc-key vsc-mod-btn" data-mod="${mod}">${label}</button>`; }

    // ====================== BUILD COMPACT KEYBOARD (narrow screens) ======================
    function compactKeyboardHTML() {
        return `
            <div class="vsc-row vsc-row-scroll">
                ${K('Esc')} ${K('Tab')} ${K('`')} ${'1234567890'.split('').map(d=>K(d)).join('')}
                ${K('-')} ${K('=')} ${K('[')} ${K(']')} ${K('\\\\')} ${K(';')} ${K("'")} ${K('/')}
                ${K('Left','\u25C0')} ${K('Down','\u25BC')} ${K('Up','\u25B2')} ${K('Right','\u25B6')}
                ${K('Home','Hm')} ${K('End')} ${K('PgUp','PU')} ${K('PgDn','PD')} ${K('Del')} ${K('Ins')}
            </div>
            <div class="vsc-row">
                ${C('q')}${C('w')}${C('e')}${C('r')}${C('t')}${C('y')}${C('u')}${C('i')}${C('o')}${C('p')}
            </div>
            <div class="vsc-row">
                ${C('a')}${C('s')}${C('d')}${C('f')}${C('g')}${C('h')}${C('j')}${C('k')}${C('l')}
            </div>
            <div class="vsc-row">
                ${M('shift','\u21E7')}${C('z')}${C('x')}${C('c')}${C('v')}${C('b')}${C('n')}${C('m')}${K('Bksp','\u232B')}
            </div>
            <div class="vsc-row">
                ${M('ctrl','Ctrl')} ${M('alt','Alt')} ${M('meta','Meta')}
                <button class="vsc-key vsc-space-key" data-key="Space">&nbsp;</button>
                ${K(',',',')} ${K('.','.') } ${K('Enter','\u21B5')}
            </div>
            <div class="vsc-row vsc-row-scroll">
                ${['~','!','@','#','$','%','^','&','*','(',')','-','_','+','=','{','}','[',']','|','\\\\',':',';','"',"'",'<','>',',','.','/','?','`'].map(s=>K(s, s==='&'?'&amp;':s==='<'?'&lt;':s==='>'?'&gt;':s==='"'?'&quot;':s)).join('')}
                ${'F1 F2 F3 F4 F5 F6 F7 F8 F9 F10 F11 F12'.split(' ').map(f=>K(f)).join('')}
            </div>`;
    }

    // ====================== BUILD FULL KEYBOARD (wide screens) ======================
    function fullKeyboardHTML() {
        return `
            <div class="vsc-row">
                ${K('Esc')} ${'1234567890'.split('').map(d=>K(d)).join('')} ${K('-')} ${K('=')} ${K('Bksp','\u232B')}
            </div>
            <div class="vsc-row">
                ${K('Tab')} ${C('q')}${C('w')}${C('e')}${C('r')}${C('t')}${C('y')}${C('u')}${C('i')}${C('o')}${C('p')}
                ${K('[')} ${K(']')} ${K('\\\\')}
            </div>
            <div class="vsc-row">
                ${M('ctrl','Ctrl')} ${C('a')}${C('s')}${C('d')}${C('f')}${C('g')}${C('h')}${C('j')}${C('k')}${C('l')}
                ${K(';')} ${K("'")} ${K('Enter','\u21B5')}
            </div>
            <div class="vsc-row">
                ${M('shift','\u21E7')} ${C('z')}${C('x')}${C('c')}${C('v')}${C('b')}${C('n')}${C('m')}
                ${K(',')} ${K('.')} ${K('/')} ${M('shift','\u21E7')}
            </div>
            <div class="vsc-row">
                ${M('alt','Alt')} ${M('meta','Meta')} ${K('`')}
                <button class="vsc-key vsc-space-key" data-key="Space">&nbsp;</button>
                ${K('Left','\u25C0')} ${K('Down','\u25BC')} ${K('Up','\u25B2')} ${K('Right','\u25B6')}
            </div>
            <div class="vsc-row vsc-row-scroll">
                ${'F1 F2 F3 F4 F5 F6 F7 F8 F9 F10 F11 F12'.split(' ').map(f=>K(f)).join('')}
                ${['~','!','@','#','$','%','^','&','*','(',')','-','_','+','=','{','}','|',':','"','<','>','?'].map(s=>K(s,s==='&'?'&amp;':s==='<'?'&lt;':s==='>'?'&gt;':s==='"'?'&quot;':s)).join('')}
                ${K('Home')} ${K('End')} ${K('PgUp')} ${K('PgDn')} ${K('Del')} ${K('Ins')}
            </div>`;
    }

    // ====================== TOUCHPAD HTML ======================
    const TOUCHPAD_HTML = `
        <div class="vsc-touchpad-area" id="vsc-touchpad">
            <div class="vsc-touchpad-hint">Drag to move cursor</div>
        </div>
        <div class="vsc-touchpad-buttons">
            <button class="vsc-tp-btn" id="vsc-tp-left">Left</button>
            <button class="vsc-tp-btn" id="vsc-tp-middle">Mid</button>
            <button class="vsc-tp-btn" id="vsc-tp-right">Right</button>
        </div>`;

    // ====================== BUILD OVERLAY ======================
    function buildOverlay() {
        const overlay = document.createElement('div');
        overlay.id = 'vsc-overlay';
        overlay.classList.add('vsc-collapsed');
        overlay.addEventListener('touchstart', e => e.preventDefault(), { passive: false });
        overlay.addEventListener('mousedown', e => e.preventDefault());

        overlay.innerHTML = `
            <div class="vsc-toolbar">
                <button class="vsc-tool-btn" id="vsc-tp-toggle">TP</button>
                <div class="vsc-spacer"></div>
                <button class="vsc-tool-btn" id="vsc-hide-btn">Hide</button>
            </div>
            <div class="vsc-panels-container">
                <div class="vsc-panel vsc-keyboard-panel vsc-active-panel"></div>
                <div class="vsc-panel vsc-touchpad-panel">${TOUCHPAD_HTML}</div>
            </div>`;

        document.body.appendChild(overlay);

        const cursor = document.createElement('div');
        cursor.id = 'vsc-cursor';
        document.body.appendChild(cursor);

        return overlay;
    }

    // ====================== ATTACH KEY EVENTS ======================
    function attachKeyEvents(overlay) {
        overlay.querySelectorAll('.vsc-char-key').forEach(btn => {
            btn.addEventListener('pointerdown', e => {
                e.preventDefault(); e.stopPropagation();
                dispatchCharKey(state.modifiers.shift ? btn.dataset.char.toUpperCase() : btn.dataset.char);
                btn.classList.add('vsc-pressed'); setTimeout(() => btn.classList.remove('vsc-pressed'), 100);
            });
        });
        overlay.querySelectorAll('.vsc-key[data-key]:not(.vsc-mod-btn)').forEach(btn => {
            btn.addEventListener('pointerdown', e => {
                e.preventDefault(); e.stopPropagation();
                const kd = KEYS[btn.dataset.key]; if (!kd) return;
                dispatchKey(kd);
                btn.classList.add('vsc-pressed'); setTimeout(() => btn.classList.remove('vsc-pressed'), 100);
            });
        });
        overlay.querySelectorAll('.vsc-mod-btn').forEach(btn => {
            btn.addEventListener('pointerdown', e => {
                e.preventDefault(); e.stopPropagation();
                toggleModifier(btn.dataset.mod);
                if (btn.dataset.mod === 'shift')
                    overlay.querySelector('.vsc-keyboard-panel').classList.toggle('vsc-shifted', state.modifiers.shift);
            });
        });
    }

    // ====================== TOUCHPAD ======================
    function setupTouchpad(overlay) {
        const pad = overlay.querySelector('#vsc-touchpad'); if (!pad) return;
        pad.addEventListener('touchstart', e => {
            e.preventDefault(); const t=e.touches;
            state.touchpad.fingers=t.length; state.touchpad.tracking=true;
            state.touchpad.lastX=t[0].clientX; state.touchpad.lastY=t[0].clientY;
            if(t.length>=2) state.touchpad.scrollLastY=t[0].clientY;
            state.tapStart=Date.now();
        }, {passive:false});
        pad.addEventListener('touchmove', e => {
            e.preventDefault(); if(!state.touchpad.tracking)return;
            const t=e.touches[0], dx=t.clientX-state.touchpad.lastX, dy=t.clientY-state.touchpad.lastY;
            state.touchpad.lastX=t.clientX; state.touchpad.lastY=t.clientY;
            if(e.touches.length>=2){const sd=t.clientY-state.touchpad.scrollLastY;state.touchpad.scrollLastY=t.clientY;scrollAt(sd*state.scrollSensitivity);}
            else{state.cursor.x=Math.max(0,Math.min(window.innerWidth,state.cursor.x+dx*state.sensitivity));state.cursor.y=Math.max(0,Math.min(window.innerHeight-200,state.cursor.y+dy*state.sensitivity));updateCursor();dispatchPointer('pointermove',0);dispatchMouse('mousemove',0);}
            state.tapStart=0;
        }, {passive:false});
        pad.addEventListener('touchend', e => {
            e.preventDefault(); state.touchpad.tracking=false;
            const el=Date.now()-state.tapStart;
            if(state.tapStart>0&&el<200){const now=Date.now();
                if(state.touchpad.fingers>=2)clickAt(2);
                else if(now-state.lastTap<300){doubleClickAt();state.lastTap=0;}
                else{state.lastTap=now;clearTimeout(state.tapTimeout);state.tapTimeout=setTimeout(()=>{if(state.lastTap>0){clickAt(0);state.lastTap=0;}},300);}
            } state.touchpad.fingers=0;
        }, {passive:false});
        overlay.querySelector('#vsc-tp-left').addEventListener('pointerdown', e=>{e.preventDefault();clickAt(0);});
        overlay.querySelector('#vsc-tp-middle').addEventListener('pointerdown', e=>{e.preventDefault();clickAt(1);});
        overlay.querySelector('#vsc-tp-right').addEventListener('pointerdown', e=>{e.preventDefault();clickAt(2);});
    }

    // ====================== SHOW / HIDE ======================
    function showOverlay(overlay) {
        state.overlayVisible = true;
        overlay.classList.remove('vsc-collapsed');
        const t = document.getElementById('vsc-float-toggle'); if (t) t.style.display = 'none';
        setInputModeNone(true);
        sendToAndroid({ type: 'overlayVisibility', visible: true });
        // Wait for CSS transition to finish before measuring height
        setTimeout(() => updateViewportResize(overlay), 250);
    }
    function hideOverlay(overlay) {
        state.overlayVisible = false;
        overlay.classList.add('vsc-collapsed');
        setInputModeNone(false);
        sendToAndroid({ type: 'overlayVisibility', visible: false });
        updateViewportResize(overlay);
        showFloatingToggle(overlay);
    }

    function setupToolbar(overlay) {
        overlay.querySelector('#vsc-tp-toggle').addEventListener('pointerdown', e => {
            e.preventDefault();
            const kp = overlay.querySelector('.vsc-keyboard-panel');
            const tp = overlay.querySelector('.vsc-touchpad-panel');
            const show = !tp.classList.contains('vsc-active-panel');
            kp.classList.toggle('vsc-active-panel', !show);
            tp.classList.toggle('vsc-active-panel', show);
            e.target.classList.toggle('vsc-active', show);
            updateViewportResize(overlay);
        });
        overlay.querySelector('#vsc-hide-btn').addEventListener('pointerdown', e => { e.preventDefault(); hideOverlay(overlay); });
    }

    function showFloatingToggle(overlay) {
        let t = document.getElementById('vsc-float-toggle');
        if (!t) {
            t = document.createElement('button'); t.id = 'vsc-float-toggle'; t.textContent = '\u27E8/\u27E9';
            t.addEventListener('pointerdown', e => { e.preventDefault(); showOverlay(overlay); });
            t.addEventListener('touchstart', e => e.preventDefault(), { passive: false });
            t.addEventListener('mousedown', e => e.preventDefault());
            document.body.appendChild(t);
        }
        t.style.display = 'block';
    }

    function updateCursor() {
        const el = document.getElementById('vsc-cursor');
        if (el) el.style.transform = `translate(${state.cursor.x}px, ${state.cursor.y}px)`;
    }

    // ====================== LAYOUT SWITCH ======================
    let _currentLayout = '';

    function updateLayout(overlay) {
        const isWide = window.innerWidth > 900;
        const layout = isWide ? 'wide' : 'compact';
        overlay.classList.toggle('vsc-wide', isWide);

        if (layout !== _currentLayout) {
            _currentLayout = layout;
            const kp = overlay.querySelector('.vsc-keyboard-panel');
            kp.innerHTML = isWide ? fullKeyboardHTML() : compactKeyboardHTML();
            attachKeyEvents(overlay);
        }

        if (isWide) {
            overlay.querySelector('.vsc-keyboard-panel').classList.add('vsc-active-panel');
            overlay.querySelector('.vsc-touchpad-panel').classList.add('vsc-active-panel');
        }
        if (state.overlayVisible) updateViewportResize(overlay);
    }

    // ====================== INIT ======================
    function init() {
        if (!document.body) return;
        for (const id of ['vsc-overlay','vsc-cursor','vsc-float-toggle']) {
            const el = document.getElementById(id); if (el) el.remove();
        }

        const overlay = buildOverlay();
        setupTouchpad(overlay);
        setupToolbar(overlay);
        updateCursor();

        updateLayout(overlay);
        showFloatingToggle(overlay);

        window.addEventListener('resize', () => updateLayout(overlay));
    }

    if (document.body) init(); else document.addEventListener('DOMContentLoaded', init);
})();
