package dev.marzahn.sankaku.features.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.google.fonts.icons.edit
import com.google.fonts.icons.favorite
import com.google.fonts.icons.local_fire_department
import com.google.fonts.icons.search_off
import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.data.GridPresets
import dev.marzahn.sankaku.features.auth.SignInController
import dev.marzahn.sankaku.features.post.TagFollowed
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.dto.TagSuggestion
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.data.SearchTags
import dev.marzahn.sankaku.data.TagsRepository
import dev.marzahn.sankaku.searchTags
import dev.marzahn.sankaku.shell.LocalOpenSearch
import dev.marzahn.sankaku.shell.signedInName
import dev.marzahn.sankaku.ui.components.TonalButton
import org.koin.compose.koinInject

/**
 * The tokens of a search that found nothing, as the empty grid lists them (#135): the [unknown] tags (included tags
 * the site answered without, see [TagsRepository.unknown]) first, then the [others] in the search's order, filters
 * first. A locked rating isn't listed, since removing it changes nothing.
 */
internal data class EmptySearchTokens(val unknown: List<String>, val others: List<String>) {
  val all: List<String> get() = unknown + others

  companion object {
    /** [searched] is the route's search ([Route.searchTags]); [unknownKeys] are keys without `-`, lower case. */
    fun of(searched: List<String>, unknownKeys: Set<String>, ratingLocked: Boolean): EmptySearchTokens {
      val listed = if (ratingLocked) withoutRating(searched) else searched
      val (unknown, others) = listed.partition { tag ->
        !tag.startsWith("-") && !SearchTags.isFilter(tag) && tag.lowercase() in unknownKeys
      }
      return EmptySearchTokens(unknown, others)
    }
  }
}

/** The line that names the unknown tags: `“zzqq” isn't a known tag`; null when there are none. */
internal fun unknownTagsLine(unknown: List<String>): String? {
  if (unknown.isEmpty()) return null
  val names = unknown.map { "“$it”" }
  val listed = if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " and " + names.last()
  return if (names.size == 1) "$listed isn't a known tag" else "$listed aren't known tags"
}

/**
 * An empty grid's message (#135, option A): every token of [route]'s search as a removable chip, unknown tags first
 * and flagged, and Edit search. Removing a token opens the search without it with `goTo`, so Back returns here; Edit
 * search opens the search panel with this search as the draft. It looks up the search's tags (as the top bar does,
 * so no request is repeated) to tell an unknown tag from one not learnt yet. Over the tag limit (#520) the same, with
 * [title] saying the limit.
 */
@Composable
internal fun EmptySearch(
  route: Route.Grid,
  modifier: Modifier = Modifier,
  title: String = "No posts for this search",
  navigator: Navigator = koinInject(),
  tags: TagsRepository = koinInject(),
  session: AuthSession = koinInject(),
  privacy: PrivacySettings = koinInject(),
  signIn: SignInController = koinInject(),
) {
  val known by tags.known.collectAsStateWithLifecycle()
  val unknown by tags.unknown.collectAsStateWithLifecycle()
  val signedIn by session.isSignedIn.collectAsStateWithLifecycle()
  val gOnly by privacy.gOnly.collectAsStateWithLifecycle()
  val searched = route.searchTags
  val me = session.signedInName()
  val locked = ratingLocked(signedIn, gOnly)
  // Locked, the line says why only G posts show, and its action lifts the lock (#523).
  val reason = gOnlyReason(signedIn, gOnly, canTurnOff = !privacy.gOnlyForced)?.takeIf { locked }
  val onReasonAction = { if (signedIn) privacy.setGOnly(false) else signIn.open() }
  val feed = EmptyFeed.of(searched, me, locked)
  if (feed != null) {
    EmptyFeedContent(
      feed,
      reason = reason,
      onReasonAction = onReasonAction,
      onBrowse = { navigator.goTo(Route.Grid(GridPresets.popular)) },
      modifier = modifier,
    )
    return
  }
  LaunchedEffect(searched) { tags.lookUp(withoutRating(searched)) }
  EmptySearchContent(
    tokens = EmptySearchTokens.of(searched, unknown, locked),
    known = known,
    me = me,
    onRemove = { token -> navigator.goTo(Route.Grid(searched.filterNot { it == token })) },
    onEdit = LocalOpenSearch.current,
    modifier = modifier,
    title = title,
    reason = reason,
    onReasonAction = onReasonAction,
  )
}

/**
 * The bare Following or Favourites grid with no posts (#523): what fills it and how, with Browse Popular to find
 * something to follow or favourite, and why only G posts show when that's locked. No chips: there's nothing to remove.
 */
@Composable
internal fun EmptyFeedContent(
  feed: EmptyFeed,
  reason: GOnlyReason?,
  onReasonAction: () -> Unit,
  onBrowse: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Locked, R15/R18 posts may be there unseen: the title doesn't claim there are none (#533 review).
  val locked = reason != null
  val (icon, title, body) = when (feed) {
    EmptyFeed.Following -> Triple(
      TagFollowed,
      if (locked) "No G posts in Following" else "Nothing in Following yet",
      "Follow tags to see their new posts here. Open a tag from a post's details and choose Follow.",
    )
    EmptyFeed.Favourites -> Triple(
      favorite,
      if (locked) "No G favourites to show" else "No favourites yet",
      if (platformIsTouch) "Posts you favourite show here. Tap ♡ on a post, or long-press its picture."
      else "Posts you favourite show here. Click ♡ on a post, or press F.",
    )
  }
  Column(
    modifier.widthIn(max = 480.dp).padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
    Text(
      title,
      style = MaterialTheme.typography.titleMedium,
      textAlign = TextAlign.Center,
      modifier = Modifier.semantics { heading() },
    )
    Text(
      body,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    reason?.let { GOnlyReasonLine(it, onReasonAction) }
    TonalButton("Browse Popular", local_fire_department, onClick = onBrowse, modifier = Modifier.padding(top = 4.dp))
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmptySearchContent(
  tokens: EmptySearchTokens,
  known: Map<String, TagSuggestion>,
  me: String?,
  onRemove: (String) -> Unit,
  onEdit: () -> Unit,
  modifier: Modifier = Modifier,
  title: String = "No posts for this search",
  /** Why only G posts show, when the rating is locked; its action is [onReasonAction] (#523). */
  reason: GOnlyReason? = null,
  onReasonAction: () -> Unit = {},
) {
  Column(
    modifier.widthIn(max = 480.dp).padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(search_off, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    unknownTagsLine(tokens.unknown)?.let {
      Text(
        it,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
    reason?.let { GOnlyReasonLine(it, onReasonAction) }
    if (tokens.all.isNotEmpty()) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        tokens.unknown.forEach { RemovableSearchToken(it, known, me, onRemove = { onRemove(it) }, flagged = true) }
        tokens.others.forEach { RemovableSearchToken(it, known, me, onRemove = { onRemove(it) }) }
      }
    }
    TonalButton("Edit search", edit, onClick = onEdit, modifier = Modifier.padding(top = 4.dp))
  }
}
