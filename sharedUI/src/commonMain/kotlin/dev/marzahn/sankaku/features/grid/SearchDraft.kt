package dev.marzahn.sankaku.features.grid

import dev.marzahn.sankaku.data.SearchTags
import dev.marzahn.sankaku.api.Feed as ApiFeed

/** A post rating as the site badges it. A search can let through any set of them (see [Rating.tag]). */
enum class Rating(val code: String, val badge: String, val label: String) {
  G("s", "G", "General"),
  R15("q", "R15", "Questionable"),
  R18("e", "R18", "Explicit"),
  ;

  val tag: String get() = "rating:$code"

  companion object {
    private val all = entries.toSet()

    /** A tag's or post's age rating from its code (`s`, `q`, `e`, or the long forms); null for anything else. */
    fun ofCode(code: String?): Rating? = code?.let { of("rating:$it")?.singleOrNull() }

    /** The ratings [tag] lets through: `rating:s` is G, `-rating:q` is G and R18; null for any other tag. */
    fun of(tag: String): Set<Rating>? {
      val excluded = tag.startsWith("-")
      val body = tag.removePrefix("-").lowercase()
      if (!body.startsWith("rating:")) return null
      val rating = when (body.removePrefix("rating:")) {
        "s", "safe" -> G
        "q", "questionable" -> R15
        "e", "explicit" -> R18
        else -> return null
      }
      return if (excluded) all - rating else setOf(rating)
    }

    /**
     * The one tag for [ratings]: none for none or all three, `rating:s` for one, and for two the third excluded
     * (`-rating:q` for G and R18; discovery `d1-rating-exclude`).
     */
    fun tag(ratings: Set<Rating>): String? = when (ratings.size) {
      1 -> ratings.single().tag
      2 -> "-" + (all - ratings).single().tag
      else -> null
    }

    /** What [tags] let through, in [entries] order; empty means every rating. */
    fun inTags(tags: List<String>): Set<Rating> {
      val allowed = tags.mapNotNull(::of).fold(all) { left, next -> left intersect next }
      return if (allowed == all) emptySet() else entries.filter { it in allowed }.toSet()
    }

    /** Whether [tag] is a rating tag, known or not (`rating:g`, `-rating:q`), so a rating choice replaces it. */
    fun isRatingTag(tag: String): Boolean = tag.removePrefix("-").startsWith("rating:", ignoreCase = true)
  }
}

/** One choice of a [Filter]: its tag and its label. */
data class FilterOption(val tag: String, val label: String) {
  fun matches(word: String): Boolean = word.equals(tag, ignoreCase = true)
}

/**
 * A search setting that is one tag out of a fixed list, picked from a menu instead of typed. No choice is the API's
 * default, named by [anyLabel].
 */
enum class Filter(val prefix: String, val label: String, val anyLabel: String, val options: List<FilterOption>) {
  Feed("feed:", "Feed", "All posts", ApiFeed.entries.map { FilterOption(it.tag, it.label) }),
  Order(
    "order:", "Order", "Newest",
    listOf(
      FilterOption("order:popularity", "Popularity"),
      FilterOption("order:quality", "Quality"),
      FilterOption("order:score", "Score"),
      FilterOption("order:favcount", "Favourites"),
      FilterOption("order:random", "Random"),
    ),
  ),
  Threshold(
    "threshold:", "Minimum score", "Any score",
    (1..5).map { FilterOption("threshold:$it", "Score $it+") },
  ),
  ;

  fun optionFor(word: String): FilterOption? = options.firstOrNull { it.matches(word) }

  companion object {
    fun optionFor(word: String): Pair<Filter, FilterOption>? =
      entries.firstNotNullOfOrNull { filter -> filter.optionFor(word)?.let { filter to it } }
  }
}

/**
 * The search panel's favourites filter (#90): the signed-in user's own favourites only (`fav:<name>`, as the
 * Favourites grid searches) or left out (`-fav:<name>`, discovery `d4-favourites-hide`). No choice is every post.
 */
enum class Favourites(val label: String) {
  Only("Only my favourites"),
  Hide("Hide my favourites"),
  ;

  fun tag(name: String): String = (if (this == Hide) "-" else "") + "fav:$name"

  companion object {
    const val LABEL = "Favourites"
    const val ANY_LABEL = "All posts"

    /** [tag]'s choice when it names [name]'s favourites; null for anything else, another user's included. */
    fun of(tag: String, name: String?): Favourites? = when {
      name == null -> null
      tag.equals(Only.tag(name), ignoreCase = true) -> Only
      tag.equals(Hide.tag(name), ignoreCase = true) -> Hide
      else -> null
    }

    /**
     * A short label for any user's favourites tag where the name isn't known, as tab titles show it: "Favourites" for
     * `fav:<name>`, "Not favourites" for `-fav:<name>`; null for other tags. Keeps the user's name off the screen.
     */
    fun shortLabel(tag: String): String? = when {
      tag.startsWith("fav:", ignoreCase = true) -> LABEL
      tag.startsWith("-fav:", ignoreCase = true) -> "Not favourites"
      else -> null
    }
  }
}

/**
 * The search being edited in the search panel: its tags as chips, the [ratings], the [Filter] choices and the
 * [favourites] filter, kept apart so choosing from a menu replaces any chip it stands for. [me] is the signed-in user's
 * name, which the favourites filter's tag holds; signed out it's null and `fav:` tags stay chips. Nothing is searched
 * until [tags] is submitted.
 */
data class SearchDraft(
  val chips: List<String> = emptyList(),
  val ratings: Set<Rating> = emptySet(),
  val filters: Map<Filter, FilterOption> = emptyMap(),
  val favourites: Favourites? = null,
  val me: String? = null,
) {
  /**
   * The search, filter tokens first ([SearchTags.filtersFirst]): the favourites filter, the rating tag, the filters in
   * [Filter] order and any filter chips, then the content chips.
   */
  val tags: List<String>
    get() = SearchTags.filtersFirst(
      listOfNotNull(me?.let { favourites?.tag(it) }, Rating.tag(ratings)) + Filter.entries.mapNotNull { filters[it]?.tag } + chips,
    )

  /**
   * Adds each whitespace-separated word as a chip, skipping ones already there; a rating tag sets [ratings], a
   * filter's tag sets that filter, and `fav:<me>` or `-fav:<me>` sets [favourites]. A filter chip the menus don't know
   * (`order:id`, another user's `fav:`) goes before the content chips, so the line still ends with them.
   */
  fun add(text: String): SearchDraft = text.split(WHITESPACE).filter(String::isNotEmpty).fold(this) { draft, word ->
    val ratings = Rating.of(word)
    val choice = Filter.optionFor(word)
    val favourites = Favourites.of(word, me)
    when {
      ratings != null -> draft.withRatings(ratings)
      choice != null -> draft.with(choice.first, choice.second)
      favourites != null -> draft.withFavourites(favourites)
      word in draft.chips -> draft
      else -> draft.copy(chips = SearchTags.filtersFirst(draft.chips + word))
    }
  }

  /** The same search read with [me] as the user's name: their `fav:` tag becomes the filter, or a chip again. */
  fun withMe(me: String?): SearchDraft = of(tags, me)

  /** Sets the favourites filter (`null` for every post), dropping chips that stand for it. */
  fun withFavourites(favourites: Favourites?): SearchDraft = copy(
    chips = chips.filterNot { Favourites.of(it, me) != null },
    favourites = favourites,
  )

  fun remove(chip: String): SearchDraft = copy(chips = chips - chip)

  /** Sets [ratings] (empty or all three for every rating), dropping rating chips such as `rating:g`. */
  fun withRatings(ratings: Set<Rating>): SearchDraft = copy(
    chips = chips.filterNot(Rating::isRatingTag),
    ratings = if (ratings.size == Rating.entries.size) emptySet() else ratings,
  )

  /** Sets [filter] to [option] (`null` for its default), dropping other chips with its prefix. */
  fun with(filter: Filter, option: FilterOption?): SearchDraft = copy(
    chips = chips.filterNot { it.startsWith(filter.prefix, ignoreCase = true) },
    filters = if (option == null) filters - filter else filters + (filter to option),
  )

  companion object {
    private val WHITESPACE = Regex("\\s+")

    /** The draft for [tags], with [me] (the signed-in user's name) to recognise the favourites filter. */
    fun of(tags: List<String>, me: String? = null): SearchDraft = SearchDraft(me = me).add(tags.joinToString(" "))
  }
}
