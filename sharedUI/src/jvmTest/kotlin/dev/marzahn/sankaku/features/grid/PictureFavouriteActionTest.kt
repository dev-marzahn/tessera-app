package dev.marzahn.sankaku.features.grid

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.api.dto.PostDto
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #527: the phone viewer's picture has TalkBack's and Switch Access's Favourite, the long-press's, next to the sheet's
 * Expand (#494), and only while the post isn't a favourite. The picture here carries the actions as [PhoneViewer]'s
 * page gives them to it.
 */
@OptIn(ExperimentalTestApi::class)
class PictureFavouriteActionTest {
  private var post by mutableStateOf(PostDto(id = "p1", md5 = "0123456789abcdef0123456789abcdef", rating = "s", status = "active"))
  private var favourited = 0

  private fun ComposeUiTest.start() {
    setContent {
      val scope = rememberCoroutineScope()
      val sheet = remember { ViewerSheetState(scope, tween(100), flingThreshold = 1200f) }
      LaunchedEffect(Unit) { sheet.update(SheetStops(half = 300f, full = 600f)) }
      val actions = sheetActions(sheet) + pictureFavouriteAction(post) {
        favourited++
        post = post.copy(isFavorited = true)
      }
      Box(Modifier.testTag("picture").size(400.dp, 400.dp).semantics { customActions = actions })
    }
    waitForIdle()
  }

  private fun ComposeUiTest.labels(): List<String> =
    onNodeWithTag("picture").fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

  @Test
  fun favouriteSitsNextToExpandAndFavourites() = runComposeUiTest {
    start()
    assertEquals(listOf("Expand details", FAVOURITE_ACTION), labels())

    onNodeWithTag("picture").performCustomAccessibilityActionWithLabel(FAVOURITE_ACTION)
    waitForIdle()

    assertEquals(1, favourited)
    // A favourite now: unfavouriting stays on the sheet's ♡.
    assertEquals(listOf("Expand details"), labels())
  }

  @Test
  fun aFavouriteHasNoFavouriteAction() = runComposeUiTest {
    post = post.copy(isFavorited = true)
    start()
    assertEquals(listOf("Expand details"), labels())
  }
}
