package dev.marzahn.sankaku.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A book (the API's "pool"), from the list (`/poolseriesv2`), `/pools/{id}` or a series (`BooksApi.series`, whose
 * GraphQL query names its fields as these). Only the fields the app uses (`docs/api.md` → Books).
 */
@Serializable
data class BookDto(
  /** A string, or with an Android User-Agent a number, as post ids are; kept as a string. */
  @Serializable(with = LenientStringSerializer::class) val id: String,
  val name: String? = null,
  @SerialName("name_en") val nameEn: String? = null,
  val rating: String? = null,
  /** `active` or `pending`: a pending book's pages are pending too, and `pool:<id>` finds none of them. */
  val status: String? = null,
  /**
   * The count the site shows, which can be more than [posts] holds: on 2026-11-06, 12 of 17 books of 30 pages or more
   * returned 27 to 228 pages of 34 to 910. `pages_count` is always this minus one, so it isn't decoded.
   */
  @SerialName("post_count") val postCount: Int = 0,
  @SerialName("fav_count") val favCount: Int = 0,
  @SerialName("is_favorited") val isFavorited: Boolean = false,
  /** Null when never opened. */
  val reading: ReadingDto? = null,
  /** Signed out, a list's book can come with this and no cover URLs, as posts do. */
  @SerialName("redirect_to_signup") val redirectToSignup: Boolean = false,
  @SerialName("cover_post") val coverPost: PostDto? = null,
  /** The pages in reading order; only `/pools/{id}` fills it (empty in lists). */
  val posts: List<PostDto> = emptyList(),
  val description: String? = null,
  @SerialName("description_en") val descriptionEn: String? = null,
  @SerialName("artist_tags") val artistTags: List<TagDto> = emptyList(),
  @SerialName("genre_tags") val genreTags: List<TagDto> = emptyList(),
  /** The book's place in its series, from 1; only a series' books have it. */
  val sequence: Int? = null,
) {
  /** What to show as the title. */
  val title: String get() = nameEn?.takeIf { it.isNotBlank() } ?: name.orEmpty()

  /** What to show as the description, English first as [title] is; null when it has none. */
  val shownDescription: String? get() = descriptionEn?.takeIf { it.isNotBlank() } ?: description?.takeIf { it.isNotBlank() }
}

/**
 * Where the account stopped reading. [sequence] is the 0-based page, [currentPage] the 1-based one; [percent] how far
 * through the book that is, out of 100 (`docs/api.md` → Books).
 */
@Serializable
data class ReadingDto(
  @SerialName("current_page") val currentPage: Int? = null,
  val sequence: Int? = null,
  val percent: Double? = null,
)

/** A series in the list: one row, with [firstPool]'s cover and pages. */
@Serializable
data class SeriesSummaryDto(
  @Serializable(with = LenientStringSerializer::class) val id: String,
  val name: String? = null,
  @SerialName("name_en") val nameEn: String? = null,
  /** The series' own rating, which its books needn't share (a `rating:s` series held 5 `e` books). */
  val rating: String? = null,
  val poolCount: Int = 0,
  /** One of its books, not always the one at sequence 1 (2026-11-06). Null signed out. */
  val firstPool: BookDto? = null,
) {
  val title: String get() = nameEn?.takeIf { it.isNotBlank() } ?: name.orEmpty()
}

/** `GET /poolseriesv2`: the series (only on page 1) and the standalone books of one page. */
@Serializable
data class BookListResponse(
  val series: SeriesList = SeriesList(),
  val pools: Page<BookDto> = Page(emptyList(), Meta()),
) {
  @Serializable
  data class SeriesList(val data: List<SeriesSummaryDto> = emptyList())
}

/** A series and its books in series order; signed out, [books] is empty. */
@Serializable
data class SeriesDto(
  @Serializable(with = LenientStringSerializer::class) val id: String,
  val name: String? = null,
  @SerialName("name_en") val nameEn: String? = null,
  @SerialName("pool_count") val poolCount: Int = 0,
  val books: List<BookDto> = emptyList(),
) {
  val title: String get() = nameEn?.takeIf { it.isNotBlank() } ?: name.orEmpty()
}

@Serializable
internal data class SeriesResponse(val data: Data? = null, val errors: List<GraphQlError> = emptyList()) {
  @Serializable
  data class Data(val series: SeriesDto? = null)
}

@Serializable
internal data class GraphQlError(val message: String? = null)

/** `POST`/`DELETE /pools/{id}/favorite`: unlike a post's, it carries the new count. */
@Serializable
internal data class BookFavouriteResponse(
  val success: Boolean? = null,
  @SerialName("fav_count") val favCount: Int? = null,
)
