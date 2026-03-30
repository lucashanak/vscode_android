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

When connection drops (WiFi switch, network timeout):
1. Terminal shows "Connection lost. Reconnecting (1/3)..."
2. Waits 2-3 seconds between attempts
3. Configurable: attempts count and timeout in Settings

### Known Hosts (TOFU)

- First connection: fingerprint saved automatically
- Subsequent connections: verified against saved fingerprint
- If fingerprint changes: warning dialog with Accept/Reject

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
