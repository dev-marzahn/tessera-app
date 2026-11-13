package dev.marzahn.sankaku.ui.components

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals

/** A held key's auto-repeats arrive as more key downs before its key up (#432 review). */
@OptIn(InternalComposeUiApi::class)
class OncePerPressTest {
  private val presses = OncePerPress()

  private fun news(vararg events: Pair<Key, KeyEventType>) = events.map { (key, type) -> presses.isNewPress(KeyEvent(key, type)) }

  @Test
  fun aHeldKeyIsOnePress() {
    assertEquals(
      listOf(true, false, false, false, true, false),
      news(Key.F to KeyEventType.KeyDown, Key.F to KeyEventType.KeyDown, Key.F to KeyEventType.KeyDown, Key.F to KeyEventType.KeyUp, Key.F to KeyEventType.KeyDown, Key.F to KeyEventType.KeyUp),
    )
  }

  @Test
  fun losingTheFocusLetsGo() {
    // F opened sign-in, which took the focus and F's release: the next F is a new press.
    assertEquals(listOf(true), news(Key.F to KeyEventType.KeyDown))
    presses.letGo()
    assertEquals(listOf(true), news(Key.F to KeyEventType.KeyDown))
  }

  @Test
  fun anotherKeyLetsGoInCaseTheReleaseWentElsewhere() {
    // F's release went to another window; after 3, F is new again.
    assertEquals(
      listOf(true, true, true),
      news(Key.F to KeyEventType.KeyDown, Key.Three to KeyEventType.KeyDown, Key.F to KeyEventType.KeyDown),
    )
  }
}
