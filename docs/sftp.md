# SFTP File Browser

Browse, upload, download, and manage files on remote servers over SFTP.

## Access

Tap **Files** button on any saved SSH server in the launcher.

## Navigation

- **Path bar** — shows current directory, editable. Type a path and tap Go.
- **Up** — navigate to parent directory
- **Tap folder** — enter directory
- **Tap `..`** — go up one level
- **Close** — return to launcher

## File Operations

### Download

- Tap **DL** button on individual files
- Or select multiple items → tap **Download** in selection bar
- Files are saved to app cache and shared via Android intent
- **Folders download recursively** — entire directory tree

### Upload

- Tap **Upload** button in toolbar
- Android file picker opens — **select multiple files** at once
- Files upload sequentially with status updates
- Correct filenames preserved via ContentResolver

### Delete

- Select items → tap **Delete** in selection bar
- Native confirmation dialog with item count
- **Recursive folder delete** — deletes entire directory tree
- Cannot be undone

### Create Directory

- Tap **Mkdir** button
- Native dialog for directory name
- Created in current directory

## Multi-Select

- **Long-press** any file/folder to enter selection mode
- **Tap** items to toggle selection
- **Select All** button in toolbar selects all items
- **Selection bar** shows: `N selected` + Download / Delete / Cancel
- **Checkboxes** visible on each item

### Selection bar actions

| Button | Action |
|--------|--------|
| Download | Download all selected files/folders |
| Delete | Delete all selected (with confirmation) |
| Cancel | Clear selection |

## Progress

A progress bar below the toolbar shows download/delete progress for multi-item operations.

## Files

- `app/src/main/assets/sftp/sftp.html` — file browser UI
- `app/src/main/java/.../SftpManager.kt` — SFTP operations via JSch
