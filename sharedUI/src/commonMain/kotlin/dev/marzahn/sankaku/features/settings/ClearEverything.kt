package dev.marzahn.sankaku.features.settings

import coil3.ImageLoader
import dev.marzahn.sankaku.data.PostsRepository
import dev.marzahn.sankaku.data.ReadingProgress
import dev.marzahn.sankaku.data.RecentSearches
import dev.marzahn.sankaku.data.TagsRepository
import dev.marzahn.sankaku.features.post.clearVideoCache
import dev.marzahn.sankaku.queue.FeedMarksStore
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.tabs.TabStateStore
import dev.marzahn.sankaku.tabs.TabsController

/**
 * "Clear everything" (Settings): wipes what the app keeps of what was looked at, and nothing else.
 *
 * - **Wiped:** every open tab (one empty Browse tab is left) and the recently closed tabs; `tabs.json`, with the grids' saved places and queue mosaics, and its
 *   copies (`tabs.v1.bak`, `tabs.json.broken`); the seen posts (`seen.json`); the feeds' marks (`feed-marks.json`,
 *   #314); the image
 *   cache, in memory and on disk (previews, samples and zoomed full files); the videos played, on disk (Android); the pages, posts and recommendations held
 *   in memory; the tags the app has learnt (`tags.json`) and the suggestions fetched; the recent searches; where each
 *   book was left, kept on this device while signed out (`readings.json`, #384).
 * - **Kept:** the signed-in session and its tokens (nothing is signed out), the reading progress saved on Sankaku, the
 *   Hide in Recents and G only switches, the
 *   first-launch answer (`first-launch.json`: a preference, and the session kept is the same adult's),
 *   the player and tip preferences, and the posts saved to `Download/Tessera`.
 *
 * Run on the main thread, as it closes tabs.
 */
class ClearEverything(
  private val tabs: TabsController,
  private val tabStore: TabStateStore,
  private val recent: RecentSearches,
  private val knownTags: TagsRepository,
  private val posts: PostsRepository,
  private val images: ImageLoader,
  private val seen: SeenStore,
  private val readings: ReadingProgress,
  private val feedMarks: FeedMarksStore,
  private val clearVideos: suspend () -> Unit = ::clearVideoCache,
) {
  suspend fun run() {
    // The tabs first: closing them stops their paging, prefetching and tag learning, which would otherwise refill the
    // caches while they are wiped. The fresh Browse tab then loads its own first page, as any new tab does.
    // Following, which can't be closed (#229), is made again on its bare feed, without its place or pages.
    ClearSavedTabs(tabs, tabStore).run()
    seen.clear()
    feedMarks.clear()
    readings.clear()
    recent.clear()
    knownTags.forget()
    posts.clearCaches()
    ClearCache(images, clearVideos).run()
  }
}
