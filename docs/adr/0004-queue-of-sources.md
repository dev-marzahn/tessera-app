# The queue holds sources, kept posts and feeds

ADR 0001 made tabs the only queue on every platform, instead of a separate "Later" list. That still holds: there's one
queue and no second model. What changed is what the queue holds. In daily use the owner reached 170–400 tabs, because a
tab was the only way to keep anything: every post of an artist, every tag found on the way. Closing a grid lost what
hadn't been opened, so nothing could be let go, and new tabs opened at the end buried an artist's posts behind a hundred
others.

So the queue holds three kinds of item:

- A **source** is a search that keeps its place and is gone through in its grid. An artist is just a tag.
- A **kept post** is placed after its source and lasts until the next deliberate "Clear viewed".
- A **feed** (Following, For you) recurs, and marks where you stopped. It is a source that persists (amended
  2026-11-03, #327): it goes at the end of the queue when it's added, and nothing keeps it last after that. Making
  feeds always stand last (#229) put searches opened "later" behind the owner, who was further on in the feeds.

Placement follows intent, around a feed as around any source:

- A new search opened "now" goes in front of the current block, so a rabbit hole unwinds by itself.
- A search opened for "later" goes to the end of this session.
- A post opened from a grid goes after that grid's block.

This replaces "new tabs open at the end" (2026-10-30).

Seen is stored per post, on the device. "Clear viewed" removes only kept posts seen since they were kept, and a grid
dims a post only when it was kept from that grid and seen since (amended 2026-11-02, #262: dimming every seen post had
no clear purpose, on Following least of all). Untouched sources fade after 3 days and are deleted 30 days after fading, after one last reminder. The
app opens on the queue, with non-G thumbnails blurred, and hides its Recents preview. Only a tap on a grid cell opens a
post over a grid; every other way of reaching a post opens it alone.

**Considered options** (`.scratch/tab-queue/`, stress-tested by five personas in `stress-test.md`):

- **Grouped tabs:** tabs fold under their grid and a grid can be parked. It's the smallest change, but the count doesn't
  shrink.
- **Now and Later:** a capped lane of tabs and a shelf that refills it. That's two lists again, and the cap gets in the
  way exactly when something new is found.
- **Going through a source in the viewer, one post at a time:** too slow, since most of an artist's posts don't matter.

**Consequences:**

- The tab strip and the bottom bar become views of the queue. The phone's bar is retired once swiping between queue
  items exists.
- `tabs.json` gains item kinds and places, and a separate store holds seen ids and feed marks.
- The work is built in three stages (spec, Tabs → Queue).
