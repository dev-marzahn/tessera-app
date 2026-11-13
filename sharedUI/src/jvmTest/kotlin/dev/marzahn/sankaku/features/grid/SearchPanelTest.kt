package dev.marzahn.sankaku.features.grid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.marzahn.sankaku.api.dto.TagSuggestion
import dev.marzahn.sankaku.data.RecentSearches
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SearchPanelTest {
  private val asked = mutableListOf<String>()
  private val submitted = mutableListOf<List<String>>()
  private val recent = RecentSearches()

  private fun ComposeUiTest.showPanel(
    initial: List<String> = emptyList(),
    signedIn: Boolean = true,
    safeOnly: Boolean = false,
    suggestions: List<TagSuggestion> = listOf(TagSuggestion("blue_hair", postCount = 1234), TagSuggestion("blue_hat", type = 10)),
  ) = setContent {
    SearchPanel(
      initial = initial,
      signedIn = signedIn,
      safeOnly = safeOnly,
      recent = recent,
      suggest = { prefix ->
        asked += prefix
        // As autocomplete does, only the tags whose name holds what's typed.
        suggestions.filter { prefix.removePrefix("-") in it.tagName }
      },
      onSubmit = { submitted += it },
      onClose = {},
    )
  }

  private val ComposeUiTest.field get() = onNode(hasSetTextAction())

  @Test
  fun typingAsksOnceThenPickingAddingSafeAndEnterRunsOneSearch() = runComposeUiTest {
    showPanel()

    "blue_ha".forEach { field.performTextInput(it.toString()) }
    // A suggestion whose name is only its key reads as a post's tag chip does: spaces for underscores (#246).
    waitUntil { onAllNodes(hasText("blue hat")).fetchSemanticsNodes().isNotEmpty() }
    field.performKeyInput { pressKey(Key.Tab) }
    onNodeWithText("Rating").performClick()
    onNodeWithText("General").performClick()
    // The menu stays open for more ticks; a click outside closes it and gives the input its focus back.
    field.performClick()
    onNodeWithText("General").assertDoesNotExist()
    field.assertIsFocused()
    field.performKeyInput { pressKey(Key.Enter) }

    assertEquals(listOf("blue_ha"), asked)
    assertEquals(listOf(listOf("rating:s", "blue_hair")), submitted)
    assertEquals(listOf(listOf("rating:s", "blue_hair")), recent.searches.value)
  }

  @Test
  fun typingAtAnEverydayPaceAsksOnlyAfterAPause() = runComposeUiTest {
    mainClock.autoAdvance = false
    showPanel()

    // 200 ms between keys, quicker than the wait: nothing is asked until the typing stops.
    "blue hair".forEach {
      field.performTextInput(it.toString())
      mainClock.advanceTimeBy(200)
    }
    mainClock.advanceTimeBy(SUGGEST_DELAY_MS)

    assertEquals(listOf("blue_hair"), asked)
  }

  @Test
  fun aTypedSpaceStaysAndAsksWithAnUnderscore() = runComposeUiTest {
    showPanel()

    "blue ha".forEach { field.performTextInput(it.toString()) }
    waitUntil { asked.isNotEmpty() }
    // Shift+Enter adds what's typed, past the highlighted suggestion; Enter on the empty input then searches.
    field.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
    field.performKeyInput { pressKey(Key.Enter) }

    assertEquals(listOf("blue_ha"), asked)
    assertEquals(listOf(listOf("blue_ha")), submitted)
  }

  private val scenery = listOf(
    TagSuggestion("landscape_scenery", rating = "s"),
    TagSuggestion("underwater_scenery", rating = "e"),
    TagSuggestion("scenery_censor", rating = "q"),
    TagSuggestion("night_scenery"),
  )
  // As the rows read them: a name that is only its key shows with spaces (#246).
  private val hiddenByGOnly = listOf("underwater scenery", "scenery censor")

  @Test
  fun gOnlyLeavesOutR15AndR18TagsYetATagTypedInFullStillRuns() = runComposeUiTest {
    // An unrated tag isn't R15 or R18 as far as the panel knows, so it's still offered.
    showPanel(safeOnly = true, suggestions = scenery)

    "scenery".forEach { field.performTextInput(it.toString()) }
    waitUntil { onAllNodes(hasText("landscape scenery")).fetchSemanticsNodes().isNotEmpty() }
    hiddenByGOnly.forEach { onNodeWithText(it).assertDoesNotExist() }
    onNodeWithText("night scenery").assertExists()
    field.performKeyInput { pressKey(Key.Enter) }

    // Typed in full, a tag the panel doesn't offer is still added and searched, once its (filtered) suggestions are in.
    "underwater_scenery".forEach { field.performTextInput(it.toString()) }
    waitUntil { "underwater_scenery" in asked }
    waitForIdle()
    field.performKeyInput { pressKey(Key.Enter) }
    field.performKeyInput { pressKey(Key.Enter) }

    assertEquals(listOf(listOf("landscape_scenery", "underwater_scenery")), submitted)
  }

  @Test
  fun withGOnlyOffEveryRatingIsListed() = runComposeUiTest {
    showPanel(suggestions = scenery)

    "scenery".forEach { field.performTextInput(it.toString()) }
    waitUntil { onAllNodes(hasText("landscape scenery")).fetchSemanticsNodes().isNotEmpty() }
    hiddenByGOnly.forEach { onNodeWithText(it).assertExists() }
  }

  @Test
  fun signedOutWithGOnlyOffEveryRatingIsListedAsBefore() = runComposeUiTest {
    showPanel(signedIn = false, suggestions = scenery)

    "scenery".forEach { field.performTextInput(it.toString()) }
    waitUntil { onAllNodes(hasText("landscape scenery")).fetchSemanticsNodes().isNotEmpty() }
    hiddenByGOnly.forEach { onNodeWithText(it).assertExists() }
  }

  private fun ComposeUiTest.typeAndWaitForSuggestions(text: String) {
    text.forEach { field.performTextInput(it.toString()) }
    // A suggestion whose name is only its key reads as a post's tag chip does: spaces for underscores (#246).
    waitUntil { onAllNodes(hasText("blue hat")).fetchSemanticsNodes().isNotEmpty() }
  }

  @Test
  fun enterAddsTheHighlightedFirstSuggestionWithoutSearching() = runComposeUiTest {
    showPanel()

    typeAndWaitForSuggestions("blue")
    field.performKeyInput { pressKey(Key.Enter) }

    assertEquals(emptyList(), submitted)
    field.performKeyInput { pressKey(Key.Enter) }
    assertEquals(listOf(listOf("blue_hair")), submitted)
  }

  @Test
  fun downMovesStraightToTheSecondSuggestion() = runComposeUiTest {
    showPanel()

    typeAndWaitForSuggestions("blue")
    field.performKeyInput { pressKey(Key.DirectionDown) }
    field.performKeyInput { pressKey(Key.Enter) }
    field.performKeyInput { pressKey(Key.Enter) }

    assertEquals(listOf(listOf("blue_hat")), submitted)
  }

  @Test
  fun upFromTheFirstSuggestionGoesBackToWhatIsTyped() = runComposeUiTest {
    showPanel()

    typeAndWaitForSuggestions("blue")
    field.performKeyInput { pressKey(Key.DirectionUp) }
    field.performKeyInput { pressKey(Key.Enter) }
    field.performKeyInput { pressKey(Key.Enter) }

    assertEquals(listOf(listOf("blue")), submitted)
  }

  @Test
  fun cmdEnterAddsTheHighlightedSuggestionAndSearches() = runComposeUiTest {
    showPanel()

    typeAndWaitForSuggestions("blue")
    field.performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Enter) } }

    assertEquals(listOf(listOf("blue_hair")), submitted)
  }

  @Test
  fun enterOnAnEmptyInputRunsTheDraftNotARecentSearch() = runComposeUiTest {
    recent.add(listOf("older"))
    showPanel(initial = listOf("smile"))

    field.performKeyInput { pressKey(Key.Enter) }

    assertEquals(listOf(listOf("smile")), submitted)
  }

  @Test
  fun aCommaEndsATagAsTypedAndBackspaceRemovesIt() = runComposeUiTest {
    showPanel(initial = listOf("smile"))

    "a b,".forEach { field.performTextInput(it.toString()) }
    "c,".forEach { field.performTextInput(it.toString()) }
    field.performKeyInput { pressKey(Key.Backspace) }
    field.performKeyInput { pressKey(Key.Enter) }

    assertEquals(listOf(listOf("smile", "a_b")), submitted)
  }

  @Test
  fun lettersPutInAtOnceAfterATypedSpaceStayTyped() = runComposeUiTest {
    showPanel()

    // An input method's completion puts in several letters at once; the space was typed before them (#529 review).
    "blue ha".forEach { field.performTextInput(it.toString()) }
    field.performTextInput("ir")
    waitUntil { asked.isNotEmpty() }

    field.assert(hasText("blue hair"))
    assertEquals(listOf("blue_hair"), asked)
  }

  @Test
  fun aPastedSearchSplitsIntoTags() = runComposeUiTest {
    showPanel()

    field.performTextInput("blue_hair smile rating:q")
    field.performKeyInput { pressKey(Key.Enter) }

    assertEquals(listOf(listOf("rating:q", "blue_hair", "smile")), submitted)
    assertEquals(emptyList(), asked)
  }

  @Test
  fun signedOutTheRatingIsGAndCannotBeChanged() = runComposeUiTest {
    showPanel(initial = listOf("blue_hair", "rating:q"), signedIn = false)

    onNodeWithText("G").assertIsNotEnabled()
    field.performKeyInput { pressKey(Key.Enter) }

    assertEquals(listOf(listOf("blue_hair")), submitted)
  }

  @Test
  fun theInputKeepsItsHeightWhenATagIsAdded() = runComposeUiTest {
    showPanel()
    val before = field.fetchSemanticsNode().boundsInRoot

    field.performTextInput("smile ")
    waitForIdle()

    assertEquals(before.height, field.fetchSemanticsNode().boundsInRoot.height)
    assertEquals(before.top, field.fetchSemanticsNode().boundsInRoot.top)
  }

  @Test
  fun anEmptyInputListsRecentSearchesAndPickingOneRunsIt() = runComposeUiTest {
    recent.add(listOf("older"))
    recent.add(listOf("blue_hair", "rating:q"))
    showPanel()

    onNodeWithText("older").performClick()

    assertEquals(listOf(listOf("older")), submitted)
  }

  @Test
  fun aSignInFinishingWhileOpenKeepsTheDraftAndTypingAndShowsTheFavouritesFilter() = runComposeUiTest {
    var me by mutableStateOf<String?>(null)
    setContent {
      SearchPanel(
        initial = listOf("blue_hair", "fav:me"),
        signedIn = me != null,
        safeOnly = false,
        recent = recent,
        suggest = { emptyList() },
        onSubmit = { submitted += it },
        onClose = {},
        me = me,
      )
    }
    onNodeWithText("Favourites").assertDoesNotExist()
    field.performTextInput("smi")

    me = "me"
    waitForIdle()

    onNodeWithText("Only my favourites").assertExists()
    onNodeWithText("smi").assertExists()
    onNodeWithText("Search").performClick()
    assertEquals(listOf(listOf("fav:me", "blue_hair", "smi")), submitted)
  }

  // The left hand's keys (#443).

  @Test
  fun optionSMovesToTheSecondSuggestionWithoutTyping() = runComposeUiTest {
    showPanel()

    typeAndWaitForSuggestions("blue")
    field.performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.S) } }
    field.performKeyInput { pressKey(Key.Tab) }
    field.performKeyInput { pressKey(Key.Enter) }

    assertEquals(listOf(listOf("blue_hat")), submitted)
  }

  @Test
  fun optionWFromTheFirstSuggestionGoesBackToWhatIsTyped() = runComposeUiTest {
    showPanel()

    typeAndWaitForSuggestions("blue")
    field.performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.W) } }
    field.performKeyInput { pressKey(Key.Enter) }
    field.performKeyInput { pressKey(Key.Enter) }

    assertEquals(listOf(listOf("blue")), submitted)
  }

  @Test
  fun shiftTabWhileTypingStepsTheHighlightBackAndKeepsTheFocus() = runComposeUiTest {
    showPanel()

    typeAndWaitForSuggestions("blue")
    field.performKeyInput { pressKey(Key.DirectionDown) }
    field.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
    field.assertIsFocused()
    field.performKeyInput { pressKey(Key.Tab) }
    field.performKeyInput { pressKey(Key.Enter) }

    assertEquals(listOf(listOf("blue_hair")), submitted)
  }

  @Test
  fun withNothingTypedTabReachesTheFilterChipsAndADMoveAlongThem() = runComposeUiTest {
    recent.add(listOf("older"))
    showPanel()

    field.performKeyInput { pressKey(Key.Tab) }
    onNodeWithText("Rating").assertIsFocused()
    onNodeWithText("Rating").performKeyInput { pressKey(Key.D) }
    onNodeWithText("Feed").assertIsFocused()
    onNodeWithText("Feed").performKeyInput { pressKey(Key.A) }
    onNodeWithText("Rating").assertIsFocused()
    // A on the first chip stays there, rather than going back up to the input and typing.
    onNodeWithText("Rating").performKeyInput { pressKey(Key.A) }
    onNodeWithText("Rating").assertIsFocused()
  }

  @Test
  fun spaceOpensAChipOnItsChosenItemAndTabChoosesTheNextOne() = runComposeUiTest {
    showPanel()

    field.performKeyInput { pressKey(Key.Tab) }
    onNodeWithText("Rating").performKeyInput { pressKey(Key.D) }
    onNodeWithText("Feed").performKeyInput { pressKey(Key.D) }
    onNodeWithText("Order").performKeyInput { pressKey(Key.Spacebar) }
    onNodeWithText("Newest").assertIsFocused()
    onNodeWithText("Newest").performKeyInput { pressKey(Key.S) }
    onNodeWithText("Popularity").performKeyInput { pressKey(Key.Tab) }
    // Chosen, the input has the focus back, so Enter runs the search.
    field.assertIsFocused()
    field.performKeyInput { pressKey(Key.Enter) }

    assertEquals(listOf(listOf("order:popularity")), submitted)
  }

  @Test
  fun escClosesAFilterMenuBackToItsChipSoDGoesOnAlongTheChips() = runComposeUiTest {
    showPanel()

    field.performKeyInput { pressKey(Key.Tab) }
    onNodeWithText("Rating").performKeyInput { pressKey(Key.Spacebar) }
    onNodeWithText("All ratings").assertIsFocused()
    onNodeWithText("All ratings").performKeyInput { pressKey(Key.Escape) }
    onNodeWithText("All ratings").assertDoesNotExist()
    onNodeWithText("Rating").assertIsFocused()
    onNodeWithText("Rating").performKeyInput { pressKey(Key.D) }
    onNodeWithText("Feed").performKeyInput { pressKey(Key.Spacebar) }
    onNodeWithText("All posts").performKeyInput { pressKey(Key.Escape) }
    onNodeWithText("Feed").assertIsFocused()
    assertEquals(emptyList(), submitted)
  }

  @Test
  fun theScoreMenusStarsMoveWithADAndTabPicksOne()= runComposeUiTest {
    showPanel()

    field.performKeyInput { pressKey(Key.Tab) }
    listOf("Rating", "Feed", "Order").forEach { onNodeWithText(it).performKeyInput { pressKey(Key.D) } }
    onNode(hasContentDescription("Minimum score")).performKeyInput { pressKey(Key.Spacebar) }
    onNodeWithText("Any score").assertIsFocused()
    onNodeWithText("Any score").performKeyInput { pressKey(Key.S) }
    // From wherever S lands in the row, A four times reaches the first star; D then moves on one.
    repeat(4) { onNode(hasContentDescription("At least 1")).performKeyInput { pressKey(Key.A) } }
    onNode(hasContentDescription("At least 1")).assertIsFocused()
    onNode(hasContentDescription("At least 1")).performKeyInput { pressKey(Key.D) }
    onNode(hasContentDescription("At least 2")).assertIsFocused()
    onNode(hasContentDescription("At least 2")).performKeyInput { pressKey(Key.Tab) }
    field.performKeyInput { pressKey(Key.Enter) }

    assertEquals(listOf(listOf("threshold:2")), submitted)
  }

  private fun hasText(text: String) = androidx.compose.ui.test.hasText(text)
  private fun hasContentDescription(text: String) = androidx.compose.ui.test.hasContentDescription(text)
}
