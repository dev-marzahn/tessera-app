package dev.marzahn.sankaku.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.unit.dp
import com.google.fonts.icons.arrow_back

/**
 * A key and the label its badge shows, so a handler and the badge by its button can't disagree. [alias] is a
 * left-hand key that does the same, for keyboard-only use (the badge keeps [label]).
 */
data class Shortcut(val key: Key, val label: String, val alias: Key? = null) {
  fun matches(key: Key): Boolean = key == this.key || key == alias
}

/** Cmd or Ctrl is held: the key is an app shortcut (Cmd-F, Ctrl-W), not a screen's own letter key. */
val KeyEvent.isCommand: Boolean get() = isMetaPressed || isCtrlPressed

/** Every shortcut the app has. Esc is Back everywhere: a screen's own handler takes it first, the window's otherwise. */
object Shortcuts {
  val Back = Shortcut(Key.Escape, "Esc")
  val Favourite = Shortcut(Key.F, "F")
  /** Stepping through posts in the viewer, and the grid cursor's sideways moves. */
  val Previous = Shortcut(Key.DirectionLeft, "←", alias = Key.A)
  val Next = Shortcut(Key.DirectionRight, "→", alias = Key.D)

  /** The grid cursor's and the tag list's up and down. */
  val Up = Shortcut(Key.DirectionUp, "↑", alias = Key.W)
  val Down = Shortcut(Key.DirectionDown, "↓", alias = Key.S)
  val Details = Shortcut(Key.I, "I")
  val Search = Shortcut(Key.Slash, "/")

  /** With Cmd or Ctrl ([isCommand]), on every platform: opens the search too. */
  val Find = Shortcut(Key.F, "⌘F")

  /** Reopens the last closed tab; Cmd/Ctrl-Shift-T too, at window level. */
  val ReopenTab = Shortcut(Key.Z, "Z")

  /** With Cmd or Ctrl and Shift, at window level on the desktop (#183): Clear viewed. Plain Shift-E is another action. */
  val ClearViewed = Shortcut(Key.E, "⌘⇧E")

  /** Focuses a post's tag list. */
  val TagList = Shortcut(Key.T, "T")

  /** On the grid's keyboard cursor: open its post in a background tab. */
  val OpenInTab = Shortcut(Key.E, "E")

  /** Opens the selection here: the cursor's post in the viewer, or the selected tag's search alone. */
  val OpenHere = Shortcut(Key.Spacebar, "Space")

  /** In the tag list: include or exclude the selected tag (the same key again removes it), or remove it. */
  val Include = Shortcut(Key.E, "E")
  val Exclude = Shortcut(Key.Q, "Q")
  val Remove = Shortcut(Key.Backspace, "⌫")

  /** A video post in the viewer: play or pause, mute, and the scrub mode, where A/D seek and W/S set the volume. */
  val PlayPause = Shortcut(Key.Spacebar, "Space")
  val Mute = Shortcut(Key.M, "M")
  val Scrub = Shortcut(Key.V, "V")

  /** 1 to 5 vote (the row or the number pad); its badge reads "1–5". */
  val Vote = Shortcut(Key.One, "1–5")

  /** The vote [key] gives, or null if it isn't a vote key. */
  fun voteFor(key: Key): Int? = when (key) {
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    else -> null
  }
}

/** Back, as an extra-small text button (M3 XSmall: 32 dp, 20 dp icon) with its key: in the top bar and the viewer. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
  val height = ButtonDefaults.ExtraSmallContainerHeight
  TextButton(
    onClick = onClick,
    shapes = ButtonDefaults.shapes(),
    contentPadding = ButtonDefaults.contentPaddingFor(height),
    modifier = modifier.heightIn(min = height),
  ) {
    Icon(arrow_back, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
    Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
    Text("Back", style = ButtonDefaults.textStyleFor(height))
    Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
    KbdBadge(Shortcuts.Back.label)
  }
}

/**
 * Takes keyboard focus when it enters, so the key handlers of the screen around it receive keys; clicking a text
 * field moves focus away, so typing there doesn't trigger them. Zero-sized: a focusable screen root would be an
 * accessibility element spanning the screen and would take presses meant for its buttons. It takes focus again
 * whenever the shell hands keys back ([KeyFocus.returnToContent]).
 */
@Composable
fun KeyFocusHolder() {
  val focus = remember { FocusRequester() }
  val returns = LocalKeyFocus.current.returns
  LaunchedEffect(returns) { focus.requestFocus() }
  Box(Modifier.size(0.dp).focusRequester(focus).focusable())
}

/**
 * Hands keys back to the screen's [KeyFocusHolder] when what had them goes away or lets go: the search panel's text
 * field, the tag list.
 */
@Stable
class KeyFocus {
  var returns by mutableIntStateOf(0)
    private set

  fun returnToContent() {
    returns++
  }
}

val LocalKeyFocus = staticCompositionLocalOf { KeyFocus() }
