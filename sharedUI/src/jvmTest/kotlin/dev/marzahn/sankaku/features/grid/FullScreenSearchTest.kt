package dev.marzahn.sankaku.features.grid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.marzahn.sankaku.api.dto.TagSuggestion
import dev.marzahn.sankaku.data.RecentSearches
import dev.marzahn.sankaku.ui.components.rememberFullScreenSearchState
import kotlin.test.Test
import kotlin.test.assertEquals

/** Android's full-screen search (#28); the jvm runs the same common code. */
@OptIn(ExperimentalTestApi::class)
class FullScreenSearchTest {
  private val asked = mutableListOf<String>()
  private val submitted = mutableListOf<List<String>>()
  private var closed = 0
  private val recent = RecentSearches()
  private var open by mutableStateOf(true)

  private var searched by mutableStateOf(emptyList<String>())

  private fun ComposeUiTest.showSearch(initial: List<String> = emptyList()) = setContent {
    FullScreenSearch(
      state = rememberFullScreenSearchState(),
      open = open,
      initial = searched.ifEmpty { initial },
      signedIn = true,
      safeOnly = false,
      recent = recent,
      suggest = { prefix ->
        asked += prefix
        listOf(TagSuggestion("blue_hair", postCount = 1234), TagSuggestion("blue_hat", type = 10))
      },
      onSubmit = { submitted += it },
      onClose = {
        closed++
        open = false
      },
    )
  }

  private val ComposeUiTest.field get() = onNode(hasSetTextAction())

  private fun ComposeUiTest.typeAndWaitForSuggestions(text: String) {
    text.forEach { field.performTextInput(it.toString()) }
    // A suggestion whose name is only its key reads as a post's tag chip does: spaces for underscores (#246).
    waitUntil { onAllNodes(hasText("blue hat")).fetchSemanticsNodes().isNotEmpty() }
  }

  @Test
  fun theActionKeyAddsWhatsTypedPastTheSuggestionsAndOnlySearchRuns() = runComposeUiTest {
    showSearch()

    // A touch screen highlights no suggestion (#73), so the action key adds what's typed, not the top suggestion.
    typeAndWaitForSuggestions("blue ha")
    field.performImeAction()
    onNodeWithContentDescription("Remove blue_ha", useUnmergedTree = true).assertExists()
    // An empty input only hides the keyboard.
    field.performImeAction()
    assertEquals(emptyList(), submitted)

    onNodeWithText("Search").performClick()

    assertEquals(listOf("blue_ha"), asked)
    assertEquals(listOf(listOf("blue_ha")), submitted)
  }

  @Test
  fun tappingASuggestionStillAddsIt() = runComposeUiTest {
    showSearch()

    typeAndWaitForSuggestions("blue")
    onNodeWithText("blue hat").performClick()
    onNodeWithText("Search").performClick()

    assertEquals(listOf(listOf("blue_hat")), submitted)
  }

  @Test
  fun typingAsksForSuggestionsAndNeverSearches() = runComposeUiTest {
    showSearch(initial = listOf("smile"))

    typeAndWaitForSuggestions("blue ha")

    assertEquals(listOf("blue_ha"), asked)
    assertEquals(emptyList(), submitted)
  }

  @Test
  fun theFiltersAreChipsInTheContent() = runComposeUiTest {
    showSearch()

    onNodeWithText("Order").performClick()
    onNodeWithText("Popularity").performClick()
    onNodeWithText("Search").performClick()

    assertEquals(listOf(listOf("order:popularity")), submitted)
  }

  @Test
  fun anEmptyInputListsRecentSearchesAndPickingOneRunsIt() = runComposeUiTest {
    recent.add(listOf("older"))
    showSearch()

    onNodeWithText("Recent searches").assertExists()
    onNodeWithText("older").performClick()

    assertEquals(listOf(listOf("older")), submitted)
  }

  @Test
  fun closingDropsTheDraftAndTheNextOpeningStartsFromTheSearch() = runComposeUiTest {
    showSearch(initial = listOf("smile"))

    typeAndWaitForSuggestions("blue")
    field.performImeAction()
    onNodeWithContentDescription("Close search").performClick()
    waitForIdle()
    open = true
    waitForIdle()
    onNodeWithText("Search").performClick()

    assertEquals(1, closed)
    assertEquals(listOf(listOf("smile")), submitted)
  }

  @Test
  fun aSearchChangedWhileClosedIsWhatTheNextOpeningStartsFrom() = runComposeUiTest {
    showSearch(initial = listOf("smile"))

    onNodeWithContentDescription("Close search").performClick()
    waitForIdle()
    // Back to another grid, or another tab came forward, while the search was closed.
    searched = listOf("blue_hair")
    waitForIdle()
    open = true
    waitForIdle()
    onNodeWithText("Search").performClick()

    assertEquals(listOf(listOf("blue_hair")), submitted)
  }
}
