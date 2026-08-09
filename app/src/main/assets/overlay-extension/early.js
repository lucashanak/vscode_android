/**
 * Runs at document_start, before the page's own scripts.
 *
 * Everything else in this extension attaches at document_idle, which is far too late for the thing
 * being investigated: vscode.dev pulls its 4.5 MB workbench bundle in with a *static* ES module
 * import, so that module is fetched and evaluated while the document is still parsing. On the
 * affected device the bundle downloads and never runs — `define` and `MonacoPerformanceMarks` are
 * absent while the earlier bootstrap globals are present — and every capture reported `errors=[]`
 * simply because nothing was listening yet. A module that fails to evaluate, or a CSP violation,
 * would both have gone unrecorded.
 *
 * Findings are stashed in sessionStorage rather than a shared variable: content scripts from one
 * extension are supposed to share a sandbox, but "supposed to" has already cost this investigation
 * three false readings, and sessionStorage is verifiable. overlay.js drains it.
 */
(function () {
    'use strict';

    var KEY = '__vsct_early';
    var MAX = 28;

    function push(entry) {
        try {
            var raw = sessionStorage.getItem(KEY);
            var list = raw ? JSON.parse(raw) : [];
            if (list.length >= MAX) return;
            var s = String(entry).replace(/\s+/g, ' ').slice(0, 300);
            if (list.indexOf(s) === -1) {
                list.push(s);
                sessionStorage.setItem(KEY, JSON.stringify(list));
            }
        } catch (e) { /* storage may be unavailable; nothing to fall back to */ }
    }

    // Marker so a missing early script is distinguishable from a page that reported nothing.
    push('early: attached at ' + document.readyState);

    // The page's own console, hooked before any page script runs.
    //
    // This closes the last blind spot. A module script whose graph fails a CORS check, or fails to
    // parse, reports that *only* through the console — no window error event, no rejection, no CSP
    // violation. overlay.js does hook the console, but at document_idle, by which time the workbench
    // bootstrap has long since finished. Every clean reading so far was taken after the fact.
    //
    // A content script lives in an isolated world and cannot see the page's console object, so
    // wrappedJSObject/exportFunction are the way in; an injected <script> element is refused by the
    // page's CSP, which is what an earlier attempt discovered the hard way.
    try {
        if (typeof exportFunction === 'function' && window.wrappedJSObject) {
            var w = window.wrappedJSObject;
            ['error', 'warn'].forEach(function (kind) {
                var original = w.console[kind];
                exportFunction(function () {
                    try {
                        var parts = [];
                        for (var i = 0; i < arguments.length && i < 4; i++) {
                            var a = arguments[i];
                            parts.push(a && a.message ? String(a.message) : String(a));
                        }
                        push('console.' + kind + '@' + document.readyState + ': ' + parts.join(' '));
                    } catch (_) {}
                    try { return original.apply(w.console, arguments); } catch (_) {}
                }, w.console, { defineAs: kind });
            });
            // So overlay.js does not wrap the same console a second time and double-report.
            // Recorded in sessionStorage rather than as an expando on the page window: content
            // scripts of one extension are supposed to share a sandbox, and this file's own header
            // notes what trusting that has already cost. sessionStorage is the mechanism already
            // verified to carry findings between these two scripts.
            try { sessionStorage.setItem('__vsct_conhook', '1'); } catch (_) {}
        }
    } catch (e) { /* diagnostics must never break the page */ }

    try {
        window.addEventListener('error', function (e) {
            try {
                if (e.target && e.target !== window && e.target.tagName) {
                    // Resource-level failure (script/link/img that would not load).
                    //
                    // Worth describing precisely rather than by URL alone. A healthy desktop load
                    // also reports one script error — an element with src="" whose empty attribute
                    // resolves to the document URL — so a bare URL cannot tell a normal failure
                    // from the interesting one. What distinguishes them: an *inline* <script> has
                    // no src attribute at all, and for an inline module script an error event means
                    // its import graph failed to fetch or instantiate. That is the case worth
                    // catching, and it needs type/srcAttr to be recognisable.
                    var el = e.target;
                    var tag = el.tagName.toLowerCase();
                    var desc = tag;
                    if (tag === 'script') {
                        var rawSrc = el.getAttribute('src');
                        var body = el.textContent || '';
                        desc += ' type=' + (el.getAttribute('type') || '-') +
                                ' srcAttr=' + (rawSrc === null ? '(absent)' : '"' + rawSrc + '"') +
                                ' inlineLen=' + body.length +
                                // Content itself is not logged: this is a third-party page and its
                                // inline bootstrap is not ours to copy. These two flags identify
                                // the element without reproducing any of it.
                                ' hasImport=' + (body.indexOf('import') !== -1) +
                                ' hasWorkbench=' + (body.indexOf('workbench.web.main') !== -1);
                        try {
                            desc += ' idx=' + Array.prototype.indexOf.call(document.scripts, el) +
                                    '/' + document.scripts.length;
                        } catch (_) {}
                    }
                    push('resource-error: <' + desc + '> at=' + document.readyState + ' ' +
                         String(el.src || el.href || '').slice(0, 100));
                } else {
                    push('error: ' + e.message +
                         (e.filename ? ' @' + String(e.filename).split('/').pop() + ':' + e.lineno : ''));
                }
            } catch (_) {}
        }, true);
    } catch (e) {}

    try {
        window.addEventListener('unhandledrejection', function (e) {
            try {
                var r = e.reason;
                push('unhandledrejection: ' + (r && (r.message || r.name) ? (r.message || r.name) : String(r)));
            } catch (_) {}
        }, true);
    } catch (e) {}

    try {
        // Reported by the browser, never through the page's console object, so this event is the
        // only way script can see one.
        document.addEventListener('securitypolicyviolation', function (e) {
            try {
                push('csp: ' + e.violatedDirective +
                     ' blocked=' + String(e.blockedURI || '').slice(0, 90) +
                     (e.sample ? ' sample=' + String(e.sample).slice(0, 60) : ''));
            } catch (_) {}
        }, true);
    } catch (e) {}
})();
