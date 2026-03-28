# Overlay Keyboard

The custom overlay keyboard replaces the Android system keyboard when working in VS Code or SSH terminals. It's designed specifically for coding on touch devices.

## Layouts

### Compact (phones)

```
[Quick Actions: Cmd Palette | Save | Undo | Redo | Quick Open | Search | Sidebar | Terminal]
[ 1 ][ 2 ][ 3 ][ 4 ][ 5 ][ 6 ][ 7 ][ 8 ][ 9 ][ 0 ]
[ q ][ w ][ e ][ r ][ t ][ y ][ u ][ i ][ o ][ p ]
  [ a ][ s ][ d ][ f ][ g ][ h ][ j ][ k ][ l ]
[Shift][ z ][ x ][ c ][ v ][ b ][ n ][ m ][Bksp]
[?123][Ctrl][Alt][         space         ][ . ][Enter]
```

- **Number row** above letters (always visible)
- **Row stagger** — ASDF row indented 5% like physical keyboard
- **?123** — switches to symbols: `!@#$%^&*()`, nav keys, arrows, F1-F12
- **Quick actions** — scrollable bar with VS Code shortcuts

### Wide (foldables/tablets, >900px)

Full PC-style keyboard with F-key row on top, nav cluster on the right (Home/End/PgUp/PgDn), arrow keys, and touchpad side-by-side.

## Diacritics (Long-press)

Hold a letter key for 300ms to show accent variants. Slide your finger to the desired character and release.

| Key | Accents |
|-----|---------|
| a | á à â ä ã å ą æ |
| c | č ć ç |
| d | ď đ |
| e | é è ě ê ë ę |
| i | í ì î ï |
| n | ň ñ ń |
| o | ó ò ô ö õ ø ő |
| r | ř ŕ |
| s | š ś ş ß |
| t | ť ţ |
| u | ú ù û ü ů ű |
| y | ý ÿ |
| z | ž ź ż |

## Key Repeat

Hold any key (except modifiers) for 400ms to start repeating at 50ms intervals. Configurable in Settings → Keyboard.

## Quick Actions

| Button | Shortcut | Action |
|--------|----------|--------|
| Cmd Palette | Ctrl+Shift+P | VS Code command palette |
| Save | Ctrl+S | Save file |
| Undo | Ctrl+Z | Undo |
| Redo | Ctrl+Shift+Z | Redo |
| Quick Open | Ctrl+P | Open file by name |
| Search Files | Ctrl+Shift+F | Search across files |
| Sidebar | Ctrl+B | Toggle sidebar |
| Terminal | Ctrl+` | Toggle terminal |

## Clipboard History

The **Clip** button in the toolbar shows the last 10 copied texts. The clipboard is polled every 2 seconds to catch copies from VS Code.

## Touchpad

Switch to touchpad via **TP** button in the toolbar.

| Gesture | Action |
|---------|--------|
| Single-finger drag | Move cursor |
| Tap | Left click |
| Double-tap | Double-click |
| Two-finger tap | Right-click |
| Two-finger drag | Scroll |
| Left/Mid/Right buttons | Mouse button click |

## Toolbar

```
[Menu] [Clip]  ...spacer...  [TP] [Hide]
```

- **Menu** — suspend session, return to launcher
- **Clip** — clipboard history popup
- **TP** — switch between keyboard and touchpad
- **Hide** — hide overlay (floating toggle button appears)

## Files

- `app/src/main/assets/overlay-ui/overlay.html` — all keyboard HTML/CSS/JS
- `app/src/main/java/.../OverlayManager.kt` — Android bridge, IME suppression, input routing
