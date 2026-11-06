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
import com.google.fonts.icons.edit
import com.google.fonts.icons.search_off
import dev.marzahn.sankaku.Navigator
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
 * so no request is repeated) to tell an unknown tag from one not learnt yet.
 */
@Composable
internal fun EmptySearch(
  route: Route.Grid,
  modifier: Modifier = Modifier,
  navigator: Navigator = koinInject(),
  tags: TagsRepository = koinInject(),
  session: AuthSession = koinInject(),
  privacy: PrivacySettings = koinInject(),
) {
  val known by tags.known.collectAsStateWithLifecycle()
  val unknown by tags.unknown.collectAsStateWithLifecycle()
  val signedIn by session.isSignedIn.collectAsStateWithLifecycle()
  val gOnly by privacy.gOnly.collectAsStateWithLifecycle()
  val searched = route.searchTags
  LaunchedEffect(searched) { tags.lookUp(withoutRating(searched)) }
  EmptySearchContent(
    tokens = EmptySearchTokens.of(searched, unknown, ratingLocked(signedIn, gOnly)),
    known = known,
    me = session.signedInName(),
    onRemove = { token -> navigator.goTo(Route.Grid(searched.filterNot { it == token })) },
    onEdit = LocalOpenSearch.current,
    modifier = modifier,
  )
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
) {
  Column(
    modifier.widthIn(max = 480.dp).padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(search_off, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
    Text("No posts for this search", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    unknownTagsLine(tokens.unknown)?.let {
      Text(
        it,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
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
