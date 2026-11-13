package dev.marzahn.sankaku.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.currentCoroutineContext
import com.google.fonts.icons.favorite_filled
import com.google.fonts.icons.heart_broken

/**
 * The heart a grid cell's Favourite action (TalkBack; a double-tap until #324) leaves on it (Android), and F or the ♡
 * on the viewer's picture ([FavouriteBursts]; desktop #419, phone #469): filled when the post became [favourited], broken when it
 * stopped being. It pops in on the fast spatial spring, fades out on the slow effects spring, then calls [onFinished].
 * White over a blurred shadow, so it reads on light and busy images alike. It plays once; the caller restarts it with
 * `key`. Tests find it by [FAVOURITE_BURST_TAG] and read its state from [BurstFavourited]; TalkBack doesn't, as the
 * control that toggled says it.
 */
@Composable
fun FavouriteBurst(favourited: Boolean, onFinished: () -> Unit, modifier: Modifier = Modifier, size: Dp = BurstSize) {
  val finished by rememberUpdatedState(onFinished)
  val pop = fastSpatialSpec<Float>()
  val fade = effectsSpec<Float>(slow = true)
  val scale = remember { Animatable(0f) }
  val alpha = remember { Animatable(1f) }
  LaunchedEffect(Unit) {
    scale.animateTo(1f, pop)
    alpha.animateTo(0f, fade)
    finished()
  }
  val icon = if (favourited) favorite_filled else heart_broken
  Box(
    modifier
      .size(size)
      .testTag(FAVOURITE_BURST_TAG)
      .semantics {
        hideFromAccessibility()
        this[BurstFavourited] = favourited
      }
      .graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        this.alpha = alpha.value
      },
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = null, tint = Color.Black.copy(alpha = 0.45f), modifier = Modifier.size(size).blur(6.dp))
    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size))
  }
}

/**
 * Plays a [FavouriteBurst] for each state in [hearts] (true: favourited, false: unfavourited), over what [modifier]
 * places it on; a new heart restarts it. With animations off (a motion scale of 0) it plays nothing: the toggle itself
 * shows the state. The desktop viewer's picture (#419), the phone viewer's (#469) and the book reader's strip (#438), at
 * [PictureBurstSize].
 */
@Composable
fun FavouriteBursts(hearts: Flow<Boolean>, modifier: Modifier = Modifier, size: Dp = BurstSize) {
  var burst by remember { mutableStateOf<Burst?>(null) }
  LaunchedEffect(hearts) {
    hearts.collect { favourited ->
      if ((currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f) == 0f) return@collect
      burst = Burst(favourited, (burst?.id ?: 0) + 1)
    }
  }
  burst?.let {
    key(it.id) { FavouriteBurst(it.favourited, onFinished = { burst = null }, modifier, size) }
  }
}

/** One heart of [FavouriteBursts]: its state, and [id], new for each, so the same state twice plays twice. */
private data class Burst(val favourited: Boolean, val id: Int)

/** The test tag of a playing [FavouriteBurst]. */
const val FAVOURITE_BURST_TAG = "FavouriteBurst"

/** Whether a playing [FavouriteBurst] shows the filled heart (true) or the broken one (false). For tests only. */
val BurstFavourited = SemanticsPropertyKey<Boolean>("BurstFavourited")

private val BurstSize = 56.dp

/** The burst over a whole picture (the viewer's, the reader's strip): the cell's 56 dp heart was lost at that size. */
val PictureBurstSize = 96.dp
