package dev.marzahn.sankaku.features.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.api.dto.TagSuggestion
import dev.marzahn.sankaku.data.GridPresets

/**
 * Why a search shows only G posts (#523), in the words every place uses: the empty grid, the search panel's locked
 * Rating chip and TalkBack's reading of the G badge. Null from [gOnlyReason] when nothing locks the rating.
 */
internal enum class GOnlyReason(val text: String, val action: String?, val droppedRating: String, val hint: String) {
  /**
   * Signed out the rating is locked to G; Sign in lifts it. Comes first: the sidebar's switch isn't there then, and a
   * debug run's held lock shows as [Held] once signed in.
   */
  SignedOut(
    "Signed out: G only",
    "Sign in",
    "Ratings stay G while signed out",
    "Signed out, searches show G only. Sign in to choose other ratings.",
  ),

  /** The sidebar's G only switch; Turn off is the switch's off. */
  Switch("G only is on", "Turn off", "Ratings stay G while G only is on", "G only is on. Turn it off to choose other ratings."),

  /** G only held on for a debug run (`safeOnly`), which nothing in the app turns off. */
  Held("G only is on", null, "Ratings stay G while G only is on", "G only is on for this run."),
}

/** [gOnly] is the switch or the debug lock; [canTurnOff] is false while the debug lock holds it. */
internal fun gOnlyReason(signedIn: Boolean, gOnly: Boolean, canTurnOff: Boolean): GOnlyReason? = when {
  !signedIn -> GOnlyReason.SignedOut
  !gOnly -> null
  canTurnOff -> GOnlyReason.Switch
  else -> GOnlyReason.Held
}

/**
 * The reason as one line: the G badge, its words and, when there is one, its action as a text button ("Sign in",
 * "Turn off"). The badge is only a picture: the words say the same.
 */
@Composable
internal fun GOnlyReasonLine(
  reason: GOnlyReason,
  onAction: () -> Unit,
  modifier: Modifier = Modifier,
  badge: Boolean = true,
) {
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    if (badge) RatingBadge(Rating.G, Modifier.clearAndSetSemantics {})
    Text(reason.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    reason.action?.let { TextButton(onClick = onAction) { Text(it) } }
  }
}

/**
 * The grids with an empty state of their own (#523): the sidebar's Following and Favourites as they open, with no
 * tag added. Anything narrower is a search and keeps [EmptySearch]'s chips.
 */
internal enum class EmptyFeed {
  Following,
  Favourites,
  ;

  companion object {
    /** [searched] is the route's search; a locked rating is the app's, not the user's, so it's left out. */
    fun of(searched: List<String>, me: String?, ratingLocked: Boolean): EmptyFeed? {
      val listed = (if (ratingLocked) withoutRating(searched) else searched).map(String::lowercase)
      return when {
        listed == GridPresets.following -> Following
        me != null && listed == GridPresets.favourites(me).map(String::lowercase) -> Favourites
        else -> null
      }
    }
  }
}

/**
 * The recent searches the panel lists: all of [recent], or with [gOnly] on all but those naming a tag [known] as R15
 * or R18 (excluded or not) or a rating other than G (#523). A tag not learnt yet isn't known to be either.
 */
internal fun shownRecentSearches(
  recent: List<List<String>>,
  known: Map<String, TagSuggestion>,
  gOnly: Boolean,
): List<List<String>> = if (!gOnly) recent else recent.filterNot { search -> search.any { namesNotG(it, known) } }

private fun namesNotG(tag: String, known: Map<String, TagSuggestion>): Boolean {
  Rating.of(tag)?.let { ratings -> return ratings.any { it != Rating.G } }
  return Rating.ofCode(known[tag.removePrefix("-").lowercase()]?.rating) in setOf(Rating.R15, Rating.R18)
}
