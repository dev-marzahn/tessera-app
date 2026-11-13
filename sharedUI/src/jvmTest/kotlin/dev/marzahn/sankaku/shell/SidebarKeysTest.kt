package dev.marzahn.sankaku.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.runComposeUiTest
import com.google.fonts.icons.search
import com.google.fonts.icons.shield
import dev.marzahn.sankaku.features.auth.AccountRow
import dev.marzahn.sankaku.features.settings.SwitchRow
import dev.marzahn.sankaku.ui.SHORT_WINDOW_MS
import dev.marzahn.sankaku.ui.components.ModalNavigationRail
import dev.marzahn.sankaku.ui.components.Shortcuts
import dev.marzahn.sankaku.ui.components.keyRing
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The sidebar's keys (#416): opened by key, focus starts on the current tab's entry (or the first), W/S move it through
 * every entry, the switch and the account included, Space opens, E opens in a new tab, and G or Esc closes. The real
 * rail and entries, with the switch and the account row the sidebar has; only the entries' actions are counted here.
 */
@OptIn(ExperimentalTestApi::class)
class SidebarKeysTest {
  private var open by mutableStateOf(true)
  private var gOnly by mutableStateOf(false)
  private val opened = mutableListOf<String>()
  private val openedInNewTab = mutableListOf<String>()
  private var signOuts = 0

  /** [byKey]: opened with G, as the shell does; otherwise with the menu button. */
  private fun ComposeUiTest.showRail(current: String?, byKey: Boolean = true) {
    // As the shell does: G opened it and its release hasn't come yet; the rail hears it.
    var held by mutableStateOf(byKey)
    setContent {
      SankakuTheme {
        ModalNavigationRail(
          open,
          onClose = { open = false },
          containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
          closeKey = Shortcuts.Sidebar.key,
          focusColumn = !byKey,
          closeKeyHeld = held,
          onCloseKeyUp = { held = false },
        ) {
          CompositionLocalProvider(LocalOpenedByKey provides (open && byKey)) {
            for (label in listOf("Browse", "For you", "Popular")) {
              Entry(
                label,
                search,
                onOpen = { opened += label },
                onOpenInNewTab = { openedInNewTab += label },
                selected = label == current,
                startHere = label == "Browse" && current == null,
              )
            }
            // Held on by `safeOnly`: no stop for the keys.
            SwitchRow("Locked", help = null, checked = true, onCheckedChange = {}, enabled = false, icon = shield)
            SwitchRow(
              "G only",
              help = null,
              checked = gOnly,
              onCheckedChange = { gOnly = it },
              modifier = Modifier.keyRing(CircleShape),
              icon = shield,
            )
            Entry("Settings", search, onOpen = { opened += "Settings" }, onOpenInNewTab = { openedInNewTab += "Settings" })
            AccountRow(
              "someone",
              avatarUrl = null,
              open = open,
              onSignOut = { signOuts++ },
              modifier = Modifier.keyRing(CircleShape),
              windowMillis = SHORT_WINDOW_MS,
            )
          }
        }
      }
    }
    waitForIdle()
  }

  private fun ComposeUiTest.press(key: Key) = press(listOf(key))

  private fun ComposeUiTest.press(keys: List<Key>) {
    for (key in keys) {
      // Sent to the dialog's root, which hands it to whatever has focus there.
      onNodeWithText("Browse").performKeyInput { pressKey(key) }
      waitForIdle()
    }
  }

  @Test
  fun focusStartsOnTheCurrentTabsEntry() = runComposeUiTest {
    showRail(current = "Popular")
    onNodeWithText("Popular").assertIsFocused()
    onNodeWithText("Browse").assertIsNotFocused()
  }

  @Test
  fun focusStartsOnTheFirstEntryWhenNoneIsCurrent() = runComposeUiTest {
    showRail(current = null)
    onNodeWithText("Browse").assertIsFocused()
  }

  @Test
  fun wsMoveThroughEveryEntryAndStopAtTheEnds() = runComposeUiTest {
    showRail(current = null)
    press(Key.W)
    onNodeWithText("Browse").assertIsFocused()
    press(Key.S)
    onNodeWithText("For you").assertIsFocused()
    press(Key.DirectionDown)
    onNodeWithText("Popular").assertIsFocused()
    // Past the locked switch, to G only, Settings and Sign out.
    press(Key.S)
    onNodeWithText("G only").assertIsFocused()
    press(Key.S)
    onNodeWithText("Settings").assertIsFocused()
    press(Key.S)
    onNodeWithContentDescription("Sign out").assertIsFocused()
    press(Key.S)
    onNodeWithContentDescription("Sign out").assertIsFocused()
    press(listOf(Key.W, Key.DirectionUp))
    onNodeWithText("G only").assertIsFocused()
  }

  @Test
  fun spaceOpensHereAndEInANewTabLeavingTheSidebarOpen() = runComposeUiTest {
    showRail(current = "For you")
    press(Key.E)
    assertEquals(listOf("For you"), openedInNewTab)
    assertEquals(true, open)
    press(listOf(Key.S, Key.Spacebar))
    assertEquals(listOf("Popular"), opened)
    // G only switches with Space, as with a click.
    press(listOf(Key.S, Key.Spacebar))
    assertEquals(true, gOnly)
  }

  @Test
  fun signOutTakesTwoSpaces() = runComposeUiTest {
    showRail(current = "Popular")
    press(listOf(Key.S, Key.S, Key.S))
    // The question's window would run out while the test waits for idle: the clock moves only when told from here.
    mainClock.autoAdvance = false
    press(Key.Spacebar)
    mainClock.advanceTimeBy(500)
    onNodeWithText("Sign out? Tap again").assertIsFocused()
    assertEquals(0, signOuts)
    press(Key.Spacebar)
    mainClock.advanceTimeBy(500)
    assertEquals(1, signOuts)
    // The question, answered, took its focus with it: the rail has it back, so the keys still work.
    press(Key.S)
    onNodeWithText("Browse").assertIsFocused()
  }

  @Test
  fun gClosesItOnceTheGThatOpenedItIsLetGo() = runComposeUiTest {
    showRail(current = "Popular")
    // The G that opened it is still down: its repeats keep the rail open.
    onNodeWithText("Browse").performKeyInput { keyDown(Key.G) }
    waitForIdle()
    assertEquals(true, open)
    onNodeWithText("Browse").performKeyInput { keyUp(Key.G) }
    waitForIdle()
    // Shift-G isn't G.
    onNodeWithText("Browse").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.G) } }
    waitForIdle()
    assertEquals(true, open)
    press(Key.G)
    assertFalse(open)
    assertEquals(emptyList(), opened)
  }

  @Test
  fun openedWithTheMenuButtonWEntersTheEntriesFromTheRail() = runComposeUiTest {
    showRail(current = "Popular", byKey = false)
    onNodeWithText("Popular").assertIsNotFocused()
    press(Key.S)
    onNodeWithText("Browse").assertIsFocused()
    // No key was down when it opened, so G closes at once.
    press(Key.G)
    assertFalse(open)
  }

  @Test
  fun escClosesIt() = runComposeUiTest {
    showRail(current = "Popular")
    press(Key.Escape)
    assertFalse(open)
  }
}
