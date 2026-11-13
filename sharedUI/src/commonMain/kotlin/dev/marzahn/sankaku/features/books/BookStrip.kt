package dev.marzahn.sankaku.features.books

import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.size.Size
import dev.marzahn.sankaku.api.FILE_HOST
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.BookPlace
import dev.marzahn.sankaku.data.SavedReading
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.data.url
import dev.marzahn.sankaku.features.grid.Rating
import dev.marzahn.sankaku.features.post.aspectRatio
import dev.marzahn.sankaku.features.post.isAnimated
import dev.marzahn.sankaku.features.post.isFlash
import dev.marzahn.sankaku.features.post.isVideo
import dev.marzahn.sankaku.features.post.previewRequest
import dev.marzahn.sankaku.features.post.sampleRequest
import dev.marzahn.sankaku.features.post.sampleVariant
import io.ktor.http.Url
import kotlin.math.roundToInt

/** How many pages after the current one load ahead, once it has loaded (#377 decision 2). */
internal const val PRELOAD_PAGES = 3

/** The pages that load ahead of [current] in a book of [count] pages: the next [PRELOAD_PAGES], as far as there are. */
internal fun preloadedPages(current: Int, count: Int): IntRange = (current + 1)..minOf(current + PRELOAD_PAGES, count - 1)

/** Where page [index] is laid out in the strip's viewport: [top] and [height] in px. */
internal data class PageSpan(val index: Int, val top: Int, val height: Int)

/**
 * The page taking most of the viewport from [viewportStart] to [viewportEnd], of the laid-out [pages]. The earlier
 * page wins a tie; with none laid out, page 0.
 */
internal fun mostShownPage(pages: List<PageSpan>, viewportStart: Int, viewportEnd: Int): Int {
  var best = 0
  var bestShown = -1
  for ((index, top, height) in pages) {
    val shown = minOf(top + height, viewportEnd) - maxOf(top, viewportStart)
    if (shown > bestShown) {
      best = index
      bestShown = shown
    }
  }
  return best
}

/** With G only on, a page of another rating shows as a "Hidden by G only" band (#377 decision 14). */
internal fun hiddenByGOnly(page: PostDto, gOnly: Boolean): Boolean = gOnly && Rating.ofCode(page.rating) != Rating.G

/**
 * The strip's page for a saved [sequence]: the last page whose `sequence` is at most it (a page without one counts
 * as its index), so a saved page this strip lacks opens just before where it was. 0 when nothing is saved.
 */
internal fun savedPageIndex(pages: List<PostDto>, sequence: Int?): Int {
  if (sequence == null) return 0
  var found = 0
  pages.forEachIndexed { index, page -> if ((page.sequence ?: index) <= sequence) found = index }
  return found
}

/**
 * The strip's page a book opens at (#503): its [saved] place ([savedPageIndex]), or page 1 with none or once it was
 * read, so a finished book is read again from the start.
 */
internal fun openingPageIndex(pages: List<PostDto>, saved: SavedReading?): Int =
  if (saved == null || saved.finished) 0 else savedPageIndex(pages, saved.sequence)

/** Where the strip is at page [index] of [pages], to save (#384): the page's own `sequence`, which the API takes. */
internal fun bookPlace(pages: List<PostDto>, index: Int): BookPlace =
  BookPlace(sequence = pages.getOrNull(index)?.sequence ?: index, page = index + 1, pages = pages.size)

/** The chip's place: "Page 12", where the book opened. */
internal fun resumeLabel(page: Int): String = "Page $page"

internal const val START_OVER = "Start over"

/** The top row's page count (#484): the current page, from 1, of [count]. */
internal fun pageLabel(current: Int, count: Int): String = "page ${current + 1} / $count"

/**
 * Whether [page] shown [shownWidth] px wide is past its sample's resolution, so its full file loads: only a still
 * image with a full file wider than its sample (#377 decision 2).
 */
internal fun needsFullFile(page: PostDto, shownWidth: Float): Boolean {
  if (isVideo(page) || isAnimated(page) || isFlash(page) || sampleVariant(page) == Variant.File) return false
  val fileWidth = page.fileWidth ?: return false
  val sampleWidth = page.sampleWidth ?: return false
  return fileWidth > sampleWidth && shownWidth > sampleWidth
}

/** A page's size in the strip: [width] px wide, as tall as its aspect ratio makes it. */
internal fun pageSize(page: PostDto, width: Int): Size? {
  if (width <= 0) return null
  return Size(width, (width / aspectRatio(page)).roundToInt().coerceAtLeast(1))
}

/**
 * What a page of the strip requests first, the same request as it draws, so a preload fills the entry the page then
 * reads: a still image's sample at the strip's width, or for a video, a GIF or Flash the preview it shows until it
 * plays. Null for a page that shows no picture (G only, or one that needs signing in).
 */
internal fun pageRequest(context: PlatformContext, page: PostDto, width: Int, gOnly: Boolean): ImageRequest? {
  if (page.redirectToSignup || hiddenByGOnly(page, gOnly)) return null
  val size = pageSize(page, width)
  return if (showsPreviewFirst(page)) previewRequest(context, page, size) else sampleRequest(context, page, size)
}

/** A video, a GIF or Flash: the page shows its preview until it plays or explains itself. */
private fun showsPreviewFirst(page: PostDto) = isVideo(page) || isAnimated(page) || isFlash(page)

/**
 * What a still page shows under its sample until the sample has loaded: its preview, from the CDN, when the sample is
 * the file on [FILE_HOST] (no separate sample), whose connects can stall for seconds (`docs/api.md` → Quirks). Null
 * otherwise: a sample on the CDN loads about as fast as its preview would.
 */
internal fun pagePreviewRequest(context: PlatformContext, page: PostDto, width: Int): ImageRequest? {
  if (showsPreviewFirst(page)) return null
  val sample = page.url(sampleVariant(page)) ?: return null
  if (runCatching { Url(sample).host }.getOrNull() != FILE_HOST) return null
  return previewRequest(context, page, pageSize(page, width))
}
