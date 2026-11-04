package dev.marzahn.sankaku.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.google.fonts.icons.favorite_filled
import com.google.fonts.icons.heart_broken

/**
 * The heart a grid cell's Favourite action (TalkBack; a double-tap until #324) leaves on it (Android): filled when the post became [favourited], broken when it
 * stopped being. It pops in on the fast spatial spring, fades out on the slow effects spring, then calls [onFinished].
 * White over a blurred shadow, so it reads on light and busy images alike. It plays once; the caller restarts it with
 * `key`.
 */
@Composable
fun FavouriteBurst(favourited: Boolean, onFinished: () -> Unit, modifier: Modifier = Modifier) {
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
      .size(BurstSize)
      .graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        this.alpha = alpha.value
      },
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = null, tint = Color.Black.copy(alpha = 0.45f), modifier = Modifier.size(BurstSize).blur(6.dp))
    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(BurstSize))
  }
}

private val BurstSize = 56.dp
