package dev.marzahn.sankaku.tabs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder

/**
 * Each tab's saved UI state by tab id (a grid's scroll position, an open viewer, every back stack entry's state), kept
 * only while the tab is open. A plain [rememberSaveableStateHolder] keeps a key's state until `removeState`, and a
 * closed tab's id never comes back (a reopened tab gets a new one), so every tab ever closed stayed in the activity's
 * saved state, about 2 to 5 KB each, carried across process deaths: past the binder's 1 MB the app crashed with
 * `TransactionTooLargeException` going to the background.
 *
 * The ids given state are saved with it, so a tab closed after the process came back is dropped too.
 */
internal class TabStates(private val holder: SaveableStateHolder, private val given: MutableSet<String>) : SaveableStateHolder {
  /** The open tabs' ids as of the last [keepOnly]. */
  private var open: Set<String> = emptySet()

  @Composable
  override fun SaveableStateProvider(key: Any, content: @Composable () -> Unit) {
    holder.SaveableStateProvider(key, content)
    // After [keepOnly] in the same frame ([rememberTabStates] registers it first): a tab still drawn as it leaves (a
    // handoff's fade) isn't recorded again once it's dropped.
    SideEffect { if (key in open) given += key as String }
  }

  override fun removeState(key: Any) {
    given.remove(key)
    holder.removeState(key)
  }

  /**
   * Drops the state of every tab not in [open]. A tab still drawn as it leaves saves nothing: the holder doesn't save a
   * removed key's state when its content goes.
   */
  fun keepOnly(open: Set<String>) {
    this.open = open
    given.filter { it !in open }.forEach(::removeState)
  }
}

/** [TabStates] for the tabs in [open], by [TabState.id]. */
@Composable
internal fun rememberTabStates(open: List<TabState>): SaveableStateHolder {
  val holder = rememberSaveableStateHolder()
  val given = rememberSaveable(saver = listSaver<MutableSet<String>, String>({ it.toList() }, { it.toMutableSet() })) {
    mutableSetOf()
  }
  val states = remember(holder, given) { TabStates(holder, given) }
  val ids = open.mapTo(mutableSetOf()) { it.id.value }
  SideEffect { states.keepOnly(ids) }
  return states
}
