package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.api.dto.TagDto
import dev.marzahn.sankaku.api.dto.TagType

/**
 * A post's name, the tab-label rule (#138): its characters, else its series, else its artist (at most two, joined
 * with ", "), else its first three general tags (joined with " · "), each by [TagDto.displayName]. Never its id.
 * The series `original` and `original_character` count only after all of those (#233): "Original" names a post only
 * when it has nothing else to be named by.
 *
 * General means any other tag that says what the post shows. With the new tag types the API sends, few tags are
 * [TagType.General] itself: "Comic", "2boys" and "Speech bubble" are Medium, Setting and Object.
 */
object PostTitle {
  /** What a post with none of those tags is called; drawn in the variant colour, as a placeholder. */
  const val UNTITLED = "Untitled post"

  private val named = listOf(TagType.Character, TagType.Copyright, TagType.Artist)

  /**
   * Besides those, a studio (a name, like an artist, that the rule doesn't name a post by) and tags about the file or
   * the tagging rather than the picture: "High resolution", "Translated", "English".
   */
  private val notGeneral = named + listOf(TagType.Studio, TagType.Meta, TagType.Language, TagType.Automatic)

  /** The name of [post] by the tags it carries (a list's post carries only 5), or null for [UNTITLED]. */
  fun of(post: PostDto): String? = of(post.tags)

  fun of(tags: List<TagDto>): String? {
    fun names(picked: List<TagDto>, count: Int) =
      picked.map { it.displayName }.filter { it.isNotBlank() }.distinct().take(count).takeIf { it.isNotEmpty() }
    // `original` and `original_character` name almost every original work, so as a series they come last (#233).
    val (originals, series) = tags.filter { it.category == TagType.Copyright }.partition(::isOriginal)
    for (type in named) {
      val picked = if (type == TagType.Copyright) series else tags.filter { it.category == type }
      names(picked, 2)?.let { return it.joinToString(", ") }
    }
    names(tags.filter { it.category !in notGeneral }, 3)?.let { return it.joinToString(" · ") }
    return names(originals, 2)?.joinToString(", ")
  }

  /** The two series tags that only say "this is an original work": `original` and `original_character`. */
  private fun isOriginal(tag: TagDto): Boolean =
    (tag.tagName ?: tag.name).trim().lowercase().replace(' ', '_') in ORIGINAL_SERIES

  private val ORIGINAL_SERIES = setOf("original", "original_character")
}
