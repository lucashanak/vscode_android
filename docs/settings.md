# Settings Reference

Fullscreen settings dialog accessible from **Settings** button on the launcher.

## Appearance

| Setting | Default | Description |
|---------|---------|-------------|
| Terminal color scheme | `default` | Color theme for SSH terminal. Options: default, solarized-dark, dracula, monokai, linux |
| Font size | `14` | Terminal font size in pixels (also adjustable via pinch-to-zoom) |
| Scrollback lines | `10000` | Number of lines kept in terminal scrollback buffer |

## Keyboard

| Setting | Default | Description |
|---------|---------|-------------|
| Suppress system keyboard | `ON` | Prevents Android keyboard from showing in VS Code/SSH sessions. Uses dual mechanism: IME suppression + content script inputmode="none" |
| Haptic feedback | `OFF` | 5ms vibration on each key press |
| Key repeat delay (ms) | `400` | Time to hold a key before repeat starts |
| Key repeat rate (ms) | `50` | Interval between repeated characters (20 chars/sec at default) |

## SSH Defaults

| Setting | Default | Description |
|---------|---------|-------------|
| Default port | `22` | Pre-filled port for new SSH servers |
| Default username | (empty) | Pre-filled username for new SSH servers |
| Default startup command | (empty) | Command to run after connecting (e.g. `cd /app && tmux attach`) |
| Auto-reconnect | `ON` | Automatically retry on connection loss |
| Reconnect attempts | `3` | Number of retry attempts before giving up |
| Connection timeout (s) | `15` | SSH connection timeout in seconds |

## Security

| Setting | Default | Description |
|---------|---------|-------------|
| Biometric lock | `OFF` | Require fingerprint/face/PIN when opening the app. Uses AndroidX Biometric with BIOMETRIC_STRONG + DEVICE_CREDENTIAL |

## Background

| Setting | Default | Description |
|---------|---------|-------------|
| Keep alive in background | `ON` | Runs a foreground service with WakeLock to prevent Android from killing active connections. Shows persistent notification. WakeLock has 4-hour safety timeout. |

## Where Settings Are Stored

All settings use Android SharedPreferences (`app_settings`). SSH servers are stored separately (`ssh_servers`). Known host fingerprints are in `known_hosts`.

## Files

- `app/src/main/java/.../AppSettings.kt` — settings definitions and accessors
- `app/src/main/java/.../MainActivity.kt` — settings dialog UI (`showSettingsDialog()`)
