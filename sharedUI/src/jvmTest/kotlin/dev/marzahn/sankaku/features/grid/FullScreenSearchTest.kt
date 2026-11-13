package dev.marzahn.sankaku.features.grid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.input.ImeAction
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
  fun theActionKeyIsTheKeyboardsSearchKey() = runComposeUiTest {
    showSearch()

    field.assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Search))
  }

  @Test
  fun theActionKeyOnPartialTextAddsItPastTheSuggestionsAndDoesntSearch() = runComposeUiTest {
    showSearch()

    // A touch screen highlights no suggestion (#73), so the action key adds what's typed, not the top suggestion;
    // "blue ha" isn't a suggested tag, so the draft is still being built (#506).
    typeAndWaitForSuggestions("blue ha")
    field.performImeAction()

    onNodeWithContentDescription("Remove blue_ha", useUnmergedTree = true).assertExists()
    assertEquals(listOf("blue_ha"), asked)
    assertEquals(emptyList(), submitted)
  }

  @Test
  fun theActionKeyOnATypedTagAddsItAndSearches() = runComposeUiTest {
    showSearch(initial = listOf("smile"))

    // What's typed is a suggested tag's name, spaces as they read: one finished tag (#506).
    typeAndWaitForSuggestions("blue hair")
    field.performImeAction()

    assertEquals(listOf(listOf("smile", "blue_hair")), submitted)
  }

  @Test
  fun theActionKeyBeforeSuggestionsArriveAddsWhatsTypedAndDoesntSearch() = runComposeUiTest {
    showSearch()
    waitForIdle()
    mainClock.autoAdvance = false

    // No suggestion has said "blue hair" is a tag yet.
    "blue hair".forEach { field.performTextInput(it.toString()) }
    field.performImeAction()
    mainClock.advanceTimeBy(SUGGEST_DELAY_MS)

    onNodeWithContentDescription("Remove blue_hair", useUnmergedTree = true).assertExists()
    assertEquals(emptyList(), submitted)
  }

  @Test
  fun theActionKeyOnAnEmptyInputRunsTheDraft() = runComposeUiTest {
    showSearch()

    typeAndWaitForSuggestions("blue ha")
    field.performImeAction()
    field.performImeAction()

    assertEquals(listOf(listOf("blue_ha")), submitted)
  }

  @Test
  fun theActionKeyWithNoDraftOnlyHidesTheKeyboard() = runComposeUiTest {
    showSearch()

    field.performImeAction()

    assertEquals(emptyList(), submitted)
  }

  @Test
  fun aSecondWordFromTheKeyboardStaysInTheInput() = runComposeUiTest {
    showSearch()

    // Glide typing, or a suggestion from the keyboard, puts a whole word in at once, with its space (#506).
    field.performTextInput("blue")
    field.performTextInput(" hair")
    waitUntil { asked.isNotEmpty() }

    field.assert(hasText("blue hair"))
    assertEquals(listOf("blue_hair"), asked)
    onNodeWithContentDescription("Remove blue_hair", useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun aPastedSearchSplitsIntoTags() = runComposeUiTest {
    showSearch()

    field.performTextInput("blue_hair smile")
    onNodeWithText("Search").performClick()

    assertEquals(listOf(listOf("blue_hair", "smile")), submitted)
    assertEquals(emptyList(), asked)
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
