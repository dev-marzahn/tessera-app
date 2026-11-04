package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.features.grid.Rating
import dev.marzahn.sankaku.images.PostImage
import kotlinx.serialization.Serializable

/**
 * A thumbnail of a grid's mosaic on the queue page, as `tabs.json` keeps it (#232): the post's [md5], which finds its
 * preview in the image cache, and its [rating] code (`s`, `q`, `e`), so an R15 or R18 thumbnail stays blurred. A
 * thumbnail with no known rating is blurred like an R15 one. Never logged.
 */
@Serializable
data class QueueThumb(val md5: String, val rating: String? = null) {
  /** Whether it may show unblurred: its post is rated G. */
  val isG: Boolean get() = Rating.ofCode(rating) == Rating.G

  /** Whether it blurs: its post is R15 or R18, or its rating isn't known. */
  val isAdult: Boolean get() = !isG

  /** Never the md5, should one end up in a log or an error message. */
  override fun toString() = "QueueThumb(rating=$rating)"

  /**
   * Its preview, for a cache-only request ([dev.marzahn.sankaku.images.cachedPostImageRequest]) only: it has no id or
   * URL, so it can never be fetched, and a miss leaves a blank tile.
   */
  val image: PostImage get() = PostImage(postId = "", md5 = md5, variant = Variant.Preview, url = null)
}

/**
 * What the queue page shows for a grid (#232): its [thumbs] (up to four) from where it was last scrolled to, and how
 * many [posts] it has loaded. The grid reports it as it changes ([TabState.reportMosaic]) and it is saved with the tab,
 * so a source restored at launch shows it with no request.
 */
data class QueueMosaic(val thumbs: List<QueueThumb>, val posts: Int) {
  companion object {
    private const val SIZE = 4

    /** A grid's [loaded] posts, from [firstShown], the first one it shows; null before it has loaded any. */
    fun of(loaded: List<PostDto>, firstShown: Int): QueueMosaic? {
      if (loaded.isEmpty()) return null
      val thumbs = shownFrom(loaded, firstShown, SIZE).map { QueueThumb(it.md5, Rating.ofCode(it.rating)?.code) }
      return QueueMosaic(thumbs, loaded.size)
    }

    /** As `tabs.json` saved it ([SavedTab.mosaic], [SavedTab.posts]): null unless it has posts. */
    fun restored(thumbs: List<QueueThumb>?, posts: Int?): QueueMosaic? =
      posts?.takeIf { it > 0 }?.let { QueueMosaic(thumbs.orEmpty().take(SIZE), it) }
  }
}

/**
 * The [count] posts a grid previews, from [firstShown] (moved back so there are [count] when it's near the end),
 * skipping the ones only signed-in users see.
 */
internal fun shownFrom(loaded: List<PostDto>, firstShown: Int, count: Int): List<PostDto> =
  loaded.drop(firstShown.coerceIn(0, maxOf(0, loaded.size - count))).filterNot { it.redirectToSignup }.take(count)
