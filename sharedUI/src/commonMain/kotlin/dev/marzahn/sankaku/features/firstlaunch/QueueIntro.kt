package dev.marzahn.sankaku.features.firstlaunch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.fonts.icons.bookmark
import com.google.fonts.icons.chevron_right
import com.google.fonts.icons.undo
import com.google.fonts.icons.view_agenda
import dev.marzahn.sankaku.ui.components.focusRing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val QueueIntroTag = "queue-intro"
internal const val QueueIntroStartTag = "queue-intro-start"

internal const val QueueIntroTitle = "Your queue"

/** The page's four points (#559), in order: each a row with its icon. */
internal val QueueIntroPoints = listOf(
  "Every search is an item in your queue. The count at the bottom shows where you are: 2 / 5. Tap it to see them all.",
  "Long-press a post to keep it. Kept posts wait right after their search.",
  "A tag you tap opens in front. Back, or finishing it, brings you back to where you were.",
  "Swipe sideways to move through the queue.",
)

/** Settings → Queue's row that shows the page again (#559). */
internal const val QueueIntroSettingsLabel = "How the queue works"

/**
 * Whether the "Your queue" page shows (#559): once on a phone, right after the first-launch screen's Continue (an
 * install that had accepted it before never sees it unasked), and again from Settings → Queue. Not saved: the page is
 * a one-off, and an app closed while it shows doesn't bring it back.
 */
class QueueIntro {
  private val _shown = MutableStateFlow(false)
  val shown: StateFlow<Boolean> = _shown.asStateFlow()

  fun show() {
    _shown.value = true
  }

  fun close() {
    _shown.value = false
  }
}

/**
 * The "Your queue" page (#559, option 3 of `.scratch/queue-onboarding/mockup.html`): what the queue is, in four points,
 * with a small queue of a search, a kept post and a tag opened in front, then Start ([onStart]). Laid out as the
 * first-launch screen is: centred when it fits, scrolling on a short window or with large text.
 */
@Composable
internal fun QueueIntroScreen(onStart: () -> Unit) {
  Surface(Modifier.fillMaxSize().testTag(QueueIntroTag), color = MaterialTheme.colorScheme.background) {
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).fillMaxWidth(),
      ) {
        Column(
          verticalArrangement = Arrangement.spacedBy(16.dp),
          modifier = Modifier.padding(24.dp).widthIn(max = 480.dp).fillMaxWidth(),
        ) {
          Text(QueueIntroTitle, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
          Point(view_agenda, QueueIntroPoints[0])
          QueueSketch(Modifier.padding(start = 52.dp))
          Point(bookmark, QueueIntroPoints[1])
          Point(undo, QueueIntroPoints[2])
          Point(chevron_right, QueueIntroPoints[3])
          Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.CenterEnd) {
            Button(onClick = onStart, modifier = Modifier.focusRing(ButtonDefaults.shape).testTag(QueueIntroStartTag)) {
              Text("Start")
            }
          }
        }
      }
    }
  }
}

/** One point: its icon in a tile, then its words. The icon says nothing to TalkBack: the words say it all. */
@Composable
private fun Point(icon: ImageVector, text: String) {
  Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
    Box(
      Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
      contentAlignment = Alignment.Center,
    ) {
      Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
    }
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
  }
}

/**
 * A queue of four rows, as the queue page lists them: a tag tapped on a kept post, opened in front of its block (the
 * current item), the search the post was kept from, the post (indented, in its block), and another search.
 * Decoration: TalkBack skips it, the points say what it shows.
 */
@Composable
private fun QueueSketch(modifier: Modifier = Modifier) {
  Column(modifier.clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    SketchRow("Tree · tapped on the kept post", current = true, indent = false)
    SketchRow("Landscape", current = false, indent = false)
    SketchRow("Kept post", current = false, indent = true)
    SketchRow("Sky", current = false, indent = false)
  }
}

@Composable
private fun SketchRow(text: String, current: Boolean, indent: Boolean) {
  val colors = MaterialTheme.colorScheme
  Box(
    Modifier
      .padding(start = if (indent) 16.dp else 0.dp)
      .fillMaxWidth()
      .background(if (current) colors.primaryContainer else colors.surfaceContainerHigh, RoundedCornerShape(6.dp))
      .padding(horizontal = 10.dp, vertical = 5.dp),
  ) {
    Text(
      text,
      style = MaterialTheme.typography.labelMedium,
      color = if (current) colors.onPrimaryContainer else colors.onSurfaceVariant,
    )
  }
}
