# Cursor Tunnel Support

## Current State

Cursor (cursor.sh) is a VS Code fork, so the web editor is compatible with our overlay keyboard, touchpad, and cursor injection.

### What works now (no code changes needed):
- **Manual URL input**: Enter `https://cursor.sh/tunnel/MACHINE_NAME` in the URL field and connect
- Keyboard, touchpad, cursor, mouse injection all work (same JS event model as VS Code)

### What doesn't work:
- **Tunnel list auto-discovery**: `TunnelApi.kt` calls VS Code API (`api.tunnels.api.visualstudio.com`), not Cursor's API
- **URL label extraction**: Several places in `MainActivity.kt` check `url.contains("vscode.dev")` for display labels

## Changes needed for full support

### 1. TunnelApi.kt (line ~102)
```kotlin
// Current:
return "https://vscode.dev/tunnel/${URLEncoder.encode(tunnelName, "UTF-8")}"
// Change to support both, based on a setting
```

### 2. MainActivity.kt - Label extraction (lines ~1666, ~2056, ~2482)
```kotlin
// Add cursor.sh prefix removal:
val label = url.removePrefix("https://cursor.sh/tunnel/")
    .removePrefix("https://vscode.dev/tunnel/")
    .removePrefix("https://insiders.vscode.dev/tunnel/")
```

### 3. MainActivity.kt - URL filter (line ~2054)
```kotlin
// Current: if (!newUrl.contains("vscode.dev")) return
// Change to: if (!newUrl.contains("vscode.dev") && !newUrl.contains("cursor.sh")) return
```

### 4. activity_main.xml - Hint text (line ~492)
```xml
android:hint="https://vscode.dev or cursor.sh/tunnel/..."
```

### 5. Settings (AppSettings.kt + settings dialog)
- Add `editor_platform` setting: "vscode" (default) / "cursor"
- `TunnelApi.buildTunnelUrl()` uses selected platform domain
- Cursor may need its own API endpoint for tunnel discovery (TBD - needs research on Cursor's tunnel API)

## Open questions
- Does Cursor have a public tunnel API similar to VS Code's `api.tunnels.api.visualstudio.com`?
- Does `cursor.sh/tunnel/NAME` URL pattern match VS Code's pattern exactly?
- Are there any Cursor-specific web editor features that need extra handling?
