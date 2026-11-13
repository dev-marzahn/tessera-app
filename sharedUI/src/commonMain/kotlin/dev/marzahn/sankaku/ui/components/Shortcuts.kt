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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.google.fonts.icons.arrow_back

/**
 * A key and the label its badge shows, so a handler and the badge by its button can't disagree. [alias] is a
 * left-hand key that does the same, for keyboard-only use (the badge keeps [label]).
 */
data class Shortcut(val key: Key, val label: String, val alias: Key? = null) {
  fun matches(key: Key): Boolean = key == this.key || key == alias
}

/**
 * Tells a key's press from its auto-repeats, for a toggle that acts once per press: held, F would favourite and
 * unfavourite over and over, a request each (#432 review). Give it every event the handler sees; another key's press,
 * or the key's release, lets go. Desktop key events carry no repeat flag, so a release that went elsewhere (F opened
 * sign-in, a sheet or another window took the focus) would make the next press look like a repeat: the handler calls
 * [letGo] when its node loses the focus.
 */
class OncePerPress {
  private var held: Key? = null

  /** Forgets the held key: its release may go to whatever has the focus now. */
  fun letGo() {
    held = null
  }

  /** Whether [event] is a key down that isn't a repeat of the key already held. */
  fun isNewPress(event: KeyEvent): Boolean = when (event.type) {
    KeyEventType.KeyDown -> (event.key != held).also { held = event.key }
    KeyEventType.KeyUp -> false.also { if (event.key == held) held = null }
    else -> false
  }
}

/** Cmd or Ctrl is held: the key is an app shortcut (Cmd-F, Ctrl-W), not a screen's own letter key. */
val KeyEvent.isCommand: Boolean get() = isMetaPressed || isCtrlPressed

/** No Cmd, Ctrl, Alt or Shift: the plain letter, as X, G and the sidebar's E want it. */
val KeyEvent.isPlain: Boolean get() = !isCommand && !isAltPressed && !isShiftPressed

/**
 * Shift-A or Shift-D: a step to the previous (false) or next (true) tab, else null. The shell takes it; screens whose
 * A/D move something (the grid cursor, the viewer, the reader) leave it alone.
 */
val KeyEvent.tabStep: Boolean?
  get() = when {
    !isShiftPressed || isCommand || isAltPressed -> null
    key == Shortcuts.PreviousTab.key -> false
    key == Shortcuts.NextTab.key -> true
    else -> null
  }

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

  /** Closes the current tab, which [ReopenTab] brings back; Cmd/Ctrl-W too, at window level. */
  val CloseTab = Shortcut(Key.X, "X")

  /** With Shift ([tabStep]): the previous and next tab, wrapping round; Ctrl-(Shift-)Tab too, at window level. */
  val PreviousTab = Shortcut(Key.A, "⇧A")
  val NextTab = Shortcut(Key.D, "⇧D")

  /** With Cmd or Ctrl and Shift, at window level on the desktop (#183): Clear viewed. Plain Shift-E is another action. */
  val ClearViewed = Shortcut(Key.E, "⌘⇧E")

  /** At window level on the desktop, with Cmd or Ctrl ([isCommand]): a new grid tab, and closing the current one. */
  val NewTab = Shortcut(Key.T, "⌘T")
  val CloseTabCommand = Shortcut(Key.W, "⌘W")

  /** With Cmd or Ctrl and Shift, at window level: [ReopenTab]'s twin. */
  val ReopenTabCommand = Shortcut(Key.T, "⌘⇧T")

  /** With Ctrl (and Shift for the previous), at window level: [NextTab]'s and [PreviousTab]'s twins. */
  val NextTabCommand = Shortcut(Key.Tab, "⌃Tab")
  val PreviousTabCommand = Shortcut(Key.Tab, "⌃⇧Tab")

  /** With Cmd or Ctrl, at window level: refreshes the grid; F5 too. */
  val Refresh = Shortcut(Key.R, "⌘R", alias = Key.F5)

  /**
   * With Shift (#521, desktop): the keyboard shortcuts sheet, which lists these, and closes it again. Plain / is
   * [Search].
   */
  val Help = Shortcut(Key.Slash, "?")

  /**
   * Opens the sidebar from the keyboard (#416), on the current tab's entry, and closes it again. While it's open
   * W/S move, [OpenHere] opens the entry here, [OpenInTab] in a new tab, and [Back] closes it too.
   */
  val Sidebar = Shortcut(Key.G, "G")

  /**
   * Opens the tab switcher (#461, desktop), a grid of every open tab, and closes it again. C is free on every screen
   * (Q is the tag list's Exclude, Shift-Space the reader's page back).
   */
  val TabSwitcher = Shortcut(Key.C, "C")

  /** In the tab switcher (#502): its cards or its queue list, by turns; the switcher holds every key, so Q is free. */
  val SwitcherMode = Shortcut(Key.Q, "Q")

  /**
   * With Shift (#502): closes a whole block, its head and its kept items, as one Recently closed entry. In the
   * switcher's queue list on the selected row's block; outside it, the block "Done with …?" offers to close.
   */
  val CloseBlock = Shortcut(Key.X, "⇧X")

  /** Focuses a post's tag list. */
  val TagList = Shortcut(Key.T, "T")

  /** On the grid's keyboard cursor: open its post in a background tab. */
  val OpenInTab = Shortcut(Key.E, "E")

  /** Opens the selection here: the cursor's post in the viewer, or the selected tag's search alone. */
  val OpenHere = Shortcut(Key.Spacebar, "Space")

  /** Closes the viewer over a grid again, as Esc does (#424): Space toggles it. Not on a video, where it's [PlayPause]. */
  val CloseViewer = OpenHere

  /** In the tag list: include or exclude the selected tag (the same key again removes it), or remove it. */
  val Include = Shortcut(Key.E, "E")
  val Exclude = Shortcut(Key.Q, "Q")
  val Remove = Shortcut(Key.Backspace, "⌫")

  /**
   * Opens a menu, a left-hand key (the owner's choice): the selected tag's in the tag list, as a right-click does (#6),
   * and the reader's ⋮ (#443).
   */
  val Menu = Shortcut(Key.R, "R")

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

/**
 * Tells a key's first press from its auto-repeat, which the desktop sends as more presses with no release between.
 * [modifier] sees every key on its way in, before any handler under it, and consumes none. A press while the same key
 * is still held is [repeating]: Space held on the grid opens the viewer once, and doesn't close it again (#424).
 * Made with [rememberHeldKeys], which forgets every held key when the window loses focus, as their releases go to
 * another app: otherwise the first press after Cmd-Tab would count as a repeat and do nothing. Not when focus moves
 * within the window: the viewer opening hands it from the grid's key holder to its own, with nothing focused between.
 */
@Stable
class HeldKeys {
  private val held = mutableSetOf<Key>()
  private var repeat: Key? = null

  /** Whether the press of [key] being handled is an auto-repeat. */
  fun repeating(key: Key): Boolean = repeat == key

  /** No key is held any more: their releases went elsewhere. */
  fun forget() {
    held.clear()
    repeat = null
  }

  val modifier: Modifier = Modifier.onPreviewKeyEvent { event ->
    when (event.type) {
      KeyEventType.KeyDown -> repeat = event.key.takeIf { !held.add(it) }
      KeyEventType.KeyUp -> {
        held.remove(event.key)
        if (repeat == event.key) repeat = null
      }
    }
    false
  }
}

/** A [HeldKeys] that forgets what's held whenever the window loses focus. */
@Composable
fun rememberHeldKeys(): HeldKeys {
  val keys = remember { HeldKeys() }
  val focused = LocalWindowInfo.current.isWindowFocused
  LaunchedEffect(focused) { if (!focused) keys.forget() }
  return keys
}

/**
 * Back, as an extra-small text button (M3 XSmall: 32 dp, 20 dp icon) with its key: in the top bar and the viewer.
 * [alsoKey] is a second key that goes back here, its badge beside Esc's (Space over the viewer, #424).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier, alsoKey: Shortcut? = null) {
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
    if (alsoKey != null) {
      Spacer(Modifier.width(2.dp))
      KbdBadge(alsoKey.label)
    }
  }
}

/**
 * Takes keyboard focus when it enters, so the key handlers of the screen around it receive keys; clicking a text
 * field moves focus away, so typing there doesn't trigger them. Zero-sized: a focusable screen root would be an
 * accessibility element spanning the screen and would take presses meant for its buttons. It takes focus again
 * whenever the shell hands keys back ([KeyFocus.returnToContent]). [modifier] can set where Tab goes from it
 * (`focusProperties`). Focused, it brings itself into view, so inside a scrolling column it would scroll there.
 */
@Composable
fun KeyFocusHolder(modifier: Modifier = Modifier) {
  val focus = remember { FocusRequester() }
  val returns = LocalKeyFocus.current.returns
  LaunchedEffect(returns) { focus.requestFocus() }
  Box(modifier.size(0.dp).focusRequester(focus).focusable())
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
