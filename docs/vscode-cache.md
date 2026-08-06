# VS Code cache and the "blank page after idle" problem

Research notes and the reasoning behind the current implementation.

The short version, because it is counterintuitive: **clearing caches is the wrong fix.** It appears
to work, but only because of the reload that happens alongside it. What actually breaks is in-page
JavaScript state, not any cache — and the clearing itself destroys the user's sign-in and throws
away roughly 72 MB.

**Implemented.** Automatic recovery is now a reload, gated on a validated network. The cold-start
path drops only vscode.dev's own document cache, scoped by base domain. The full reset survives as
an explicit button, since it is the fallback when a reload does not recover — see
[What we do now](#what-we-do-now). Self-hosted `serve-web` was deliberately left alone; it needs
the opposite treatment and is not what this app is used with.

Findings are marked **[measured]** (verified here, with the method stated) or **[from source]** (read
out of shipped code or upstream, not exercised).

## Symptom

After the device is backgrounded or screen-off for a long time, the tunnel WebSocket dies and
vscode.dev renders blank or sits on "Reconnecting…" forever. Clearing all browsing data fixes it.

## What we used to do

`GeckoManager.clearBrowsingData()` — fired automatically on resume after `tunnelStaleRefreshMin`
minutes in background (default 10), and as a cold-start pre-clean:

```kotlin
val flags = ALL_CACHES or DOM_STORAGES or AUTH_SESSIONS or SITE_SETTINGS
rt.storageController.clearData(flags)     // note: global, not scoped to an origin
```

## What we do now

| Trigger | Action |
|---|---|
| Resume after N min idle | `session.reload()`, only once the network is **validated** |
| Resume, network not validated yet | defer — do nothing, log it |
| Cold start past threshold | `clearDataFromBaseDomain("vscode.dev", NETWORK_CACHE\|IMAGE_CACHE)` |
| "Reset VS Code" button | the old full clear, unchanged |

The reset button is what makes the automatic path safe to keep this conservative: if a reload ever
fails to recover, there is an explicit escape hatch, so the automatic path never has to guess its
way up to the destructive option. `SITE_SETTINGS` was dropped from the reset too — it revokes
granted permissions and has nothing to do with staleness.

## Why that is expensive and harmful

### The assets cannot go stale [measured]

Fetched live. 99% of the payload is commit-pinned and on a *different origin* to vscode.dev:

| Resource | Origin | `Cache-Control` | Size |
|---|---|---|---|
| `/` and `/tunnel/<name>` (HTML) | vscode.dev | `max-age=150` + strong ETag | 342 KB |
| `workbench.web.main.internal.js` | `main.vscode-cdn.net` | `max-age=31536000, public` | 4.5 MB |
| `workbench…css`, `nls.messages.js` | `main.vscode-cdn.net` | `max-age=31536000, public` | 424 KB |
| `/sw.js` | vscode.dev | `max-age=3600` | 13 KB |

Asset paths carry a 40-hex commit, e.g. `/stable/e4c7e7b1…/out/vs/workbench/…`. A new VS Code
release is a *new URL*, so a cached asset is never a wrong asset. The HTML — the one thing that
could be stale — revalidates after 150 seconds on its own.

The HTML is also byte-identical for every tunnel (same ETag for `/` and `/tunnel/foo`), and contains
no connection token; the tunnel name is parsed client-side from `location.pathname`. So "stale HTML
holding an expired token" is not the problem here. (It *is* a real problem for self-hosted — see
below.)

### `DOM_STORAGES` is far broader than its name suggests [from source]

`mobile/shared/modules/geckoview/GeckoViewStorageController.sys.mjs`, which opens with *"Keep in
sync with StorageController.ClearFlags and nsIClearDataService.idl"*, maps GeckoView bit 4 to
`CLEAR_DOM_QUOTA`, documented in `nsIClearDataService.idl` as:

```
/**
 * LocalStorage, IndexedDB, ServiceWorkers, DOM Cache and so on.
 */
const uint32_t CLEAR_DOM_QUOTA = 1 << 7;
```

Verified flag values in GeckoView 149 (`javap` on the AAR's `classes.jar`):

```
COOKIES=1  NETWORK_CACHE=2  IMAGE_CACHE=4  DOM_STORAGES=16
AUTH_SESSIONS=32  PERMISSIONS=64
ALL_CACHES=6 (NETWORK_CACHE|IMAGE_CACHE)   SITE_SETTINGS=192 (PERMISSIONS|1<<7)
SITE_DATA=471   ALL=512
```

Bit 3, which has no Java constant, is `CLEAR_HISTORY`. `ALL_CACHES` is *only* the HTTP and image
caches — it was never the flag discarding the service worker's precache.

So one call currently destroys:

| Cleared | Consequence |
|---|---|
| localStorage → `stable.secrets.provider` | **sign-in lost** |
| IndexedDB → `msal.db` | **Microsoft auth crypto keys lost** |
| DOM Cache → `workbench` / `core` / `extensions` | **~72 MB re-download** |
| ServiceWorker registration | SW reinstalls and re-precaches |
| IndexedDB → `vscode-web-state-db-*` | editor layout, open tabs |
| HTTP + image cache (`ALL_CACHES`) | everything else re-fetched |
| `PERMISSIONS` (via `SITE_SETTINGS`) | granted permissions revoked |

Cookies do survive — the `// keep cookies (GitHub auth)` comment is accurate — but it does not help,
because the encrypted blob those cookies unlock is in localStorage, which is wiped.

### Scale of the precache [measured]

Playwright + Firefox (Gecko, same engine family as GeckoView), fresh profile, load vscode.dev:

```
service worker registrations : 1
Cache Storage keys           : ["workbench"] → later ["workbench","core","extensions"]
entries per cache            : {"workbench":1739, "core":4, "extensions":38}
```

The SW's `files.txt` manifest is 1853 entries; HEAD-ing all of them totals ~72 MB over the wire
(gzipped — `workbench.web.main.internal.js` alone is 4.5 MB compressed, 17.7 MB raw). It is
incremental and backgrounded, so it does not delay first paint, but every full clear re-incurs it.

## The actual root cause [from source]

`remoteAgentConnection.ts` — `_permanentFailure` is a **`static`** field. A management-connection
failure is fatal (`reconnectionFailureIsFatal = true`), which trips the flag at *class* level, so
every existing connection dies **and every newly constructed one dies immediately too**. Pure
in-page state; no cache is involved.

Upstream's own recovery is a plain reload — `contrib/remote/browser/remote.ts` shows
"Cannot reconnect. Please reload the window." → `ReloadWindowAction`, which in web is literally
`mainWindow.location.reload()`, **with no cache bypass**.

The reconnection token is `generateUuid()` per connection and is never persisted, so a reload always
mints a fresh one. Timeouts: `ReconnectionGraceTime` is **3 hours**, with client backoff settling at
30 s forever — so "Reconnecting…" can legitimately hang for hours, and once the server-side grace
lapses it can never succeed.

Why there is sometimes no error at all: the permanent-failure handler has an `if (e.handled)` branch
that shows nothing — a silently dead workbench.

## The credential-destruction hazard [measured]

The secrets blob is encrypted with a key split in half: one half inline in localStorage, the other
fetched over the network from `POST https://auth.vscode.dev` (cookie `vscode.session`, `_maxAge`
= 604800000 ms = exactly 7 days). The failure handler is destructive:

```js
catch (e) {
  console.error("Failed to decrypt secrets from localStorage", e),
  window.localStorage.removeItem(this._storageKey)      // wipes ALL credentials
}
```
with `_storageKey = \`${this.quality}.secrets.provider\`` (so `stable.secrets.provider`).

**Confirmed by experiment.** Seeding an undecryptable value and reloading produced exactly
`error: Failed to decrypt secrets from localStorage Error` in the console, and the key was removed.

`getServerKeyPart()` gives that fetch **4 attempts over roughly 1.4 s** before falling into the same
handler. On Android that is the dangerous part: after waking from doze the radio is often not up
yet, so a reload issued too early can convert a recoverable hang into a forced re-login.

**Not confirmed:** that this specific offline path fires in practice. The page never contacts
`auth.vscode.dev` when signed out, so the test could not exercise it — verified with a regex route
plus request logging, so this is a genuine gap and not a missed pattern. The destructive handler is
proven; its offline trigger is inferred.

## Self-hosted `code serve-web` is the opposite case [from source]

1. **No service worker at all** — `/sw.js` is vscode.dev-only, so Cache-Storage reasoning is a no-op.
2. Assets are still commit-pinned (`/stable-<commit>/`, `max-age=31536000`).
3. **The HTML has no `Cache-Control` at all** — no ETag either, so Gecko falls back to heuristic
   caching. This *is* a real staleness risk: after a server upgrade the browser can serve HTML
   bootstrapping a `/stable-<oldcommit>/` path the new server no longer serves.
4. **The connection token is persisted**, in cookie `vscode-tkn` (`?tkn=` → cookie → 302), re-issued
   with a 1-week max-age on each root load. Clearing cookies loses it unrecoverably without the
   original `?tkn=` URL.

So for self-hosted, bypassing the cache on the *document* request is justified — the one place it is.

## Direction

1. Stop clearing Cache Storage and the HTTP cache. Nothing in them can go stale; it costs ~72 MB.
2. Never clear cookies or localStorage. That is what destroys sign-in.
3. Replace the clear-everything workaround with `location.reload()` — what upstream does itself.
4. **Gate the reload on real connectivity**, not just a network-available edge, because of the
   ~1.4 s fuse above.
5. If anything must ever be cleared, scope it: `clearDataFromBaseDomain("vscode.dev", …)` leaves the
   CDN bundle alone automatically, since it is on a different base domain.
6. Self-hosted only: bypass cache on the document request.

## Open questions

- Does the offline-reload path actually wipe credentials on a signed-in device? Read
  `localStorage['stable.secrets.provider']` before and after a failing resume. If it vanishes, item 4
  above is the whole fix.
- Is `ms-vscode.remote-server`'s tunnel token stored via `secretStorage`? If so, a secrets wipe
  explains tunnel unreachability directly. The resolver extension is not open source, so this was not
  traceable.
- The SW's cache GC regex only matches `…/{quality}/…`, so `nlsmetadata/<commit>/` (256 KB per
  commit) and the whole `extensions` cache are never collected. Unbounded growth; cannot cause the
  hang, but may be worth pruning.

## Upstream issues

No upstream issue matches idle → blank/stuck-Reconnecting on vscode.dev, and there is no upstream
fix. Related but distinct:

- [#145647](https://github.com/microsoft/vscode/issues/145647) — Firefox blank page on vscode.dev,
  open since 2022. A *first-load* bug, not idle-related, but relevant because GeckoView is Gecko;
  a maintainer's guess in-thread is "service worker issue".
- [#299989](https://github.com/microsoft/vscode/issues/299989),
  [#301505](https://github.com/microsoft/vscode/issues/301505) — `serve-web` blank screens. Build/
  version skew (`NLS MISSING`), reproduce in private mode, so not caching.
