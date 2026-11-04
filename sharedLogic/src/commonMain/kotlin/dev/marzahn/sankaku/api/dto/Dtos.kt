package dev.marzahn.sankaku.api.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Instant

/** Named to avoid clashing with `Route.Post`. Only the fields the app uses. */
@Serializable
data class PostDto(
  /** A string, or with an Android User-Agent a number (docs/api.md → Browser fingerprint); kept as a string. */
  @Serializable(with = LenientStringSerializer::class) val id: String,
  val md5: String,
  val author: Author? = null,
  val rating: String,
  val status: String,
  @SerialName("file_type") val fileType: String? = null,
  @SerialName("file_ext") val fileExt: String? = null,
  @SerialName("preview_url") val previewUrl: String? = null,
  @SerialName("preview_width") val previewWidth: Int? = null,
  @SerialName("preview_height") val previewHeight: Int? = null,
  @SerialName("sample_url") val sampleUrl: String? = null,
  @SerialName("sample_width") val sampleWidth: Int? = null,
  @SerialName("sample_height") val sampleHeight: Int? = null,
  @SerialName("file_url") val fileUrl: String? = null,
  @SerialName("width") val fileWidth: Int? = null,
  @SerialName("height") val fileHeight: Int? = null,
  @SerialName("file_size") val fileSize: Long? = null,
  /** Seconds, fractional, on videos and GIFs in lists too; null or 0 on images (discovery step 15). */
  @SerialName("video_duration") val videoDuration: Double? = null,
  @SerialName("is_favorited") val isFavorited: Boolean = false,
  @SerialName("fav_count") val favCount: Int = 0,
  @SerialName("vote_count") val voteCount: Int = 0,
  @SerialName("total_score") val totalScore: Int = 0,
  /** The account's own vote, 1–5; only a full post (`/posts/{id}`) carries it, null when it hasn't voted. */
  @SerialName("user_vote") val userVote: Int? = null,
  @SerialName("parent_id") @Serializable(with = LenientStringSerializer::class) val parentId: String? = null,
  @SerialName("has_children") val hasChildren: Boolean = false,
  /** Signed out, most posts come with this set and no image URLs at all; refetching them doesn't help. */
  @SerialName("redirect_to_signup") val redirectToSignup: Boolean = false,
  /** In lists only 5 tags; `/posts/{id}` (`PostsRepository.fullPost`) has them all. */
  val tags: List<TagDto> = emptyList(),
  /** Every tag's search name, even in lists, but without type or count. */
  @SerialName("tag_names") val tagNames: List<String> = emptyList(),
  @SerialName("created_at") @Serializable(with = SankakuInstantSerializer::class) val createdAt: Instant? = null,
) {
  @Serializable
  data class Author(val name: String, val avatar: String? = null)
}

/** [name] is the display name ("Blue Hair"); [tagName] is what a search uses ("blue_hair"). */
@Serializable
data class TagDto(
  val name: String,
  val type: Int = 0,
  @SerialName("tagName") val tagName: String? = null,
  @SerialName("post_count") val postCount: Int? = null,
  /** Needed to follow the tag; a full post's tags have it (discovery step 11). */
  @Serializable(with = LenientStringSerializer::class) val id: String? = null,
  /** Whether the signed-in user follows the tag; false when signed out (discovery `d11-tags-is-following`). */
  @SerialName("is_following") val isFollowing: Boolean = false,
) {
  val category: TagType get() = TagType.fromId(type)

  /** Older responses and test fixtures have only `name`. */
  val searchName: String get() = tagName ?: name

  /**
   * What to show: [name] ("Coconut (Girls und Panzer)"), or, where that is only the search name in some case
   * ("coconut_(girls_und_panzer)", "Jien_(Nikke)"), with spaces for its underscores ([TagNames.shown]). Tag chips and
   * post titles use it.
   */
  val displayName: String get() = TagNames.shown(name, tagName)
}

/** One autocomplete entry (discovery `d1-autocomplete`). The search uses [tagName]; [name] is how the site shows it. */
@Serializable
data class TagSuggestion(
  val tagName: String,
  val name: String = tagName,
  @SerialName("name_en") val nameEn: String? = null,
  val type: Int = 0,
  val count: Int? = null,
  @SerialName("post_count") val postCount: Int? = null,
  /** The tag's age rating, `s`, `q` or `e` (with `show_rating=true`). */
  val rating: String? = null,
) {
  val category: TagType get() = TagType.fromId(type)
  val posts: Int? get() = postCount ?: count

  /** This, with what [older] knew that this doesn't (a post's tag has no `name_en` or rating). */
  fun over(older: TagSuggestion?): TagSuggestion = if (older == null) this else copy(
    nameEn = nameEn ?: older.nameEn,
    count = count ?: older.count,
    postCount = postCount ?: older.postCount,
    rating = rating ?: older.rating,
  )

  /**
   * What to show ("Blue Hair"); a search still uses [tagName] ("blue_hair"). A name that is only the search key reads
   * as a post's tag chip does ([TagNames.shown]). Without the leading `-`. Search chips and source titles use it.
   */
  val displayName: String get() = TagNames.shown(nameEn ?: name, tagName).removePrefix("-")

  internal fun excluded() = if (tagName.startsWith("-")) this else copy(tagName = "-$tagName")
}

@Serializable
data class Page<T>(val data: List<T>, val meta: Meta)

@Serializable
data class Meta(val next: String? = null)

/** The signed-in user. `id` arrives as a number from the login host and as a string from the API. */
@Serializable
data class UserSummary(
  @Serializable(with = LenientStringSerializer::class) val id: String,
  val name: String,
  @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
internal data class UserResponse(val user: UserSummary)

/** From the token exchange and the refresh. Both TTLs are seconds. */
@Serializable
internal data class TokenResponse(
  val success: Boolean? = null,
  @SerialName("access_token") val accessToken: String? = null,
  @SerialName("refresh_token") val refreshToken: String? = null,
  @SerialName("access_token_ttl") val accessTokenTtl: Long? = null,
  @SerialName("refresh_token_ttl") val refreshTokenTtl: Long? = null,
  @SerialName("current_user") val currentUser: UserSummary? = null,
)

@Serializable
internal data class VoteResponse(
  val success: Boolean? = null,
  @SerialName("vote_count") val voteCount: Int? = null,
)

@Serializable
internal data class FavouriteResponse(
  val success: Boolean? = null,
  @SerialName("post_id") @Serializable(with = LenientStringSerializer::class) val postId: String? = null,
)

/** A write's `{success}` answer, such as following a tag. */
@Serializable
internal data class SuccessResponse(val success: Boolean = false)

/** Decodes `created_at`, a Ruby-style `{json_class, s, n}` object (seconds and nanoseconds). */
object SankakuInstantSerializer : KSerializer<Instant> {
  @Serializable
  private data class Surrogate(val s: Long, val n: Long = 0)

  override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

  override fun deserialize(decoder: Decoder): Instant {
    val raw = decoder.decodeSerializableValue(Surrogate.serializer())
    return Instant.fromEpochSeconds(raw.s, raw.n)
  }

  override fun serialize(encoder: Encoder, value: Instant) {
    encoder.encodeSerializableValue(
      Surrogate.serializer(),
      Surrogate(value.epochSeconds, value.nanosecondsOfSecond.toLong()),
    )
  }
}

/** Accepts a JSON string or number and keeps it as a string. */
object LenientStringSerializer : KSerializer<String> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientString", PrimitiveKind.STRING)

  override fun deserialize(decoder: Decoder): String {
    val element = (decoder as JsonDecoder).decodeJsonElement()
    return (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull ?: element.toString()
  }

  override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}
