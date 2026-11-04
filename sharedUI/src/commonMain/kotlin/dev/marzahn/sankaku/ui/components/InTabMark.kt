package dev.marzahn.sankaku.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.fonts.icons.tab
import dev.marzahn.sankaku.platformIsTouch
import kotlinx.coroutines.delay

/**
 * The mark of a post open in a tab, on a grid cell and on a recommended post: its image drawn with [OpenInTabFilter]
 * (15% saturation), [InTabWash] over it and [InTabChip] in the bottom-left corner.
 */
val OpenInTabFilter: ColorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.15f) })

/** The light wash over the image of a post open in a tab, under its [InTabChip]. */
@Composable
fun InTabWash() {
  Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.22f)))
}

/**
 * A post's chip answering a background-tab open that found it already in the tab at [position] (from 1). With [again]
 * the post had been seen, and is kept anew and unseen: the chip says "Kept again" (#182).
 */
data class AlreadyTab(val postId: String, val position: Int, val serial: Int, val again: Boolean = false)

/**
 * Which chip says "Already tab N" (#130), or "Kept again" (#182): [show] it when opening a post in a background tab
 * opened nothing. It shows for [ALREADY_TAB_MS], one chip at a time; showing it again, on any post, starts over.
 */
@Stable
class AlreadyInTab {
  var shown by mutableStateOf<AlreadyTab?>(null)
    internal set

  fun show(postId: String, position: Int, again: Boolean = false) {
    shown = AlreadyTab(postId, position, (shown?.serial ?: 0) + 1, again)
  }

  /** What [postId]'s chip shows, while it shows. */
  fun of(postId: String): AlreadyTab? = shown?.takeIf { it.postId == postId }
}

/** An [AlreadyInTab] that clears itself [ALREADY_TAB_MS] after each show. */
@Composable
fun rememberAlreadyInTab(): AlreadyInTab {
  val state = remember { AlreadyInTab() }
  val shown = state.shown
  LaunchedEffect(shown) {
    if (shown == null) return@LaunchedEffect
    delay(ALREADY_TAB_MS)
    state.shown = null
  }
  return state
}

const val ALREADY_TAB_MS = 1500L

/** The plain chip's words: "Kept" on a phone, which has no tabs (#249), "In tab" on desktop, spoken the same. */
internal fun inTabLabel(touch: Boolean = platformIsTouch): String = if (touch) "Kept" else "In tab"

/** The chip answering an open that found the post at [position]: "Already item N" on a phone, "Already tab N" on desktop. */
internal fun alreadyLabel(position: Int, touch: Boolean = platformIsTouch): String =
  if (touch) "Already item $position" else "Already tab $position"

/** What TalkBack or a screen reader says for [alreadyLabel]: "Already item N" on a phone, "Already in tab N" on desktop. */
internal fun alreadySpoken(position: Int, touch: Boolean = platformIsTouch): String =
  if (touch) "Already item $position" else "Already in tab $position"

/**
 * The "In tab" chip ("Kept" on a phone, [inTabLabel]) of a post open in a tab. With [already] it swells into "Already
 * tab N" ("Already item N", [alreadyLabel]) in the inverse colours, with a pulse each time it's shown again, and a
 * polite live region says "Already in tab N" ([alreadySpoken]). With animations off it changes at once.
 */
@Composable
fun InTabChip(modifier: Modifier = Modifier, already: AlreadyTab? = null, touch: Boolean = platformIsTouch) {
  val color by animateColorAsState(
    if (already != null) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.secondaryContainer,
    effectsSpec(),
  )
  val contentColor by animateColorAsState(
    if (already != null) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSecondaryContainer,
    effectsSpec(),
  )
  val pulse = remember { Animatable(1f) }
  val swell = spatialSpec<Float>()
  LaunchedEffect(already?.serial) {
    if (already == null) return@LaunchedEffect
    pulse.animateTo(PULSE_SCALE, swell)
    pulse.animateTo(1f, swell)
  }
  // A recommended post's 96 dp thumb has no room for the icon and "Already tab 37" at full size: the icon goes and the
  // text steps down to fit.
  BoxWithConstraints(
    modifier.clearAndSetSemantics {
      // A live region only while it answers, so the plain mark coming back, or appearing, says nothing.
      if (already != null) {
        liveRegion = LiveRegionMode.Polite
        contentDescription = if (already.again) KEPT_AGAIN else alreadySpoken(already.position, touch)
      } else {
        contentDescription = inTabLabel(touch)
      }
    },
  ) {
    val roomy = maxWidth >= ALREADY_ROOMY
    Surface(
      shape = RoundedCornerShape(50),
      color = color,
      contentColor = contentColor,
      shadowElevation = 2.dp,
      modifier = Modifier
        .graphicsLayer {
          scaleX = pulse.value
          scaleY = pulse.value
          transformOrigin = TransformOrigin(0f, 1f)
        },
    ) {
      Row(
        Modifier.animateContentSize(spatialSpec()).height(24.dp).padding(start = 7.dp, end = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        if (already == null || roomy) Icon(tab, contentDescription = null, modifier = Modifier.size(14.dp))
        val style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
        Text(
          when {
            already == null -> inTabLabel(touch)
            already.again -> KEPT_AGAIN
            else -> alreadyLabel(already.position, touch)
          },
          style = style,
          maxLines = 1,
          autoSize = TextAutoSize.StepBased(minFontSize = MIN_FONT, maxFontSize = style.fontSize),
        )
      }
    }
  }
}

/** What the chip and the cell say when a seen kept post was kept again. */
const val KEPT_AGAIN = "Kept again"

private const val PULSE_SCALE = 1.12f

// The width "Already tab 137" needs with the icon, at full size.
private val ALREADY_ROOMY = 120.dp
private val MIN_FONT = 9.sp
