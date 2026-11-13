package dev.marzahn.sankaku.features.grid

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.marzahn.sankaku.api.dto.TagSuggestion
import dev.marzahn.sankaku.data.RecentSearches
import kotlin.test.Test
import kotlin.test.assertEquals

/** The words that say why only G posts show, and the feeds' own empty states (#523). */
@OptIn(ExperimentalTestApi::class)
class GOnlyMessagesTest {
  private var actions = 0
  private var browsed = 0

  @Test
  fun anEmptySearchSaysItIsSignedOutAndSignInActs() = runComposeUiTest {
    setContent {
      EmptySearchContent(
        tokens = EmptySearchTokens(unknown = emptyList(), others = listOf("scenery")),
        known = emptyMap(),
        me = null,
        onRemove = {},
        onEdit = {},
        reason = GOnlyReason.SignedOut,
        onReasonAction = { actions++ },
      )
    }

    onNodeWithText("Signed out: G only").assertIsDisplayed()
    onNodeWithText("Sign in").performClick()
    assertEquals(1, actions)
  }

  @Test
  fun anEmptySearchWithTheSwitchOnOffersTurnOff() = runComposeUiTest {
    setContent {
      EmptySearchContent(
        tokens = EmptySearchTokens(unknown = emptyList(), others = listOf("scenery")),
        known = emptyMap(),
        me = null,
        onRemove = {},
        onEdit = {},
        reason = GOnlyReason.Switch,
        onReasonAction = { actions++ },
      )
    }

    onNodeWithText("G only is on").assertIsDisplayed()
    onNodeWithText("Turn off").performClick()
    assertEquals(1, actions)
  }

  @Test
  fun aHeldLockSaysSoWithNothingToTap() = runComposeUiTest {
    setContent {
      EmptySearchContent(
        tokens = EmptySearchTokens(unknown = emptyList(), others = listOf("scenery")),
        known = emptyMap(),
        me = null,
        onRemove = {},
        onEdit = {},
        reason = GOnlyReason.Held,
      )
    }

    onNodeWithText("G only is on").assertIsDisplayed()
    onNodeWithText("Turn off").assertDoesNotExist()
  }

  @Test
  fun anUnlockedEmptySearchHasNoReasonLine() = runComposeUiTest {
    setContent {
      EmptySearchContent(
        tokens = EmptySearchTokens(unknown = emptyList(), others = listOf("scenery")),
        known = emptyMap(),
        me = null,
        onRemove = {},
        onEdit = {},
      )
    }

    onNodeWithText("G only", substring = true).assertDoesNotExist()
  }

  @Test
  fun anEmptyFollowingSaysHowToFollowWithNoChipsAndBrowseOpensPopular() = runComposeUiTest {
    setContent { EmptyFeedContent(EmptyFeed.Following, reason = null, onReasonAction = {}, onBrowse = { browsed++ }) }

    onNodeWithText("Nothing in Following yet").assertIsDisplayed()
    onNodeWithText("Follow tags to see their new posts here", substring = true).assertIsDisplayed()
    onNodeWithText("No posts for this search").assertDoesNotExist()
    onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading).and(hasText("Nothing in Following yet"))).assertExists()
    onNodeWithText("Browse Popular").performClick()
    assertEquals(1, browsed)
  }

  @Test
  fun anEmptyFavouritesSaysHowToFavouriteAndWhyWhenGOnlyIsOn() = runComposeUiTest {
    setContent {
      EmptyFeedContent(EmptyFeed.Favourites, reason = GOnlyReason.Switch, onReasonAction = { actions++ }, onBrowse = {})
    }

    // G only may be hiding R15/R18 favourites, so it doesn't claim there are none (#533 review).
    onNodeWithText("No G favourites to show").assertIsDisplayed()
    onNodeWithText("No favourites yet").assertDoesNotExist()
    // Desktop: a click on ♡, or F.
    onNodeWithText("Click ♡ on a post, or press F", substring = true).assertIsDisplayed()
    onNodeWithText("Turn off").performClick()
    assertEquals(1, actions)
  }

  private val submitted = mutableListOf<List<String>>()
  private val recent = RecentSearches()
  private val ComposeUiTest.field get() = onNode(hasSetTextAction())

  private fun ComposeUiTest.showPanel(
    signedIn: Boolean,
    safeOnly: Boolean,
    suggestions: List<TagSuggestion> = emptyList(),
    known: Map<String, TagSuggestion> = emptyMap(),
  ) = setContent {
    SearchPanel(
      initial = emptyList(),
      signedIn = signedIn,
      safeOnly = safeOnly,
      recent = recent,
      suggest = { prefix -> suggestions.filter { prefix in it.tagName } },
      onSubmit = { submitted += it },
      onClose = {},
      known = known,
      onSignIn = { actions++ },
      onTurnOffGOnly = { actions++ },
    )
  }

  @Test
  fun theLockedChipSaysWhyInWordsSignedOutAndSignInActs() = runComposeUiTest {
    showPanel(signedIn = false, safeOnly = false)

    onNodeWithText("Signed out: G only").assertIsDisplayed()
    onNodeWithText("Sign in").performClick()
    assertEquals(1, actions)
  }

  @Test
  fun theLockedChipOffersTurnOffWithTheSwitchOn() = runComposeUiTest {
    showPanel(signedIn = true, safeOnly = true)

    onNodeWithText("G only is on").assertIsDisplayed()
    onNodeWithText("Turn off").performClick()
    assertEquals(1, actions)
  }

  @Test
  fun aRatingTypedWhileLockedSaysWhyItWasDropped() = runComposeUiTest {
    showPanel(signedIn = false, safeOnly = false)

    field.performTextInput("rating:e,")
    waitForIdle()

    onNodeWithText("Ratings stay G while signed out").assertIsDisplayed()
    onNodeWithText("rating:e", substring = true).assertDoesNotExist()
  }

  @Test
  fun aPastedRatingWithATagAfterItKeepsItsNoticeUntilTheNextTyping() = runComposeUiTest {
    showPanel(signedIn = true, safeOnly = true)

    // One edit, as a paste is: the comma ends the rating and "smi" stays typed.
    field.performTextInput("rating:q,smi")
    waitForIdle()
    onNodeWithText("Ratings stay G while G only is on").assertIsDisplayed()

    field.performTextInput("l")
    waitForIdle()
    onNodeWithText("Ratings stay G", substring = true).assertDoesNotExist()
  }

  @Test
  fun lockedFollowingDoesNotClaimItIsEmptyEither() = runComposeUiTest {
    setContent { EmptyFeedContent(EmptyFeed.Following, reason = GOnlyReason.Switch, onReasonAction = {}, onBrowse = {}) }

    onNodeWithText("No G posts in Following").assertIsDisplayed()
    onNodeWithText("Nothing in Following yet").assertDoesNotExist()
  }

  @Test
  fun whatACommaLeftStopsKeepingTheNoticeOnceTheInputEmpties() = runComposeUiTest {
    showPanel(signedIn = true, safeOnly = true)

    field.performTextInput("rating:q,foo")
    waitForIdle()
    field.performTextClearance()
    waitForIdle()
    // The same text typed again is new typing.
    field.performTextInput("foo")
    waitForIdle()

    onNodeWithText("Ratings stay G", substring = true).assertDoesNotExist()
  }

  @Test
  fun aRatingTypedUnlockedSaysNothing() = runComposeUiTest {
    showPanel(signedIn = true, safeOnly = false)

    field.performTextInput("rating:e,")
    waitForIdle()

    onNodeWithText("Ratings stay G", substring = true).assertDoesNotExist()
  }

  @Test
  fun suggestionsEmptiedByGOnlySayTagsAreHiddenNotThatNoneMatch() = runComposeUiTest {
    showPanel(signedIn = true, safeOnly = true, suggestions = listOf(TagSuggestion("underwater_scenery", rating = "e")))

    "underwater".forEach { field.performTextInput(it.toString()) }
    waitUntil { onAllNodes(hasText("Some tags are hidden while G only is on.")).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("No tags match", substring = true).assertDoesNotExist()
  }

  @Test
  fun recentSearchesWithAnR18TagAreLeftOutWhileGOnlyIsOn() = runComposeUiTest {
    recent.add(listOf("underwater_scenery"))
    recent.add(listOf("landscape"))
    showPanel(
      signedIn = true,
      safeOnly = true,
      known = mapOf("underwater_scenery" to TagSuggestion("underwater_scenery", rating = "e")),
    )

    onNodeWithText("landscape", useUnmergedTree = true).assertExists()
    onNodeWithText("underwater", substring = true, useUnmergedTree = true).assertDoesNotExist()
  }
}
