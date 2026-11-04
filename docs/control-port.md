# Control port

A debug-only HTTP server in the desktop app (`desktopApp/.../ControlPort.kt`) for driving the running app without
taking over the screen. It posts input straight to Compose's canvas, so it works while the window is in the background
or covered, and draws screenshots from the window's own Skia layer.

Start the app with it:

```bash
./gradlew :desktopApp:run -Dsankaku.debug.safeOnly=true -Dsankaku.debug.control=7310
```

It listens on `127.0.0.1` only and prints `Control port on http://127.0.0.1:7310`. `0` picks a free port. Without the
property nothing starts.

`-Dsankaku.debug.dataDir=<dir>` gives the run its own app data folder. The app then moves no data from before the
rename to Tessera, unless `-Dsankaku.debug.oldDataDir=<dir>` names a folder to move it from, as the first launch moves
`~/Library/Application Support/Sankaku`. Use the pair to test that move on copies, never on the owner's folder.

A data folder without `first-launch.json` opens on the first-launch screen (#210), and nothing loads until Continue is
clicked; that's kept in the folder, so it's asked once per folder.

## Calls

Coordinates are the window content's points (Compose dp), top left 0,0, below the title bar: the same as the pixels of
a default screenshot.

| Call | Does |
| --- | --- |
| `GET /screenshot[?wait=ms][&full=1]` | PNG of the window content after `wait` (default 150 ms). `full=1` keeps the Retina pixels; coordinates are then doubled. |
| `GET /tree` | The accessibility tree, one line per element: role, name, `[x,y wxh]`. It can lag a frame or more behind the screen, so trust a screenshot when they disagree. |
| `GET /requests` | The request log, oldest first (at most 200): index, method, host, path template, status, duration. Templates have no ids, md5s or queries. Diff it around an action to check what that action requested. |
| `GET /components` | The AWT components with listener counts and the focus owner, for when input doesn't arrive. |
| `POST /click?x&y[&button=left\|middle\|right][&count=2][&mods=cmd,shift]` | Move there, then press and release. |
| `POST /move?x&y` | Hover (tooltips, hover states). |
| `POST /drag?x1&y1&x2&y2` | Press, drag in 12 steps, release. |
| `POST /scroll?x&y&dy` | Wheel at a point; positive `dy` scrolls down. |
| `POST /type?text=…` (or the text as the body) | Types each character into whatever Compose has focused. |
| `POST /key?name=…[&mods=…]` | One key: `enter`, `esc`, `tab`, `backspace`, `up`, `down`, `left`, `right`, `pageup`, `/`, a letter, or any `KeyEvent.VK_*` name without the prefix. |
| `POST /resize?w&h` | Resizes the window content. |

Example, the search panel's CUJ:

```bash
P=http://127.0.0.1:7310
curl -s -X POST "$P/click?x=400&y=88"
curl -s -X POST "$P/type?text=blue_ha"
curl -s "$P/screenshot?wait=1500" -o panel.png
curl -s -X POST "$P/key?name=tab"
```

## Notes

- Keys go to Compose's focused element. If nothing is focused (the app was never clicked), keys are dropped: click
  first.
- Popups (menus, tooltips) draw on the same canvas, so they show in screenshots and take clicks.
- It never reads app state beyond what the window shows; the rules on tokens, user fields and query strings still apply
  to anything you write down from a screenshot or the tree.
- The packaged app doesn't include `jdk.httpserver`; the port is for `:desktopApp:run` only.
