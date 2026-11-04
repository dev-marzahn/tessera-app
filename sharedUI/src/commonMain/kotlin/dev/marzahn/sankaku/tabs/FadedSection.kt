package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.fonts.icons.arrow_drop_down
import dev.marzahn.sankaku.queue.Fading
import kotlin.time.Instant

/**
 * The queue page's Faded section (#215), kept apart from the rows of live items ([queueRows]): [items] are the faded
 * sources that show, [hidden] the R15 and R18 ones G only leaves out, and [total] is every one, which "Faded · 4" counts.
 * It is collapsed unless [expanded]; [now] is what "Deleted in N days" counts from.
 *
 * - **A row** says "Deleted in 12 days" ("Last chance · Deleted in 4 days" in the last week, [Fading.LAST_CHANCE]), then
 *   "place kept" if it has one and how many kept posts faded with it. It shows a kind icon, never a picture. A tap opens
 *   it ([onOpen]: restored at the end of the session and shown); "Restore" ([onRestore]) puts it back and stays on the
 *   page; "Delete now" ([onDelete]) deletes it, with an Undo.
 * - **TalkBack** hears one node per row, with the custom actions Restore and Delete now.
 */
internal class FadedPanel(
  val items: List<FadedSource>,
  val hidden: Int,
  val total: Int,
  val expanded: Boolean,
  val now: Instant,
  val onToggle: () -> Unit,
  val onOpen: (FadedSource) -> Unit,
  val onRestore: (FadedSource) -> Unit,
  val onDelete: (FadedSource) -> Unit,
)

/** The panel's items the page lists while G only is [gOnly] and a find is [query], with how many G only leaves out. */
internal fun fadedPanel(
  faded: List<FadedSource>,
  query: String?,
  gOnly: Boolean,
  expanded: Boolean,
  now: Instant,
  onToggle: () -> Unit,
  onOpen: (FadedSource) -> Unit,
  onRestore: (FadedSource) -> Unit,
  onDelete: (FadedSource) -> Unit,
): FadedPanel? {
  val matching = if (query == null) faded else faded.filter { it.matches(query) }
  if (matching.isEmpty()) return null
  val shown = matching.filterNot { gOnly && it.adult }
  // A find opens the section, so its matches are not hidden behind the heading.
  return FadedPanel(shown, matching.size - shown.size, matching.size, expanded || query != null, now, onToggle, onOpen, onRestore, onDelete)
}

/** A faded row's second line: the countdown, then its place and kept posts. */
internal fun fadedSubtitle(faded: FadedSource, now: Instant): String {
  val detail = fadedDetail(faded, now)
  return if (Fading.isLastChance(faded.fadedAt, now)) "Last chance · $detail" else detail
}

internal fun LazyListScope.fadedSection(panel: FadedPanel?) {
  if (panel == null) return
  item(key = FADED_HEADING) { FadedHeading(panel) }
  if (!panel.expanded) return
  panel.items.forEach { faded ->
    item(key = "$FADED_PREFIX${faded.id.value}") { FadedRow(faded, panel) }
  }
  if (panel.hidden > 0) {
    item(key = FADED_HIDDEN) {
      Text(
        "Hidden by G only · ${panel.hidden}",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
      )
    }
  }
}

/** "Faded · 4" and a chevron: a tap opens or shuts the section. */
@Composable
private fun FadedHeading(panel: FadedPanel) {
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(role = Role.Button, onClickLabel = if (panel.expanded) "Collapse" else "Expand", onClick = panel.onToggle)
      .padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)
      .semantics(mergeDescendants = true) {
        heading()
        stateDescription = if (panel.expanded) "Expanded" else "Collapsed"
      },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("Faded · ${panel.total}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
    Icon(arrow_drop_down, contentDescription = null, modifier = Modifier.rotate(if (panel.expanded) 180f else 0f))
  }
}

@Composable
private fun FadedRow(faded: FadedSource, panel: FadedPanel) {
  val colors = MaterialTheme.colorScheme
  val lastChance = Fading.isLastChance(faded.fadedAt, panel.now)
  val subtitle = fadedSubtitle(faded, panel.now)
  Column(Modifier.fillMaxWidth()) {
    Row(
      Modifier
        .fillMaxWidth()
        .clickable(onClickLabel = "Open", onClick = { panel.onOpen(faded) })
        // TalkBack hears the title and the countdown as one node, with Restore and Delete now among its actions.
        .semantics {
          customActions = listOf(
            CustomAccessibilityAction("Restore") { panel.onRestore(faded); true },
            CustomAccessibilityAction("Delete now") { panel.onDelete(faded); true },
          )
        }
        .padding(start = 16.dp, end = 16.dp, top = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // No picture: a faded source holds none, and nothing here loads.
      Box(
        Modifier.size(THUMBNAIL).clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        TabIcon(faded.route, size = 24.dp, decorative = true)
      }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(faded.title, style = MaterialTheme.typography.bodyLarge, maxLines = rowTextLines(), overflow = TextOverflow.Ellipsis)
        Text(
          subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = if (lastChance) colors.error else colors.onSurfaceVariant,
          maxLines = rowTextLines().coerceAtLeast(2),
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp), horizontalArrangement = Arrangement.End) {
      TextButton(onClick = { panel.onRestore(faded) }) { Text("Restore") }
      TextButton(onClick = { panel.onDelete(faded) }) { Text("Delete now", color = colors.error) }
    }
  }
}

private val THUMBNAIL = 48.dp
private const val FADED_HEADING = "faded-heading"
private const val FADED_PREFIX = "faded-"
private const val FADED_HIDDEN = "faded-hidden-by-g-only"
