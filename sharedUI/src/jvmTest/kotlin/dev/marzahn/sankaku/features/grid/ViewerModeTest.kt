package dev.marzahn.sankaku.features.grid

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewerModeTest {
  /** #538: where the viewer's count pill may offer the swipe tip, "Swipe sideways for the next item in your queue." */
  @Test
  fun onlyALonePostsSidewaysSwipeMovesBetweenQueueItems() {
    assertTrue(ViewerMode.Tab.swipesQueue, "a kept post: the swipe moves on in the queue")
    assertFalse(ViewerMode.Overlay.swipesQueue, "over a grid: the swipe steps to the grid's next post")
  }
}
