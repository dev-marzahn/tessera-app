package dev.marzahn.sankaku.features.settings

import coil3.ImageLoader
import dev.marzahn.sankaku.features.post.clearVideoCache
import dev.marzahn.sankaku.features.post.videoCacheBytes
import dev.marzahn.sankaku.tabs.TabStateStore
import dev.marzahn.sankaku.tabs.TabsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/**
 * Settings → Storage's cache (#205): the image cache, in memory and on disk (previews, samples and zoomed full files),
 * and the videos played, on disk (Android, #180). "Clear cache" is the narrower wipe of Clear everything: the queue,
 * the seen posts, the tags learnt and the searches stay, and what's shown loads again.
 */
class ClearCache(
  private val images: ImageLoader,
  private val clearVideos: suspend () -> Unit = ::clearVideoCache,
  private val videoBytes: suspend () -> Long = ::videoCacheBytes,
) {
  /** The bytes the disk caches take now: the images' and, on Android, the videos'. */
  suspend fun bytesInUse(): Long = withContext(Dispatchers.Default) { (images.diskCache?.size ?: 0) } + videoBytes()

  suspend fun run() {
    images.memoryCache?.clear()
    // The disk cache holds up to 1 GB: deleting it is off the main thread.
    withContext(Dispatchers.Default) { images.diskCache?.clear() }
    clearVideos()
  }
}

/**
 * Settings → Storage's "Clear saved tabs" (#205; "Clear the queue" on a phone, which has no tabs, #249): the narrower
 * wipe of Clear everything that empties the tabs (one Browse tab is left, and Following, which can't close, is made
 * again on its bare feed), the recently closed and faded ones, the titles posts were named by, and `tabs.json` with its
 * copies. The caches, the seen posts, the tags learnt and the searches stay.
 *
 * Run on the main thread, as it closes tabs.
 */
class ClearSavedTabs(private val tabs: TabsController, private val tabStore: TabStateStore) {
  fun run() {
    tabs.clearAll()
    tabs.forgetClosed()
    tabs.forgetFaded()
    tabs.forgetTitles()
    // The save follows a change after a second; this makes the file empty now, in case the app stops before it.
    // A full disk or locked file must not stop the rest of the wipe; the debounced save rewrites the file anyway.
    runCatching { tabStore.clear() }
    runCatching { tabStore.clearBackups() }
  }
}

/**
 * [bytes] as Storage shows it: "Empty", "Under 1 MB", whole MB below a GB, and GB to one decimal above (binary units,
 * as the cache's 1 GB limit is counted).
 */
internal fun storageSize(bytes: Long): String {
  val mb = 1024L * 1024
  val gb = mb * 1024
  return when {
    bytes <= 0 -> "Empty"
    bytes < mb -> "Under 1 MB"
    bytes < gb -> "${(bytes.toDouble() / mb).roundToLong().coerceAtMost(1023)} MB"
    else -> {
      val tenths = (bytes.toDouble() * 10 / gb).roundToLong()
      if (tenths % 10 == 0L) "${tenths / 10} GB" else "${tenths / 10}.${tenths % 10} GB"
    }
  }
}
