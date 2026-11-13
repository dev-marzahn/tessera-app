package dev.marzahn.sankaku.tabs

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.TabContent
import dev.marzahn.sankaku.data.InMemoryTextFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The activity's saved state holds each open tab's UI state and nothing for a closed tab: every closed tab's state
 * stayed in it, so it grew with every tab ever closed until going to the background crashed with
 * `TransactionTooLargeException` (#389).
 */
@OptIn(ExperimentalTestApi::class)
class TabStatesTest {
  private val tabs = TabsController(FileTabStateStore(InMemoryTextFile(null)))
  private val registry = SaveableStateRegistry(null) { true }

  /** The active tab's saved counter, as a screen's saved state. */
  private var shownState: MutableIntState? = null

  private fun ComposeUiTest.shell() = setContent {
    CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
      val open by tabs.tabs.collectAsState()
      val active by tabs.active.collectAsState()
      TabContent(shown = true, open) { holder ->
        holder.SaveableStateProvider(active.value) { shownState = rememberSaveable { mutableIntStateOf(1) } }
      }
    }
  }

  private fun ComposeUiTest.openShown(i: Int): TabId {
    runOnIdle { tabs.open(Route.Grid(listOf("t$i")), background = false) }
    waitForIdle()
    return tabs.activeTab.id
  }

  @Test
  fun theSavedStateKeepsOnlyTheOpenTabs() = runComposeUiTest {
    shell()
    val kept = tabs.activeTab.id.value
    val closed = (1..200).map { i ->
      val id = openShown(i)
      runOnIdle { tabs.close(id) }
      waitForIdle()
      id.value
    }
    // A second route out of the strip: several tabs shown, then closed at once.
    val closedTogether = (201..210).map { openShown(it) }
    runOnIdle { tabs.closeAll(closedTogether) }
    waitForIdle()

    val keys = savedStrings(runOnIdle { registry.performSave() })
    assertEquals(emptyList(), (closed + closedTogether.map { it.value }).filter { it in keys })
    assertTrue(kept in keys)
    // Bounded: the open tab's state and its id, not an entry per tab ever shown.
    assertTrue(keys.size < 10, "saved ${keys.size} strings")
  }

  @Test
  fun aTabInTheBackgroundKeepsItsStateWhileOthersOpenAndClose() = runComposeUiTest {
    shell()
    val background = tabs.activeTab.id
    runOnIdle { shownState!!.intValue = 2 }
    (1..20).forEach { i ->
      val id = openShown(i)
      runOnIdle { tabs.activate(background) }
      waitForIdle()
      assertEquals(2, shownState!!.intValue)
      runOnIdle { tabs.close(id) }
      waitForIdle()
    }
    assertEquals(2, shownState!!.intValue)
  }

  /** Every string in the saved tree, map keys included: where tab ids end up. */
  private fun savedStrings(value: Any?): Set<String> = when (value) {
    is String -> setOf(value)
    is Map<*, *> -> value.keys.flatMap { savedStrings(it) }.toSet() + value.values.flatMap { savedStrings(it) }
    is Iterable<*> -> value.flatMap { savedStrings(it) }.toSet()
    else -> emptySet()
  }
}
