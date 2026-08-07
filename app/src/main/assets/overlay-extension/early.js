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
    var MAX = 12;

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

    try {
        window.addEventListener('error', function (e) {
            try {
                if (e.target && e.target !== window && e.target.tagName) {
                    // Resource-level failure (script/link/img that would not load)
                    push('resource-error: <' + e.target.tagName.toLowerCase() + '> ' +
                         String(e.target.src || e.target.href || '').slice(0, 120));
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
