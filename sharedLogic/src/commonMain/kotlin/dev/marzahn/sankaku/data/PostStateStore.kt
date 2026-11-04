package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.api.dto.PostDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Variant { Preview, Sample, File }

/** Image URLs from a refetch, newer than the ones in the paged list. */
data class PostUrls(val preview: String?, val sample: String?, val file: String?) {
  fun forVariant(variant: Variant): String? = when (variant) {
    Variant.Preview -> preview
    Variant.Sample -> sample
    Variant.File -> file
  }
}

/** The account's vote on a post (null: none) with the post's totals it gives. */
data class VoteState(val userVote: Int?, val voteCount: Int, val totalScore: Int)

/**
 * What changed about a post after it was loaded. [generation] identifies the latest favourite toggle, so an older
 * toggle's API result never overwrites a newer one; [voteGeneration] does the same for votes.
 */
data class PostOverride(
  val isFavourited: Boolean? = null,
  val favCount: Int? = null,
  val urls: PostUrls? = null,
  val inFlight: Boolean = false,
  val generation: Long = 0,
  val vote: VoteState? = null,
  val voteInFlight: Boolean = false,
  val voteGeneration: Long = 0,
)

fun PostDto.url(variant: Variant): String? = when (variant) {
  Variant.Preview -> previewUrl
  Variant.Sample -> sampleUrl
  Variant.File -> fileUrl
}

/** The loaded post with its override applied. The paged list itself is never changed. */
fun PostDto.merge(override: PostOverride?): PostDto {
  override ?: return this
  return copy(
    isFavorited = override.isFavourited ?: isFavorited,
    favCount = override.favCount ?: favCount,
    previewUrl = override.urls?.preview ?: previewUrl,
    sampleUrl = override.urls?.sample ?: sampleUrl,
    fileUrl = override.urls?.file ?: fileUrl,
    userVote = if (override.vote != null) override.vote.userVote else userVote,
    voteCount = override.vote?.voteCount ?: voteCount,
    totalScore = override.vote?.totalScore ?: totalScore,
  )
}

/** [post]'s vote state, as a vote override holds it. */
fun PostDto.voteState(): VoteState = VoteState(userVote, voteCount, totalScore)

/** [post]'s image URLs, as a URL override holds them. */
fun PostDto.urls(): PostUrls = PostUrls(previewUrl, sampleUrl, fileUrl)

/** Per-post state that changes after loading, shared by every grid and post view. */
class PostStateStore {
  private val _overrides = MutableStateFlow<Map<String, PostOverride>>(emptyMap())
  val overrides: StateFlow<Map<String, PostOverride>> = _overrides.asStateFlow()

  /** Starts a favourite toggle: returns its generation, which [setFavourite] must match. */
  fun beginToggle(id: String, value: Boolean, favCount: Int): Long {
    var generation = 0L
    _overrides.update { all ->
      val current = all[id] ?: PostOverride()
      generation = current.generation + 1
      all + (id to current.copy(isFavourited = value, favCount = favCount, inFlight = true, generation = generation))
    }
    return generation
  }

  /** Writes the outcome of the toggle [generation], unless a newer toggle started since. Returns whether it wrote. */
  fun setFavourite(id: String, value: Boolean, favCount: Int, inFlight: Boolean, generation: Long): Boolean {
    var written = false
    _overrides.update { all ->
      val current = all[id] ?: return@update all
      if (current.generation != generation) return@update all
      written = true
      all + (id to current.copy(isFavourited = value, favCount = favCount, inFlight = inFlight))
    }
    return written
  }

  /** Starts a vote: shows [vote] at once and returns its generation, which [setVote] must match. */
  fun beginVote(id: String, vote: VoteState): Long {
    var generation = 0L
    _overrides.update { all ->
      val current = all[id] ?: PostOverride()
      generation = current.voteGeneration + 1
      all + (id to current.copy(vote = vote, voteInFlight = true, voteGeneration = generation))
    }
    return generation
  }

  /** Writes the outcome of the vote [generation], unless a newer vote started since. */
  fun setVote(id: String, vote: VoteState, generation: Long) {
    _overrides.update { all ->
      val current = all[id] ?: return@update all
      if (current.voteGeneration != generation) return@update all
      all + (id to current.copy(vote = vote, voteInFlight = false))
    }
  }

  /** The account's vote as a full post reports it (lists don't carry it), unless a vote is still in flight. */
  fun setServerVote(post: PostDto) {
    _overrides.update { all ->
      val current = all[post.id] ?: PostOverride()
      if (current.voteInFlight) return@update all
      all + (post.id to current.copy(vote = post.voteState()))
    }
  }

  fun setUrls(post: PostDto) {
    _overrides.update { all -> all + (post.id to (all[post.id] ?: PostOverride()).copy(urls = post.urls())) }
  }

  /**
   * Freshly listed [posts] carry the server's state, which their overrides take, so every copy loaded earlier (a
   * full post, another grid's page) shows it too (#347). [whenAsked] is [overrides] when the list was asked for:
   * a favourite, vote or URLs that were in flight then, or changed since, keep their override, as the list may
   * predate them. A list doesn't carry the account's own vote, so that stays. Only the fields an override holds
   * change: posts without an override get none, and one with only URLs or a vote gets no favourite.
   */
  fun takeListed(posts: List<PostDto>, whenAsked: Map<String, PostOverride>) {
    _overrides.update { all ->
      posts.fold(all) { acc, post ->
        val current = acc[post.id] ?: return@fold acc
        val before = whenAsked[post.id] ?: PostOverride()
        // Only a field the override holds: one it doesn't is left to the loaded copy, which a newer full post replaces.
        val favouriteSettled = current.isFavourited != null && !before.inFlight && !current.inFlight &&
          before.generation == current.generation
        val voteSettled = !before.voteInFlight && !current.voteInFlight && before.voteGeneration == current.voteGeneration &&
          before.vote == current.vote
        val listed = post.urls()
        acc + (
          post.id to current.copy(
            isFavourited = if (favouriteSettled) post.isFavorited else current.isFavourited,
            favCount = if (favouriteSettled) post.favCount else current.favCount,
            urls = if (current.urls != null && before.urls == current.urls) {
              PostUrls(listed.preview ?: current.urls.preview, listed.sample ?: current.urls.sample, listed.file ?: current.urls.file)
            } else {
              current.urls
            },
            vote = if (voteSettled) current.vote?.copy(voteCount = post.voteCount, totalScore = post.totalScore) else current.vote,
          )
        )
      }
    }
  }

  fun clear() {
    _overrides.value = emptyMap()
  }
}
