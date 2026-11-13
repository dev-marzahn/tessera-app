package dev.marzahn.sankaku.tabs

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEventType.Companion.KeyDown
import androidx.compose.ui.input.key.KeyEventType.Companion.KeyUp
import dev.marzahn.sankaku.features.grid.GridMove
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The tab switcher's moves, its selection after a close and its keys (#461), apart from the composable. */
@OptIn(InternalComposeUiApi::class)
class TabSwitcherKeysTest {
  @Test
  fun movesStopAtTheEdgesAsTheGridCursorDoes() {
    // 7 cards in rows of 3: 0 1 2 / 3 4 5 / 6.
    assertEquals(1, switcherStep(0, 7, 3, GridMove.Right))
    assertNull(switcherStep(2, 7, 3, GridMove.Right))
    assertNull(switcherStep(3, 7, 3, GridMove.Left))
    assertEquals(4, switcherStep(1, 7, 3, GridMove.Down))
    assertEquals(6, switcherStep(3, 7, 3, GridMove.Down))
    // The short last row has nothing under 4 and 5, and nothing right of 6.
    assertNull(switcherStep(4, 7, 3, GridMove.Down))
    assertNull(switcherStep(6, 7, 3, GridMove.Right))
    assertNull(switcherStep(1, 7, 3, GridMove.Up))
    assertEquals(3, switcherStep(6, 7, 3, GridMove.Up))
  }

  @Test
  fun aClosedSelectionMovesToTheTabThatTookItsPlace() {
    val (a, b, c) = listOf(TabId("a"), TabId("b"), TabId("c"))
    assertEquals(c, selectionAfter(listOf(a, b, c), listOf(a, c), b))
    assertEquals(b, selectionAfter(listOf(a, b, c), listOf(a, b), c))
    // Another tab closing leaves it where it is.
    assertEquals(b, selectionAfter(listOf(a, b, c), listOf(b, c), b))
  }

  private val log = mutableListOf<String>()
  private var presses = SwitcherPresses()

  /** What [events] did, in one switcher: presses carry over from one call to the next. */
  private fun keys(vararg events: Pair<Key, KeyEventType>): List<String> {
    events.forEach { (key, type) ->
      switcherKey(
        KeyEvent(key, type),
        presses,
        onMove = { log += "move $it" },
        onGo = { log += "go" },
        onCloseTab = { log += "close tab" },
        onReopen = { log += "reopen" },
        onDismiss = { log += "dismiss" },
      )
    }
    return log
  }

  @Test
  fun wasdMoveOnEveryPressAndRepeat() {
    assertEquals(
      listOf("move Right", "move Right", "move Down"),
      keys(Key.D to KeyDown, Key.D to KeyDown, Key.D to KeyUp, Key.S to KeyDown, Key.S to KeyUp),
    )
  }

  @Test
  fun spaceAndEGoOnTheirRelease() {
    assertEquals(emptyList(), keys(Key.Spacebar to KeyDown, Key.Spacebar to KeyDown))
    assertEquals(listOf("go"), keys(Key.Spacebar to KeyUp))
    log.clear()
    assertEquals(listOf("go"), keys(Key.E to KeyDown, Key.E to KeyUp))
  }

  @Test
  fun xClosesOneTabPerPressAndZReopensOnEach() {
    assertEquals(
      listOf("close tab", "reopen", "reopen"),
      keys(Key.X to KeyDown, Key.X to KeyDown, Key.X to KeyUp, Key.Z to KeyDown, Key.Z to KeyDown),
    )
  }

  @Test
  fun theCThatOpenedItNeverClosesItButTheNextOneDoes() {
    presses = SwitcherPresses(heldAtOpen = Key.C)
    // Held from the shell: its repeats and release do nothing.
    assertEquals(emptyList(), keys(Key.C to KeyDown, Key.C to KeyDown, Key.C to KeyUp))
    // A fresh press closes it, on its release.
    assertEquals(emptyList(), keys(Key.C to KeyDown))
    assertEquals(listOf("dismiss"), keys(Key.C to KeyUp))
  }

  @Test
  fun aQuickTapsReleaseAtTheShellLetsGoOfC() {
    presses = SwitcherPresses(heldAtOpen = Key.C)
    // The opening C's release reached the shell before the switcher had the focus.
    presses.letGo(Key.C)
    assertEquals(listOf("dismiss"), keys(Key.C to KeyDown, Key.C to KeyUp))
  }

  @Test
  fun losingTheWindowForgetsHeldKeys() {
    keys(Key.X to KeyDown)
    // X's release went to another app; the next X is a press.
    presses.forget()
    assertEquals(listOf("close tab", "close tab"), keys(Key.X to KeyDown))
  }

  @Test
  fun escClosesOnItsRelease() {
    assertEquals(emptyList(), keys(Key.Escape to KeyDown))
    assertEquals(listOf("dismiss"), keys(Key.Escape to KeyUp))
  }

  @Test
  fun aReleaseWithNoPressHereDoesNothing() {
    // Space went down on the screen under the switcher; only its release comes here.
    assertEquals(emptyList(), keys(Key.Spacebar to KeyUp, Key.Escape to KeyUp))
  }
}
