package dev.marzahn.sankaku.features.tag

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #522: a tag's wiki as the page shows it, its DText read as headings, bullets, styles and links, never raw. */
class WikiTextTest {
  private fun AnnotatedString.styledOver(part: String) = spanStyles.filter { text.substring(it.start, it.end) == part }.map { it.item }

  private fun AnnotatedString.tagLinks() = getLinkAnnotations(0, length).map { it.item }.filterIsInstance<LinkAnnotation.Clickable>().map { it.tag }

  private fun AnnotatedString.urls() = getLinkAnnotations(0, length).map { it.item }.filterIsInstance<LinkAnnotation.Url>().map { it.url }

  @Test
  fun headingsReadAsHeadings() {
    val text = wikiText("h4. Characters\nSome text\nh5.Umamusume")
    assertEquals("Characters\nSome text\nUmamusume", text.text)
    assertTrue(text.styledOver("Characters").any { it.fontWeight == FontWeight.SemiBold })
    assertTrue(text.styledOver("Umamusume").any { it.fontWeight == FontWeight.SemiBold })
    assertTrue(text.styledOver("Some text").isEmpty())
  }

  @Test
  fun starLinesReadAsBulletsNestedOnce() {
    // Indented with an em space, not a paragraph style: a style's edge adds an empty line around it in Compose's layout.
    val text = wikiText("* one\n** two\n*** three")
    assertEquals("• one\n • two\n • three", text.text)
    assertTrue(text.paragraphStyles.isEmpty())
  }

  @Test
  fun boldItalicUnderlineAndStrikeAreStyled() {
    val text = wikiText("[b]bold[/b] [i]italic[/i] [u]under[/u] [s]struck[/s]")
    assertEquals("bold italic under struck", text.text)
    assertTrue(text.styledOver("bold").any { it.fontWeight == FontWeight.Bold })
    assertTrue(text.styledOver("italic").any { it.fontStyle == FontStyle.Italic })
    assertTrue(text.styledOver("under").any { it.textDecoration == TextDecoration.Underline })
    assertTrue(text.styledOver("struck").any { it.textDecoration == TextDecoration.LineThrough })
  }

  @Test
  fun aStyleCanSpanLines() {
    val text = wikiText("[i]Some umamusume\nare tagged[/i] so")
    assertTrue(text.styledOver("Some umamusume\nare tagged").any { it.fontStyle == FontStyle.Italic })
  }

  @Test
  fun aQuoteIsIndentedAndItalicWithNoLinesOfItsOwnForItsTags() {
    val text = wikiText("Before\n[quote]\nSaid so.\n* And this\n[/quote]\nAfter")
    assertEquals("Before\n Said so.\n • And this\nAfter", text.text)
    assertTrue(text.styledOver(" Said so.\n • And this").any { it.fontStyle == FontStyle.Italic })
  }

  @Test
  fun otherTagsAreDroppedKeepingWhatTheyHold() {
    assertEquals("secret and a note More", wikiText("[spoiler]secret[/spoiler] and [tn]a note[/tn] [expand=More]More[/expand]").text)
    assertEquals("Hidden", wikiText("[expand]\nHidden\n[/expand]").text)
  }

  @Test
  fun wikiLinksAreLinksToTheirTags() {
    val text = wikiText("[[Admire Groove (umamusume)]], [[other_tag|another]] and [[Some Tag#Section]]")
    assertEquals("Admire Groove (umamusume), another and Some Tag#Section", text.text)
    assertEquals(listOf("admire_groove_(umamusume)", "other_tag", "some_tag"), text.tagLinks())
  }

  @Test
  fun aWikiLinkWithoutALabelReadsWithSpaces() {
    assertEquals("other tag", wikiText("[[other_tag]]").text)
  }

  @Test
  fun webLinksOpenInTheBrowserAndSiteLinksReadAsTheirText() {
    val text = wikiText("\"Pixiv\":https://www.pixiv.net/users/1 or https://example.com/a. See \"posts\":/posts?tags=x and \"wiki\":[https://example.com/w]")
    assertEquals("Pixiv or https://example.com/a. See posts and wiki", text.text)
    assertEquals(listOf("https://www.pixiv.net/users/1", "https://example.com/a", "https://example.com/w"), text.urls())
  }

  @Test
  fun blankLinesCollapseToOneAndTheEndsAreTrimmed() {
    assertEquals("One\n\nTwo", wikiText("\n\nOne\r\n\r\n\r\nTwo\n\n").text)
  }

  @Test
  fun linksTellWhichTag() {
    val clicked = mutableListOf<WikiTag>()
    val text = wikiText("[[other_tag|another]]", onTag = { clicked += it })
    val link = text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Clickable
    link.linkInteractionListener!!.onClick(link)
    assertEquals(listOf(WikiTag(shown = "other tag", searchName = "other_tag")), clicked)
  }
}
