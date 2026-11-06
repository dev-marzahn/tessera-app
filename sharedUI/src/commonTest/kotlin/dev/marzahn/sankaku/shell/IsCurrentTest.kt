package dev.marzahn.sankaku.shell

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.GridPresets
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which sidebar entry is selected: the one whose preset is exactly the current grid's tags. */
class IsCurrentTest {
  @Test
  fun aPresetGridSelectsItsEntry() {
    assertTrue(isCurrent(Route.Grid(GridPresets.popular), GridPresets.popular))
    assertTrue(isCurrent(Route.Grid(GridPresets.favourites("someone")), GridPresets.favourites("someone")))
  }

  @Test
  fun browseIsTheGridWithNoTags() {
    assertTrue(isCurrent(Route.Grid(emptyList()), emptyList()))
    assertFalse(isCurrent(Route.Grid(listOf("blue_hair")), emptyList()))
  }

  @Test
  fun aPresetOnlySelectsItself() {
    assertFalse(isCurrent(Route.Grid(GridPresets.popular), GridPresets.top))
    assertFalse(isCurrent(Route.Grid(GridPresets.popular), emptyList()))
  }

  @Test
  fun aTypedSearchSelectsNothing() {
    val typed = Route.Grid(listOf("blue_hair", "smile"))
    val presets = listOf(emptyList(), GridPresets.forYou, GridPresets.following, GridPresets.popular, GridPresets.top)
    for (preset in presets) assertFalse(isCurrent(typed, preset))
  }

  @Test
  fun aFilterOnTopOfAPresetSelectsNothing() {
    val favourites = GridPresets.favourites("someone")
    assertFalse(isCurrent(Route.Grid(favourites + "rating:s"), favourites))
    assertFalse(isCurrent(Route.Grid(listOf("rating:s") + GridPresets.popular), GridPresets.popular))
  }

  @Test
  fun theOrderOfTheTagsDoesNotMatter() {
    assertTrue(isCurrent(Route.Grid(listOf("b", "a")), listOf("a", "b")))
  }

  @Test
  fun aScrollPositionDoesNotMatter() {
    assertTrue(isCurrent(Route.Grid(GridPresets.top, at = "abc"), GridPresets.top))
  }

  @Test
  fun aPostOrNoTabSelectsNothing() {
    assertFalse(isCurrent(Route.Post("1", null, GridPresets.popular), GridPresets.popular))
    assertFalse(isCurrent(null, emptyList()))
  }
}
