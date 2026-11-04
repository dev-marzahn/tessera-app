# Tessera

Tessera is an unofficial desktop and Android client for sankakucomplex.com. The language here is the client's, not Sankaku's API.

## Browsing

**Grid**:
The results of one search, shown as a scrolling wall of post previews.
_Avoid_: Feed (that's chosen by Sankaku), results page, gallery

**Post**:
One upload on Sankaku: an image, video or GIF with its tags.
_Avoid_: Image, item

**Viewer**:
A post shown over the grid it was opened from, stepping through that grid's posts.
_Avoid_: Lightbox, post view, detail screen

**Recommended posts**:
Posts Sankaku suggests as similar to one post, shown with that post's details.
_Avoid_: Related posts, similar posts

**Preset**:
A sidebar entry that opens a grid on fixed tags (Popular, Top, Favourites), with nothing carried over from the current search.
_Avoid_: Popular screen, top screen

**Feed**:
Posts Sankaku picks for the account (For you, Following): in the queue, a source that persists. One queue item each (opening one that's open goes to it) that recurs, is never closed, never fades, and remembers where you stopped. Posts kept from it sit under it as their source, in its block. Signed in, Following is always in the queue (For you too, if the user turns it on), added at the end when it comes in; after that it's an ordinary item, which nothing keeps last. The feed isn't counted in the count pill, its kept posts are.
_Avoid_: Timeline, stream

## The queue

**Queue**:
Everything the user is going through or has kept to come back to, in order, on one device. The only one: there's no separate "Later" list.
_Avoid_: Tab list, Later list, backlog

**Queue item**:
One place in the queue: a source, a kept post or a feed. The tab strip shows queue items as tabs; on a phone the count pill shows where the current one is.
_Avoid_: Tab (that's only how a queue item is drawn), window

**Source**:
A search kept in the queue, gone through in its grid, which keeps its place. An artist is just a tag. A source ends only when the user closes it.
_Avoid_: Saved search, bookmark, grid tab

**Kept post**:
A post put in the queue to see again, placed after its source: the grid or feed it was kept from. It lasts until it's seen and the user clears viewed posts. On a phone, long-pressing a post keeps it, and its grid cell says "Kept" (desktop: "In tab").
_Avoid_: Saved post

**Block**:
A source or a feed together with the kept posts that follow it. When the source is closed (or a feed leaves the queue, as on signing out) the kept posts stay as a block marked "from artist_x (closed)", until a source on that search is opened again or they're dragged out.
_Avoid_: Group, stack

**Lone post**:
A post shown with no grid behind it, as every post not tapped in a grid is.
_Avoid_: Post page

**Seen**:
A post the user has opened and acted on, or moved on from once it was fully shown. Scrolling past a post in a grid doesn't make it seen.
_Avoid_: Read, viewed (except in "Clear viewed")

**Queue page**:
The full-screen list of the queue, by blocks, that the app opens on at a start when the queue holds more than the current item (a kept post, or a second source).
_Avoid_: Tab list (the sheet it replaced), queue screen

**Count pill**:
On a phone, the "▤ 2 / 9" pill at the bottom of a grid, or over a post's picture, that says where the current item is in the queue and opens the queue page. Undo grows out of it after a close.
_Avoid_: Tab bar, tab pill (the retired ‹ n / N ›)

**Start**:
A cold launch, or coming back after more than 5 minutes away: what "Start on" acts on. It never ends a session, and it doesn't bring back the queue page's blur, which lasts only until the queue's first use since the app started (#264).
_Avoid_: Session, launch (when a return counts too)

**Session**:
Everything since the user last chose Clear viewed. Closing the app never ends one.
_Avoid_: Visit, launch

**Faded source**:
A source untouched for 3 days, set aside, with its unseen kept posts, in the queue page's Faded section until it's opened again or deleted 30 days after it faded.
_Avoid_: Archived, expired

**Last chance**:
The one offer, in the last week before a faded source is deleted, to keep it or let it go. Not answered, it is deleted all the same.
_Avoid_: Reminder, warning

**Up next**:
The text-only card past the last queue item, whatever it is (a feed's block too), offering one thing at a time that isn't in the queue: a Last chance, then the other faded sources ("Pick up artist_x again?"). Skip moves to the next; past the last comes End of the queue. It is never the first thing shown at a start.
_Avoid_: Suggestions, recommendations (those are Sankaku's, for a post)

**End of the queue**:
The text-only card past the last queue item, "You've reached the end", with Back to the start and Browse. Like Up next, it is never the first thing shown at a start.
_Avoid_: Empty state, last page

**Now / later**:
Opening something now (a tap) puts it in front of the current block. Opening it for later (a new tab) puts it at the end of the session.
_Avoid_: Foreground and background (for placement)

**Background tab**:
A tab opened without leaving the current one. It's how a kept post or a source opened for later is held, until the queue replaces tabs.
_Avoid_: "Open later"

**Post tab**:
A tab whose current screen is a single post: a lone post, held as a tab.
_Avoid_: Post page
