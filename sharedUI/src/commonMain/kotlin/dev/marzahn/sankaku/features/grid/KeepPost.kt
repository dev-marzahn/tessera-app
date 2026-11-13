package dev.marzahn.sankaku.features.grid

import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.PostTitle
import dev.marzahn.sankaku.existingPosition
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.tabs.TabsController
import dev.marzahn.sankaku.ui.components.KEPT_AGAIN
import dev.marzahn.sankaku.ui.components.alreadySpoken

/** The phone viewer's ⋮ entry that keeps the post shown (#504). */
internal const val KEEP_FOR_LATER = "Keep for later"

/**
 * What keeping a post did: kept now at [position] in the queue (as the pill counts), or [already] there, opening
 * nothing; [again] when it had been seen since it was kept, so keeping it made it unseen (#182).
 */
internal data class Kept(val position: Int, val already: Boolean, val again: Boolean = false)

/**
 * Keeps [post] from the grid on [searchTags], as a long-press, middle-click or E on its cell does and, since #504, the
 * viewer's Keep: a lone post at the end of the grid's block, in the background ([Navigator.openInNewTab]).
 */
internal fun keepPost(post: PostDto, searchTags: List<String>, navigator: Navigator, tabs: TabsController, seen: SeenStore): Kept {
  // Asked first: opening it again keeps it anew, which makes it unseen (#182).
  val keptSeen = tabs.keptPostSeen(post.id, seen)
  val route = Route.Post(post.id, post.md5, searchTags, PostTitle.of(post), post.rating)
  navigator.openInNewTab(route)?.let { return Kept(it, already = true, again = keptSeen) }
  val index = checkNotNull(tabs.indexOfLone(route)) { "a kept post isn't in the queue" }
  return Kept(existingPosition(tabs.tabs.value, index), already = false)
}

/**
 * The words for [kept] where no cell shows its chip (the viewer, #504): "Kept · item N" on a phone, which has no tabs
 * (#249), "Kept in tab N" on desktop; "Already item N" ("Already in tab N"), or "Kept again", as a cell's chip says.
 */
internal fun keptMessage(kept: Kept, touch: Boolean): String = when {
  kept.again -> KEPT_AGAIN
  kept.already -> alreadySpoken(kept.position, touch)
  touch -> "Kept · item ${kept.position}"
  else -> "Kept in tab ${kept.position}"
}
