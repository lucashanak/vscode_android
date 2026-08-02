# SSH Client & Terminal

## Connecting

### Saved Servers

1. Tap **+ Add** in SSH Servers section
2. Fill in: host, port, username, password (or SSH key)
3. Optional: startup command, port forwards, snippets, color scheme
4. Tap **Save** → server appears in list
5. Tap server to connect

### Quick Connect

1. Tap **Quick** button
2. Enter `user@host` or `user@host:port`
3. Enter password → Connect

### SSH Key Authentication

Two options:
- **Paste key**: paste PEM private key into the key field in server settings
- **File picker**: tap "Pick key file..." to select a `.pem`/`.ppk` file from device

### SSH Key Generation

1. Tap **Key Gen** button
2. Choose ED25519 (recommended) or RSA-4096
3. Enter comment (e.g. `android@phone`)
4. Copy public key → add to server's `~/.ssh/authorized_keys`
5. Copy private key → paste into server settings

## Connection Features

### Startup Command

Runs automatically after shell opens. Examples:
- `cd /var/www/myproject`
- `tmux attach || tmux new`
- `cd /app && source venv/bin/activate`

### Port Forwarding

Configure in server settings. Format:
- **Local**: `L8080:127.0.0.1:80` — forward local port 8080 to remote 80
- **Remote**: `R3000:localhost:3000` — forward remote port 3000 to local 3000
- Multiple: comma-separated `L8080:127.0.0.1:80,R3000:localhost:3000`

### Auto-Reconnect

When a connection drops (WiFi switch, network timeout):

1. Terminal shows "Connection lost. Reconnecting (n/m)..."
2. Backoff is exponential with jitter, not a fixed delay. In the foreground the first retry is
   immediate, then 2s, 4s, 8s… capped at 30s. In the background it starts at 2s and caps at 60s.
3. The app watches the network directly (`registerDefaultNetworkCallback`), so a WiFi→cellular
   handover triggers a reconnect as soon as the new route appears rather than waiting for the
   blocked read to time out. Losing the network tears the transport down immediately so the
   terminal doesn't appear frozen while a dead socket waits for its keepalive to expire.
4. Returning to the app also sweeps for a dead session — the network callback only fires on a
   change, and after a long spell in the background connectivity is usually already up.
5. Settings honoured: **Auto-reconnect** (turning it off really does stop it), **attempts** (applies
   in the foreground, where the user can see what's happening) and **connect timeout**.

In the background retries are unbounded *for network failures* by design: a fixed cap means a
session that exhausts it stays dead for good, which is wrong for a phone that spends most of its
time asleep. Retrying stops immediately for failures that repeating cannot fix — a rejected or
changed host key, bad credentials, an unusable key, or an unresolvable hostname — and the terminal
says which one it was. Retrying a bad password forever would re-send it every cycle, which earns a
`fail2ban` ban or an account lockout rather than a connection.

The attempt counter measures a session that will not *stay* up, not just one that will not start:
it only resets after a connection has held for a while. Otherwise a server whose shell exits
immediately (`/sbin/nologin`, a failing `ForceCommand`, a `startupCommand` ending in `exit`) would
reconnect forever without the configured limit ever applying.

Shell state does not survive a reconnect — use tmux for that.

### Known Hosts (TOFU)

- First connection: fingerprint pinned automatically
- Subsequent connections: verified against the pinned fingerprint
- If the fingerprint changes: warning dialog showing both the old and new fingerprint, with
  Accept/Reject

Verification happens **during key exchange, before any credential is sent**. This matters: an
earlier version compared fingerprints only after the session had fully connected, by which point
the password had already been transmitted — so the warning described a compromise that had already
happened. Fingerprints are SHA256 and can be compared against `ssh-keygen -lf <keyfile>` on the
server.

The tmux, SFTP and mosh paths share the same verification. They pin a first-seen key silently
(there is no interactive prompt on those paths) but always **reject** a changed key rather than
asking.

Pins are stored in ordinary app-private preferences, deliberately not encrypted — see
[Credential storage](#credential-storage).

### Mosh

UDP-based protocol that survives WiFi switches and high latency.

1. Enable `useMosh` in server settings
2. Requires `mosh-server` installed on the remote
3. Requires `mosh-client` binary in the APK (built automatically by CI)
4. Falls back to SSH if mosh binary is unavailable

## Terminal

### xterm.js terminal emulator with:
- Full ANSI color support (256 colors)
- Scrollback buffer (configurable, default 10000 lines)
- Unicode support

### Touch Gestures

| Gesture | Action |
|---------|--------|
| 1-finger drag | Scroll terminal (Termux-style) |
| Long-press (500ms) | Select word at touch position |
| 2-finger drag | Scroll (native Kotlin handler) |
| 2-finger tap | Context menu |
| Pinch | Zoom font size (8-32px) |
| Selection handles | Drag teardrops to extend selection |

**Haptic feedback** on word select, copy, and paste (when enabled in Settings).

### Tmux Scroll Support

When connecting to a tmux session, mouse mode is automatically enabled (`set -g mouse on`). This allows 1-finger and 2-finger scroll to work inside tmux panes. The terminal sends SGR mouse wheel escape sequences to tmux when in alternate buffer with mouse reporting active.

In tmux, scrolling up enters **copy-mode** (shows `[line/total]` indicator). Scrolling back to the bottom exits copy-mode automatically.

### Context Menu (2-finger tap)

- **Copy** — copy current selection
- **Paste** — paste from clipboard
- **Select All** — select entire buffer
- **Clear** — clear terminal screen
- **Search** — open search bar
- **Export Log** — share scrollback as text file

### Clipboard

**Copy** — select text (long-press, double-tap, or drag the selection handles) and tap Copy on the
selection toolbar, or use the context menu.

**Paste** — via the selection toolbar or context menu. Pasted text goes through xterm.js rather
than straight into the SSH stream, which means:

- **Bracketed paste** is honoured when the remote program has enabled it (`ESC[?2004h`), so pasting
  multi-line text into vim no longer triggers cascading auto-indent, and shells that support it can
  tell a paste from typing. Note that a program which never enables the mode still receives each
  newline as Enter — bracketed paste is opt-in by the *remote* side, and markers are deliberately
  not sent otherwise (a program that hasn't asked for them renders `[200~` literally).
- ESC and C1 control bytes are stripped from pasted text, so clipboard contents can't inject escape
  sequences into the terminal or forge the paste end-marker.
- `\r\n` and `\n` are normalised to `\r`.

**OSC 52** — remote programs can put text on the Android clipboard by emitting
`ESC]52;c;<base64>`. This is what makes `tmux` copy-mode, vim/neovim with `clipboard=unnamedplus`,
and tools like `yank` work across the SSH link. The tmux attach command sets `set-clipboard on` and
`terminal-features ',*:clipboard'` automatically, since tmux does not forward OSC 52 by default.

Clipboard **reading** by the remote host (the `ESC]52;c;?` query form) is **off by default** and
must be enabled explicitly in Settings. A compromised or malicious host could otherwise silently
read whatever you last copied — passwords, tokens, OTPs — with nothing shown in the terminal. Most
terminals (iTerm2, kitty, wezterm, xterm) default this off for the same reason. Writing is always
allowed, as it is comparatively harmless.

Generated private keys are copied with Android's `IS_SENSITIVE` flag so they stay out of the
clipboard preview and history.

### Credential storage

Passwords, private keys and Cloudflare tokens are stored in `EncryptedSharedPreferences` with an
AES256-GCM key held in the Android Keystore. Existing plaintext entries are migrated automatically
on first launch after upgrading.

Host key pins are **not** encrypted, and that is deliberate. A fingerprint is public data — it is
what `ssh-keygen -lf` prints — so there is nothing there to keep secret. Only its integrity
matters, and encryption does not provide that: anyone able to rewrite this app's private files
already has app-level access and could read the encrypted store through the app's own key anyway.
Encrypting pins actively made verification weaker, because a failed decrypt returned "no pin",
which is indistinguishable from "never seen this host" — so a single transient Keystore error would
silently downgrade every known host back to trust-on-first-use.

Backup is disabled (`allowBackup="false"`, plus data-extraction rules excluding the credential
stores), so secrets cannot be pulled off the device with `adb backup` or carried out by
device-to-device transfer.

**Consequence worth knowing:** because the master key lives in the Keystore and cannot be exported,
saved servers do not survive a device migration, and if the device credential store is ever reset
the saved servers will need to be re-entered.

### Search

Open via context menu → Search. Type to find, use arrows for prev/next.

### Clickable URLs

URLs in terminal output are automatically detected and highlighted. Tap to open in browser.

### Color Schemes

Configurable in Settings → Appearance:
- Default (VS Code dark)
- Solarized Dark
- Dracula
- Monokai
- Linux (classic VGA)

### Snippets

Configure per-server command shortcuts that appear as buttons above the terminal.

Example: `docker ps,git status,ls -la,htop` → four buttons, each runs the command on tap.

## Files

- `app/src/main/assets/terminal/terminal.html` — terminal UI (xterm.js)
- `app/src/main/java/.../SshSessionManager.kt` — SSH connection via JSch
- `app/src/main/java/.../MoshSessionManager.kt` — Mosh subprocess management
- `app/src/main/java/.../ServerStorage.kt` — server persistence + known hosts
