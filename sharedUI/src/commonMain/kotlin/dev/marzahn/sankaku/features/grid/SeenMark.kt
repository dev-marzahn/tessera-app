package dev.marzahn.sankaku.features.grid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.queue.SeenStore
import dev.marzahn.sankaku.tabs.TabState
import dev.marzahn.sankaku.tabs.TabsController

/**
 * Which cells of the grid [route], shown in [tab], carry the seen mark (#182, #262): the posts kept from it and seen
 * since they were kept ([TabsController.seenFromGrid]). None while [tab] shows something else on top, or with no tab.
 * Reads the store's revision and the tabs, so the cells follow a post being seen (the viewer closing), kept or closed;
 * kept times and blocks are snapshot state, so Kept again, Mark unseen and a post leaving its block are followed too.
 */
@Composable
fun seenMark(route: Route.Grid, tab: TabState?, tabs: TabsController, seen: SeenStore): (PostDto) -> Boolean {
  val revision = seen.revision.collectAsState()
  val all = tabs.tabs.collectAsState()
  val grid = tab?.takeIf { it.top == route }
  return { post ->
    revision.value
    all.value
    grid != null && tabs.seenFromGrid(grid, post.id, seen)
  }
}
