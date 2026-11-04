package dev.marzahn.sankaku.tabs

import coil3.memory.MemoryCache
import dev.marzahn.sankaku.images.PostImage

/**
 * A loaded post's data on the JVM: a keyset page's JSON is a few KB per post (mostly its tags, each with names in
 * several languages), and as objects with UTF-16 strings it takes about twice that. A rough figure, not a measurement.
 */
internal const val POST_BYTES = 8L * 1024

/**
 * Roughly what a tab holds, for its hover preview: the decoded images of the screens it has shown that are in Coil's
 * memory cache right now, plus its loaded posts' data. Decoded bitmaps are by far the largest part, and they live in
 * the shared cache rather than in the tab, so an image two tabs show counts in both (as Chrome counts shared memory),
 * and a background tab's figure drops as the cache evicts its images. [decodedBytes] maps cache keys to bytes.
 */
fun estimateTabBytes(contents: List<TabContents>, decodedBytes: Map<String, Long>): Long {
  val images = contents.flatMapTo(mutableSetOf()) { it.images.map(PostImage::cacheKey) }
  return images.sumOf { decodedBytes[it] ?: 0L } + contents.sumOf { it.posts } * POST_BYTES
}

/** The decoded bytes of each cache key in the memory cache, summed over its entries (one per requested size). */
fun MemoryCache.decodedBytes(): Map<String, Long> =
  keys.groupingBy { it.key }.fold(0L) { total, key -> total + (this[key]?.image?.size ?: 0L) }

/** "640 KB", "12.4 MB", "1.2 GB". */
fun formatBytes(bytes: Long): String {
  fun tenths(unit: Long) = (bytes * 10 + unit / 2) / unit
  return when {
    bytes >= GB -> tenths(GB).let { "${it / 10}.${it % 10} GB" }
    bytes >= MB -> tenths(MB).let { "${it / 10}.${it % 10} MB" }
    else -> "${(bytes + KB / 2) / KB} KB"
  }
}

private const val KB = 1024L
private const val MB = KB * 1024
private const val GB = MB * 1024
