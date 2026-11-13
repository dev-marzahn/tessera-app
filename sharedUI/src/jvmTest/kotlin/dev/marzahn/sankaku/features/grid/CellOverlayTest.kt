package dev.marzahn.sankaku.features.grid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.ui.openOnClick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CellOverlayTest {
  @Test
  fun onlyFavouritesSearchesDimUnfavouritedPosts() {
    assertTrue(isFavouritesSearch(listOf("fav:someone", "rating:s")))
    assertFalse(isFavouritesSearch(listOf("blue_hair", "-fav:someone")))
    assertFalse(isFavouritesSearch(listOf("blue_hair", "order:popularity")))
  }

  @Test
  fun anUnfavouritedPostIsDimmedWithUndoThatFavouritesItAgain() = runComposeUiTest {
    var favourited by mutableStateOf(true)
    var overridden by mutableStateOf(false)
    setContent {
      // As the grid derives it: unfavourited once the override says not favourited.
      CellOverlay(
        favourited = favourited,
        unfavourited = overridden && !favourited,
        hovered = false,
        onToggleFavourite = { favourited = !favourited; overridden = true },
        modifier = Modifier.size(200.dp),
      )
    }

    // Favourited: the badge, no Undo.
    onNodeWithContentDescription("Favourited").assertIsDisplayed()
    onNodeWithText("Undo").assertDoesNotExist()

    // Unfavourited elsewhere (the viewer): dimmed in place, with Undo.
    favourited = false
    overridden = true
    onNodeWithText("Unfavourited ·").assertIsDisplayed()
    onNodeWithContentDescription("Favourited").assertDoesNotExist()

    onNodeWithText("Undo").performClick()
    assertTrue(favourited)
    onNodeWithText("Undo").assertDoesNotExist()
    onNodeWithContentDescription("Favourited").assertIsDisplayed()
  }

  @Test
  fun theFavouriteButtonIsThereOnlyWhileHovered() = runComposeUiTest {
    var toggles = 0
    var opened = 0
    setContent {
      val hover = remember { MutableInteractionSource() }
      val hovered by hover.collectIsHoveredAsState()
      // As in the grid: the cell opens on click, the button inside takes its own clicks.
      Box(Modifier.size(200.dp).testTag("cell").hoverable(hover).openOnClick(onOpen = { opened++ }, onOpenInNewTab = {})) {
        CellOverlay(favourited = true, unfavourited = false, hovered = hovered, onToggleFavourite = { toggles++ })
      }
    }

    onNodeWithContentDescription("Unfavourite").assertDoesNotExist()
    onNodeWithTag("cell").performMouseInput { moveTo(center) }
    onNodeWithContentDescription("Unfavourite").assertIsDisplayed().performClick()
    assertEquals(1, toggles)
    assertEquals(0, opened)

    onNodeWithTag("cell").performMouseInput { exit() }
    onNodeWithContentDescription("Unfavourite").assertDoesNotExist()
    onNodeWithContentDescription("Favourited").assertIsDisplayed()
  }

  @Test
  fun aPostThatIsNotFavouritedShowsItsHeartOnlyWhileHovered() = runComposeUiTest {
    var favourited by mutableStateOf(false)
    setContent {
      val hover = remember { MutableInteractionSource() }
      val hovered by hover.collectIsHoveredAsState()
      Box(Modifier.size(200.dp).testTag("cell").hoverable(hover)) {
        CellOverlay(
          favourited = favourited,
          unfavourited = false,
          hovered = hovered,
          onToggleFavourite = { favourited = !favourited },
        )
      }
    }

    onNodeWithContentDescription("Favourite").assertDoesNotExist()
    onNodeWithTag("cell").performMouseInput { moveTo(center) }
    onNodeWithContentDescription("Favourite").assertIsDisplayed().performClick()
    assertTrue(favourited)

    // The same element, now the favourited post's button: one heart, never a badge beside a button.
    onNodeWithContentDescription("Unfavourite").assertIsDisplayed()
    onNodeWithContentDescription("Favourited").assertDoesNotExist()
  }
}
