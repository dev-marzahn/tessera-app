package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every [GroupedMenu] works from the left hand (#443): focus as it opens, W/S to move, Space, Tab or Enter to choose. */
@OptIn(ExperimentalTestApi::class)
class GroupedMenuKeysTest {
  private val chosen = mutableListOf<String>()
  private var open by mutableStateOf(false)

  // Opened after the first frame, as a click or key opens one.
  private fun ComposeUiTest.showMenu(items: @Composable () -> Unit) {
    setContent {
      Box { GroupedMenu(expanded = open, onDismissRequest = { open = false }) { MenuGroup(0, 1) { items() } } }
    }
    open = true
    waitForIdle()
  }

  @Composable
  private fun Action(name: String) = MenuAction(text = { Text(name) }, onClick = {
    chosen += name
    open = false
  })

  private fun ComposeUiTest.press(on: String, key: Key) = onNodeWithText(on).performKeyInput { pressKey(key) }

  @Test
  fun theFirstItemHasTheFocusAsTheMenuOpensAndWAndSMoveIt() = runComposeUiTest {
    showMenu {
      Action("Open")
      Action("Copy")
      Action("Close")
    }

    onNodeWithText("Open").assertIsFocused()
    press("Open", Key.S)
    onNodeWithText("Copy").assertIsFocused()
    press("Copy", Key.S)
    onNodeWithText("Close").assertIsFocused()
    press("Close", Key.W)
    onNodeWithText("Copy").assertIsFocused()
    assertEquals(emptyList(), chosen)
  }

  @Test
  fun wOnTheFirstItemAndSOnTheLastStayPut() = runComposeUiTest {
    showMenu {
      Action("Open")
      Action("Copy")
    }

    press("Open", Key.W)
    onNodeWithText("Open").assertIsFocused()
    press("Open", Key.S)
    press("Copy", Key.S)
    onNodeWithText("Copy").assertIsFocused()
    assertEquals(emptyList(), chosen)
  }

  @Test
  fun openedByATapOnATouchScreenNoItemTakesTheFocus()= runComposeUiTest {
    var modes: InputModeManager? = null
    setContent {
      modes = LocalInputModeManager.current
      Box {
        GroupedMenu(expanded = open, onDismissRequest = { open = false }) {
          MenuGroup(0, 1) {
            Action("Open")
            Action("Copy")
          }
        }
      }
    }
    // As a tap leaves it: the menu would otherwise switch to keyboard mode and ring its first item.
    runOnIdle { modes!!.requestInputMode(InputMode.Touch) }
    open = true
    waitForIdle()

    onNodeWithText("Open").assertIsNotFocused()
    onNodeWithText("Copy").assertIsNotFocused()
    assertEquals(InputMode.Touch, modes!!.inputMode)
  }

  @Test
  fun tabChoosesTheFocusedItemAsSpaceDoes()= runComposeUiTest {
    showMenu {
      Action("Open")
      Action("Copy")
    }

    press("Open", Key.S)
    press("Copy", Key.Tab)

    assertEquals(listOf("Copy"), chosen)
    onNodeWithText("Copy").assertDoesNotExist()
  }

  @Test
  fun spaceChoosesTheFocusedItem() = runComposeUiTest {
    showMenu {
      Action("Open")
      Action("Copy")
    }

    press("Open", Key.Spacebar)

    assertEquals(listOf("Open"), chosen)
  }

  @Test
  fun shiftTabDoesNothingInsideAMenu() = runComposeUiTest {
    showMenu {
      Action("Open")
      Action("Copy")
    }

    press("Open", Key.S)
    onNodeWithText("Copy").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }

    onNodeWithText("Copy").assertIsFocused()
    assertEquals(emptyList(), chosen)
  }

  @Test
  fun aChoiceMenuOpensOnTheChosenItem() = runComposeUiTest {
    showMenu {
      listOf("Newest", "Popularity", "Quality").forEach { name ->
        MenuChoice(selected = name == "Popularity", onClick = { chosen += name }) { Text(name) }
      }
    }

    onNodeWithText("Popularity").assertIsFocused()
    press("Popularity", Key.S)
    press("Quality", Key.Tab)
    assertEquals(listOf("Quality"), chosen)
  }

  @Test
  fun tabTicksAFocusedCheckAndTheMenuStaysOpen() = runComposeUiTest {
    var ticked by mutableStateOf(setOf<String>())
    showMenu {
      listOf("G", "R15").forEach { name ->
        MenuCheck(checked = name in ticked, onCheckedChange = { ticked = if (it) ticked + name else ticked - name }) {
          Text(name)
        }
      }
    }

    press("G", Key.S)
    press("R15", Key.Tab)
    assertEquals(setOf("R15"), ticked)
    press("R15", Key.Tab)
    assertEquals(emptySet(), ticked)
    onNodeWithText("R15").assertIsFocused()
  }
}
