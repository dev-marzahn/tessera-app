package dev.marzahn.sankaku.queue

import kotlin.jvm.JvmInline

/**
 * What makes two searches one source (one source per search, #174): their content tags, trimmed, lower-cased, without
 * repeats and sorted. `order:` and the rating filter don't count, so `order:popularity artist_x`, `-rating:e artist_x`
 * and `artist_x` are one source; feed, favourites and every other token do. On-device data like the tags it's made of:
 * never logged.
 */
@JvmInline
value class SourceKey private constructor(val value: String) {
  companion object {
    /**
     * The key of a search on [tags], or null when nothing is left once order and rating are taken out (Browse,
     * Popular, Top): those browse everything, so they have no identity and never merge.
     */
    fun of(tags: List<String>): SourceKey? = tags.asSequence()
      .map { it.trim().lowercase() }
      .filter { it.isNotEmpty() && !isOrder(it) && !isRating(it) }
      .distinct()
      .sorted()
      .joinToString(" ")
      .takeIf { it.isNotEmpty() }
      ?.let(::SourceKey)
  }
}
