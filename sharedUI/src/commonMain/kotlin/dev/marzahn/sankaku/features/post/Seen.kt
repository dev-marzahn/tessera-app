package dev.marzahn.sankaku.features.post

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.queue.Viewing
import dev.marzahn.sankaku.tabs.TabsController
import org.koin.compose.koinInject

/**
 * What acting on a post in the viewer or on its own does to the queue (#174): the post is seen, and the tab it's in is
 * touched. [PostActions] calls it for ♡ (not from the grid), a vote and a tag action.
 */
fun actedOn(seen: SeenStore, tabs: TabsController): (PostDto) -> Unit = { post ->
  seen.record(post.id, post.md5)
  tabs.touchActive()
}

/**
 * The [Viewing] of a viewer or post tab showing [current] as its settled post: moving on from it once it was fully
 * shown makes it seen, and so does this leaving the composition (the viewer closing, its tab going out of view). The
 * screen reports each page whose image has loaded with [Viewing.loaded].
 */
@Composable
fun rememberViewing(current: PostDto?, seen: SeenStore = koinInject()): Viewing {
  val viewing = remember(seen) { Viewing(seen) }
  SideEffect { viewing.current(current?.id, current?.md5) }
  DisposableEffect(viewing) { onDispose { viewing.leave() } }
  return viewing
}
