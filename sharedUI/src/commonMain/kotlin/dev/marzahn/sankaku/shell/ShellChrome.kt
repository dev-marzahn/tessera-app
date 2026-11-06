package dev.marzahn.sankaku.shell

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Touch: where a viewer over a grid says whether it covers the shell's chrome (the top bar and the count pill). It
 * draws over the top bar and draws the pill itself, over its picture (#178), so the shell hides its pill and takes the
 * top bar out of the accessibility tree while it's covered. The grid provides it and keeps the answer on its tab
 * ([dev.marzahn.sankaku.tabs.TabState.coverChrome]), where the shell reads it: a grid shown again with its viewer open
 * is covered from its first frame, and one left behind no longer is, without waiting a frame for an effect (#287).
 * Outside a grid, nothing listens. Desktop never covers it.
 */
val LocalCoverChrome = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

/**
 * Whether this viewer covers the shell's chrome ([LocalCoverChrome]). Leaving composition doesn't uncover it: the
 * viewer of a grid under the queue page is still open, and the grid uncovers it once its viewer has closed.
 */
@Composable
fun CoverChrome(enabled: Boolean) {
  val report = LocalCoverChrome.current
  SideEffect { report(enabled) }
}

/**
 * Touch: the room the shell's chrome takes at the window's edges (the status bar and top bar above, the navigation bar
 * below). The tab's content fills the whole window so a viewer can cover the top bar; everything else keeps out of
 * this room. Zero on desktop, where the content is laid out below the top bar.
 */
val LocalChromePadding = compositionLocalOf { PaddingValues() }

/**
 * Touch: how far down, in px, the count pill over a grid has slid right now to hide on scroll, so the grid's ↑ Top,
 * on the same line in the right corner, slides with it. Read it in placement, not composition. Zero while a post
 * covers the grid and on desktop. (Named for the tab bar the pill replaced, #178.)
 */
val LocalTabBarSlide = staticCompositionLocalOf<() -> Float> { { 0f } }
