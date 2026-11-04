package dev.marzahn.sankaku.features.grid

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.marzahn.sankaku.api.dto.TagSuggestion
import kotlin.test.Test
import kotlin.test.assertEquals

/** An empty search lists its tokens to remove, the unknown tag first and named, and offers Edit search (#135). */
@OptIn(ExperimentalTestApi::class)
class EmptySearchTest {
  private val removed = mutableListOf<String>()
  private var edits = 0

  @Test
  fun everyTokenIsAChipWhoseCrossRemovesItAndEditSearchOpensThePanel() = runComposeUiTest {
    setContent {
      EmptySearchContent(
        tokens = EmptySearchTokens(unknown = listOf("zzqq"), others = listOf("feed:following", "animated_gif")),
        known = mapOf("animated_gif" to TagSuggestion("animated_gif", name = "Animated GIF", type = 8)),
        me = null,
        onRemove = { removed += it },
        onEdit = { edits++ },
      )
    }

    onNodeWithText("No posts for this search").assertIsDisplayed()
    onNodeWithText("“zzqq” isn't a known tag").assertIsDisplayed()
    onNodeWithText("Animated GIF", useUnmergedTree = true).assertIsDisplayed()
    onNodeWithContentDescription("Remove zzqq", useUnmergedTree = true).performClick()
    onNodeWithContentDescription("Remove Following", substring = true, useUnmergedTree = true).performClick()
    onNodeWithContentDescription("Remove Animated GIF", useUnmergedTree = true).performClick()
    onNodeWithText("Edit search").performClick()

    assertEquals(listOf("zzqq", "feed:following", "animated_gif"), removed)
    assertEquals(1, edits)
  }
}
