# Changelog

What changed in each release of Tessera.

## Unreleased

## 100.1.0 - 2026-11-13

### Added

- **Books:** browse and search Sankaku's books, with page thumbnails and series. Read a book as one vertical strip,
  move to the next or previous book, and pick up where you left off: signed in, your place syncs with Sankaku;
  signed out, it's kept on the device. A Continue reading shelf, an end-of-book card, ♡ and a book sheet with the
  book's tags. A post that is a book's page says "From the book" and opens it. Books searches and kept books join
  the queue like any other item.
- **Tag pages:** a tag's own page, with its wiki text, posts and books, from a tag's menu or the book sheet.
- **Following:** marks what's new since your last visit ("N new"), offers to Continue where you stopped for 7 days,
  and says when you're caught up. Your followed tags are listed in Settings → Account.
- **Offline:** favourites, votes and follows made without a connection wait and are sent once you're back online.
- **Viewer:** long-press the picture to favourite it, with a burst and a tick. Keep the post you're looking at for
  later (⋮ on a phone, E on a Mac). A Left-handed setting mirrors the vote and favourite row. Videos show a
  buffering spinner, start when ready, and can start muted. A load bar, Retry for GIFs and full files, and badges for
  GIFs and posts that can't play. Past the last post, a page says so, or what to do when loading failed.
- **On a phone:** a Post size slider (three columns by default). The sheet shows five stars, with the counts under
  its peek. The queue's pill shows the block you're in, what's left and where it ends; three tips and a Your queue
  page explain the queue, and the queue page has ☰ for the sidebar and ⌄ to close it.
- **On a Mac:** a queue view with blocks, what's left and bulk closes; C opens a tab switcher; ? shows every
  keyboard shortcut; a right-click menu on posts; tooltips naming each button's key; Settings as a side sheet over
  the tab. Cmd-T and + open a new tab with its search focused. A refined search is titled after the grid it came
  from ("Landscape › +Sky"), tabs shrink to stay in view, and +N opens the queue list.
- Signed out, posts that need an account are left out and Tessera says so once. G only says why only G posts show.

### Fixed

- Some previews on Android showed as a green, striped picture until the post was opened.
- Text grows properly at 200% font size, touch targets are at least 48 dp, and the sidebar scrolls.
- Sankaku's rate limit is respected more closely, and a pause after "too many requests" says how long it is.
- An expired sign-in is renewed instead of showing "offline", and errors give plain messages with a next step.
- On a phone, the keyboard's action key searches, and closing a filter menu puts the keyboard away.
- There's one kind of Undo after closing something, the ↶ button, in the same colour everywhere.
- A kept post from the viewer is marked unseen; Back from a series returns to the previous item instead of closing
  the app; the sheet opens without a drag and remembers where it was when you come back to a post.
- On a Mac, the keyboard cursor stays where you expect in the grid, nothing fades away, and favourites made offline
  are sent when the network returns even while the app is idle.

## 100.0.2 - 2026-11-06

### Fixed

- **Crash on a phone:** after many posts and searches had been opened and closed, Tessera could crash when it went to
  the background. Closed tabs no longer leave their state behind.
- **Queue:** a search opened from a post in the queue now brings you back to that post however you finish it, with
  ✕, a drag down or "Done with …?". A search that's already in the queue says which item it is.
- **Viewer:** a drag down right after lowering a post's sheet closes the post, instead of doing nothing until you lift
  your finger.
- Tessera now presents itself as Chrome 158, the current stable version.

## 100.0.1 - 2026-11-04

No changes in the app. This release checks that updates install, through Obtainium and by hand, and keep your data.
The README now explains Google Play Protect's scan prompt on install.

## 100.0.0 - 2026-11-04

The first public release of Tessera, an unofficial Sankaku client for Android and macOS.

- **Browse:** posts in a grid that loads as you scroll, plus Popular, Top, Favourites, For you and Following. Pull to refresh on a phone.
- **Search:** tag search with autocomplete, coloured tag types, order and minimum-score filters, and a hint naming what to remove when a search finds nothing.
- **Viewer:** full-size pictures with zoom, animated GIFs, and videos that play in place. Each post shows its tags, rating and votes, and its Recommended, parent and child posts.
- **Favourites and tags:** favourite with a double-tap or the heart, vote, follow tags, and copy a tag's name.
- **On a phone, the queue:** long-press a post to keep it for later. The queue page lists what you're reading and what you kept; swipe between items, and Clear viewed when you're done. Posts you've seen are marked in their grid.
- **On a Mac, tabs and the keyboard:** open posts and searches in tabs that survive a restart, with hover previews and reopening closed tabs. Move through the grid with WASD; Space opens the viewer, A and D step through posts, and Cmd-F searches.
- **Save and share:** save a post's original file, or share its link.
- **Account:** sign in with your Sankaku account and stay signed in. "Create an account" opens Sankaku's sign-up page.
- **Privacy:** signed out, Tessera shows only G-rated posts; signed in, the G only switch does the same. Hide in Recents (Android), and Clear everything to wipe the viewing history Tessera keeps on the device.
- **Settings:** Account, Privacy, Storage (cache size, Clear cache, Clear saved tabs), Updates, Diagnostics and About.
- **Updates:** once a day Tessera asks GitHub whether there's a newer version and shows a dot if there is. Nothing is downloaded, and Settings → Updates turns it off.
- **Bug reports:** Diagnostics has the request log, Copy log and Report a bug. After a crash, the next launch offers to report it. Nothing is sent automatically.
