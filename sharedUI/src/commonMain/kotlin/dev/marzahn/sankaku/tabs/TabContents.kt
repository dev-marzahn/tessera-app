package dev.marzahn.sankaku.tabs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.images.PostImage

/**
 * What a viewed screen holds, for its tab's hover preview: [preview] are the images to show (from the image cache
 * only), [images] every image it may hold decoded, and [posts] how many posts it has loaded. The queue page's mosaic
 * is [QueueMosaic], which the grid reports apart from this.
 */
class TabContents(
  val preview: List<PostImage>,
  val images: List<PostImage>,
  val posts: Int,
) {
  companion object {
    private const val GRID_PREVIEW_COUNT = 6

    /** A grid's [loaded] posts, previewed from [firstShown], the first one it showed last. */
    fun grid(loaded: List<PostDto>, firstShown: Int): TabContents = TabContents(
      preview = shownFrom(loaded, firstShown, GRID_PREVIEW_COUNT).map { PostImage.of(it, Variant.Preview) },
      images = loaded.flatMap { post -> Variant.entries.map { PostImage.of(post, it) } },
      posts = loaded.size,
    )

    /** A post screen, once its md5 is known (from the route, or the loaded post for a parent link). */
    fun post(id: String, md5: String?): TabContents {
      if (md5 == null) return TabContents(emptyList(), emptyList(), 1)
      return TabContents(
        preview = listOf(PostImage(id, md5, Variant.Preview, null)),
        images = Variant.entries.map { PostImage(id, md5, it, null) },
        posts = 1,
      )
    }
  }
}

/** The back stack entry being composed and its tab; null outside a tab (Android). */
class TabEntry(val tab: TabState, val contentKey: Any)

val LocalTabEntry = staticCompositionLocalOf<TabEntry?> { null }

/**
 * Tells this screen's tab what it holds, for the hover preview. [describe] runs when the preview opens, so it reads
 * the screen's state as last shown, even with the tab in the background.
 */
@Composable
fun DescribeTab(route: Route, describe: () -> TabContents) {
  val entry = LocalTabEntry.current ?: return
  SideEffect { entry.tab.describe(entry.contentKey, route, describe) }
}
