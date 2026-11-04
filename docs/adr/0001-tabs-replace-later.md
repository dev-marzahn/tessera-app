# Android uses tabs, not a "Later" list

The design first gave Android a "Later" list of posts instead of desktop's tabs. By M2 tabs had become the core of the
owner's loop (queue posts and grids in the background, then work through them), and a "Later" list is that same queue
as a second model with its own store that can't hold grids or a history. So Android uses the shared `TabsController`
and saved `tabs.json`, worked through from a tab bar at the bottom; long-press opens a background tab as middle-click
does on desktop. Tabs are the only queue, on every platform.

**Considered options** (mockups in `.scratch/android-tabs/`): a Chrome-like tab grid (two touches per queued post, next
tab at the top of the screen) and a swipeable deck (a second tab container, and its swipes clash with system back and
the viewer's paging).

**Revised by 0004 (2026-11-01):** the queue is still the only one, but it now holds sources, kept posts and feeds, not only tabs.
