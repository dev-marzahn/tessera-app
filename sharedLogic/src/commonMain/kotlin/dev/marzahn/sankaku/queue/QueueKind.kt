package dev.marzahn.sankaku.queue

import dev.marzahn.sankaku.api.Feed as ApiFeed
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a queue item is (Glossary → The queue), from the screen on top of its tab. It follows navigation: a tab whose
 * grid opens a post is a kept post from then on, and back on the grid it's a source again.
 */
@Serializable
enum class QueueKind {
  /** A grid on a search, gone through in its grid; or a Books search, gone through in its list (#385). */
  @SerialName("source")
  Source,

  /** A grid whose search is exactly a feed (Following, For you), with at most a rating filter. */
  @SerialName("feed")
  Feed,

  /** A post on top. */
  @SerialName("post")
  KeptPost,

  /** A book on top (#385): kept from a Books search as a post is from a grid, and placed after it the same way. */
  @SerialName("book")
  KeptBook,

  /** Anything else (Settings and later screens), which the queue's rules leave alone. */
  @SerialName("other")
  Other,
  ;

  /** Whether it's kept: a kept post or a kept book, which follow their source in its block and Clear viewed clears. */
  val isKept: Boolean get() = this == KeptPost || this == KeptBook

  companion object {
    /** The kind of a tab whose top screen is a grid on [tags]. */
    fun ofGrid(tags: List<String>): QueueKind = if (feedOf(tags) != null) Feed else Source

    /**
     * The feed [tags] are exactly, or null: one feed token, and otherwise only rating tokens, which a feed allows
     * (the signed-out lock and G only add one). Any tag, order or other filter makes it a search, so a source.
     */
    fun feedOf(tags: List<String>): ApiFeed? {
      val words = tags.map { it.trim() }.filter { it.isNotEmpty() }
      val feeds = words.mapNotNull(ApiFeed::of).distinct()
      if (feeds.size != 1) return null
      return feeds.single().takeIf { words.all { ApiFeed.of(it) != null || isRating(it) } }
    }
  }
}

internal fun isRating(tag: String): Boolean = tag.removePrefix("-").startsWith("rating:", ignoreCase = true)

internal fun isOrder(tag: String): Boolean = tag.removePrefix("-").startsWith("order:", ignoreCase = true)
