package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.dto.TagSuggestion
import dev.marzahn.sankaku.data.InMemoryTextFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The queue's titles use the names the search chips show, from the tags the app has learnt (#246). */
class TagNameTitleTest {
  private val known = MutableStateFlow(mapOf("k-suwabe" to TagSuggestion(tagName = "k-suwabe", name = "K-Suwabe")))

  @Test
  fun aSourceIsTitledByItsChipsName() {
    val tabs = TabsController(knownTags = known)
    tabs.open(Route.Grid(listOf("order:popularity", "k-suwabe", "flower")), background = false)

    assertEquals("K-Suwabe Flower", tabs.activeTab.title)
  }

  @Test
  fun aRestoredSourceWithNoKnownNameReadsWithSpaces() {
    val store = FileTabStateStore(InMemoryTextFile(null))
    val before = TabsController(store)
    before.open(Route.Grid(listOf("jien_(nikke)")), background = false)
    store.save(before.saved())

    val after = TabsController(store, knownTags = MutableStateFlow(emptyMap()))

    assertEquals("Jien (Nikke)", after.activeTab.title)
  }

  @Test
  fun aNameLearntLaterRenamesTheSourceAndAClosedOneIsNamedToo() = runTest {
    val tabs = TabsController(scope = backgroundScope, knownTags = known)
    runCurrent()
    tabs.open(Route.Grid(listOf("jien_(nikke)")), background = false)
    val source = tabs.activeTab
    tabs.open(Route.Grid(listOf("k-suwabe")), background = false)
    val closed = tabs.close(tabs.activeTab.id)!!
    assertEquals("Jien (Nikke)", source.title)

    known.value += "jien_(nikke)" to TagSuggestion(tagName = "jien_(nikke)", name = "Jien_(Nikke)", nameEn = "Jien")
    runCurrent()

    assertEquals("Jien", source.title)
    assertEquals("K-Suwabe", closed.title)
  }
}
