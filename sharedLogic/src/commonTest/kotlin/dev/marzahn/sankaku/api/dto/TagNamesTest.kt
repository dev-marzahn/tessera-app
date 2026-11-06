package dev.marzahn.sankaku.api.dto

import dev.marzahn.sankaku.data.PostTitle
import kotlin.test.Test
import kotlin.test.assertEquals

/** One name per tag, on its chip and in every title (#246). */
class TagNamesTest {
  @Test
  fun aKeyWithNoNameHasSpacesAndEachWordCapitalised() {
    // #246's acceptance criterion: `jien_(nikke)` with no display name.
    assertEquals("Jien (Nikke)", TagNames.ofKey("jien_(nikke)"))
    assertEquals("Blue Hair", TagNames.ofKey("blue_hair"))
    // A `-` inside a word starts no new one; what the key capitalises stays.
    assertEquals("K-suwabe", TagNames.ofKey("k-suwabe"))
    assertEquals("McDonald's", TagNames.ofKey("mcDonald's"))
    assertEquals("1boy", TagNames.ofKey("1boy"))
  }

  @Test
  fun aSiteNameThatIsOnlyTheKeyReadsWithSpaces() {
    // The kept post the journey check found titled "Jien_(Nikke)": the site's name is the key, capitalised.
    val jien = TagDto(name = "Jien_(Nikke)", type = TagType.Character.id, tagName = "jien_(nikke)")
    assertEquals("Jien (Nikke)", jien.displayName)
    assertEquals("Jien (Nikke)", PostTitle.of(listOf(jien)))
    assertEquals("Jien (Nikke)", TagSuggestion(tagName = "jien_(nikke)", name = "Jien_(Nikke)").displayName)
    // An excluded suggestion's key has the `-` its name doesn't.
    assertEquals("Jien (Nikke)", TagSuggestion(tagName = "jien_(nikke)", name = "Jien_(Nikke)").excluded().displayName)
  }

  @Test
  fun aRealNameIsShownAsTheSiteGivesIt() {
    assertEquals("K-Suwabe", TagSuggestion(tagName = "k-suwabe", name = "K-Suwabe").displayName)
    assertEquals("Blue Hair", TagSuggestion(tagName = "blue_hair", name = "blue_hair", nameEn = "Blue Hair").displayName)
    assertEquals("^_^", TagNames.shown("^_^", "^_^_(emoticon)"))
    assertEquals("text", TagSuggestion(tagName = "-text", name = "-text").displayName)
  }
}
