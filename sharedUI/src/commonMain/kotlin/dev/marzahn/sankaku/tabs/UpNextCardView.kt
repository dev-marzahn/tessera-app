package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.marzahn.sankaku.shell.LocalChromePadding
import dev.marzahn.sankaku.ui.components.PrimaryButton
import org.koin.compose.koinInject

/**
 * Touch: the Up next and End of the queue cards (#216, #230), which stand in for the tab's content on a page of their
 * own, with no grid behind them ([UpNextState]). Text only, so nothing on them can show a picture. [QueueSwipeHost]
 * shows them: Back, a swipe back and the pill's ‹ take them away ([UpNextState.dismiss]), with the item they stand past
 * peeking in (#248). A swipe forward does nothing.
 *
 * - **A Last chance** reads "Last chance: artist_x" with when it is deleted, and offers Keep and Let go.
 * - **A faded source** reads "Pick up artist_x again?" with its Faded row's line, and offers Open and Skip.
 * - **End of the queue** reads "You've reached the end", and offers Back to the start and Browse.
 * - **TalkBack:** the card is a pane titled "Up next" or "End of the queue", and focus moves to its title, a heading,
 *   as it appears and again for each card Skip brings. The title also carries the actions as custom actions, so they
 *   are one swipe away from where focus lands, and each button says what it acts on.
 */
@Composable
fun UpNextCardView(modifier: Modifier = Modifier, state: UpNextState = koinInject()) {
  val card = state.card ?: return
  BoxWithConstraints(
    modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.surface)
      .padding(LocalChromePadding.current)
      // Clear of the count pill, which stays below the card.
      .padding(bottom = PILL_ROOM)
      .semantics { paneTitle = if (card == UpNextCard.End) END_PANE else UP_NEXT_PANE },
  ) {
    // Centred, and scrolling where large text makes the card taller than the room (#525).
    Box(
      Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
      contentAlignment = Alignment.Center,
    ) {
      key(card) { CardContent(card, state) }
    }
  }
}

/** What one card says: the small [label] above, the [title] heading, the [detail] line, and TalkBack's [spoken] name. */
private class CardText(val label: String, val title: String, val detail: String, val spoken: String)

private fun cardText(card: UpNextCard): CardText = when (card) {
  is UpNextCard.LastChance -> CardText("Last chance", "Last chance: ${card.title}", card.detail, "Last chance: ${card.title}, ${card.detail}")
  is UpNextCard.Pickup -> CardText("Up next · faded", "Pick up ${card.title} again?", card.detail, "Up next: pick up ${card.title} again? ${card.detail}")
  UpNextCard.End -> CardText(END_PANE, END_TITLE, END_DETAIL, "$END_PANE. $END_TITLE. $END_DETAIL")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardContent(card: UpNextCard, state: UpNextState) {
  val focus = remember { FocusRequester() }
  // TalkBack lands on the title as the card appears, and again on each card Skip brings.
  LaunchedEffect(card) { focus.requestFocus() }
  val colors = MaterialTheme.colorScheme
  val text = cardText(card)
  // The swipe back's action too ([LocalQueueActions], #248), where focus lands.
  val actions = cardActions(card, state) + LocalQueueActions.current
  val chance = card is UpNextCard.LastChance
  Surface(
    modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 480.dp).fillMaxWidth().testTag(if (card == UpNextCard.End) END_CARD else UP_NEXT_CARD),
    shape = MaterialTheme.shapes.extraLarge,
    color = if (chance) colors.errorContainer else colors.surfaceContainerHigh,
    contentColor = if (chance) colors.onErrorContainer else colors.onSurface,
  ) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(text.label.uppercase(), style = MaterialTheme.typography.labelMedium)
      // The title and what's under it are one heading to TalkBack, where focus lands and the actions are.
      Column(
        Modifier
          .focusRequester(focus)
          .focusable()
          .semantics(mergeDescendants = true) {
            heading()
            contentDescription = text.spoken
            customActions = actions
          },
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(text.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(text.detail, style = MaterialTheme.typography.bodyMedium, color = if (chance) colors.onErrorContainer else colors.onSurfaceVariant)
      }
      FlowRow(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
      ) {
        when (card) {
          is UpNextCard.LastChance -> {
            PrimaryButton("Keep", state::keep, Modifier.semantics { contentDescription = "Keep ${card.title}" })
            TextButton(onClick = state::letGo, modifier = Modifier.semantics { contentDescription = "Let go of ${card.title}" }) {
              Text("Let go")
            }
          }
          is UpNextCard.Pickup -> {
            PrimaryButton("Open", state::open, Modifier.semantics { contentDescription = "Open ${card.title}" })
            TextButton(onClick = state::skip, modifier = Modifier.semantics { contentDescription = "Skip ${card.title}" }) {
              Text("Skip")
            }
          }
          UpNextCard.End -> {
            PrimaryButton(BACK_TO_START, state::backToStart, Modifier.semantics { contentDescription = "$BACK_TO_START of the queue" })
            OutlinedButton(onClick = state::browse, modifier = Modifier.semantics { contentDescription = BROWSE_LABEL }) {
              Text(BROWSE)
            }
          }
        }
      }
    }
  }
}

/** The card's actions for TalkBack, on its title: what the buttons below do. */
private fun cardActions(card: UpNextCard, state: UpNextState): List<CustomAccessibilityAction> = when (card) {
  is UpNextCard.LastChance -> listOf(
    CustomAccessibilityAction("Keep") { state.keep(); true },
    CustomAccessibilityAction("Let go") { state.letGo(); true },
  )
  is UpNextCard.Pickup -> listOf(
    CustomAccessibilityAction("Open") { state.open(); true },
    CustomAccessibilityAction("Skip") { state.skip(); true },
  )
  UpNextCard.End -> listOf(
    CustomAccessibilityAction(BACK_TO_START) { state.backToStart(); true },
    CustomAccessibilityAction(BROWSE) { state.browse(); true },
  )
}

/** The room kept clear under the card for the count pill (40 dp tall, 32 dp above the navigation bar). */
private val PILL_ROOM = 96.dp

/** The pane title TalkBack says as the card appears, and TalkBack's name for moving past the last source. */
internal const val UP_NEXT_PANE = "Up next"
internal const val UP_NEXT_ACTION = "Up next"
internal const val UP_NEXT_CARD = "Up next card"

/** End of the queue (#230): its pane title, which is also the pill's name for moving there, and its words. */
internal const val END_PANE = "End of the queue"
internal const val END_CARD = "End of the queue card"
private const val END_TITLE = "You've reached the end"
private const val END_DETAIL = "Nothing else is waiting."
private const val BACK_TO_START = "Back to the start"
private const val BROWSE = "Browse"
private const val BROWSE_LABEL = "Browse, a new grid"
