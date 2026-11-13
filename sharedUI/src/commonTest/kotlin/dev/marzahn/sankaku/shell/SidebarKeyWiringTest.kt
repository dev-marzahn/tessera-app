package dev.marzahn.sankaku.shell

import androidx.compose.ui.input.key.Key
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.GridPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sidebar's keys as the shell and `Sidebar` wire them (#416): which G opens it, and where its selection starts.
 * `SidebarKeysTest` covers the keys inside the rail.
 */
class SidebarKeyWiringTest {
  private fun g(plain: Boolean = true, held: Boolean = false, searchOpen: Boolean = false, touch: Boolean = false) =
    shellSidebarKey(Key.G, plain, held, searchOpen, touch)

  @Test
  fun plainGOpensTheSidebar() {
    assertEquals(SidebarKey.Open, g())
  }

  @Test
  fun aHeldGsRepeatsDoNothing() {
    assertEquals(SidebarKey.Repeat, g(held = true))
  }

  @Test
  fun gWithAModifierWhileTypingOrOnAPhoneIsntTheSidebars() {
    assertEquals(SidebarKey.None, g(plain = false))
    assertEquals(SidebarKey.None, g(searchOpen = true))
    assertEquals(SidebarKey.None, g(touch = true))
  }

  @Test
  fun otherKeysArentTheSidebars() {
    assertEquals(SidebarKey.None, shellSidebarKey(Key.F, plain = true, held = true, searchOpen = false, touch = false))
  }

  @Test
  fun presetsAndBooksHaveAnEntry() {
    assertTrue(hasEntry(Route.Grid(emptyList()), userName = null))
    assertTrue(hasEntry(Route.Grid(GridPresets.popular), userName = null))
    assertTrue(hasEntry(Route.Grid(GridPresets.following), userName = null))
    assertTrue(hasEntry(Route.Books(), userName = null))
  }

  @Test
  fun favouritesHasAnEntryOnlyForTheSignedInAccount() {
    val favourites = Route.Grid(GridPresets.favourites("someone"))
    assertTrue(hasEntry(favourites, userName = "someone"))
    assertFalse(hasEntry(favourites, userName = null))
    assertFalse(hasEntry(favourites, userName = "someone_else"))
  }

  @Test
  fun aTypedSearchOrNoRouteStartsOnBrowse() {
    assertFalse(hasEntry(Route.Grid(listOf("blue_hair")), userName = "someone"))
    assertFalse(hasEntry(null, userName = "someone"))
  }
}
