package dev.marzahn.sankaku.features.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.google.fonts.icons.logout
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.AuthState
import dev.marzahn.sankaku.ui.components.ButtonHint
import dev.marzahn.sankaku.ui.components.LiveAnnouncement
import dev.marzahn.sankaku.ui.components.countdownRing
import dev.marzahn.sankaku.ui.components.effectsSpec
import dev.marzahn.sankaku.ui.rememberConfirmWindowMillis
import dev.marzahn.sankaku.ui.runWindow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * The account, at the foot of the sidebar: "Sign in" when signed out; otherwise [AccountRow], the avatar and name
 * with Sign out beside them. [open] is whether the sidebar is; [onClose] closes it before the sign-in dialog opens.
 */
@Composable
fun AccountSection(
  open: Boolean,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  session: AuthSession = koinInject(),
  signIn: SignInController = koinInject(),
) {
  val state by session.state.collectAsStateWithLifecycle()
  val user = (state as? AuthState.SignedIn)?.user
  if (user == null) {
    FilledTonalButton(
      onClick = {
        onClose()
        signIn.open()
      },
      modifier = modifier.fillMaxWidth(),
    ) { Text("Sign in") }
    return
  }

  val scope = rememberCoroutineScope()
  AccountRow(user.name, user.avatarUrl, open, onSignOut = { scope.launch { session.signOut() } }, modifier)
}

/**
 * The avatar and [name], with Sign out beside them (#134). A tap on Sign out doesn't sign out: the row turns into an
 * error-tonal "Sign out? Tap again" with a countdown ring for [windowMillis] (M3's Short snackbar time, through the
 * accessibility settings), and a second tap within it calls [onSignOut]. When the window runs out, or the sidebar
 * closes ([open] false), the row comes back. TalkBack hears the question from a polite live region, and Sign out's
 * custom action signs out at once.
 */
@Composable
internal fun AccountRow(
  name: String,
  avatarUrl: String?,
  open: Boolean,
  onSignOut: () -> Unit,
  modifier: Modifier = Modifier,
  windowMillis: Long = rememberConfirmWindowMillis(),
) {
  var asking by remember { mutableStateOf(false) }
  var left by remember { mutableFloatStateOf(1f) }
  val confirmInteractions = remember { MutableInteractionSource() }
  val pressed by confirmInteractions.collectIsPressedAsState()
  LaunchedEffect(open) { if (!open) asking = false }
  LaunchedEffect(asking) {
    if (!asking) return@LaunchedEffect
    // A press on the question holds the window, as feedback C's controls do.
    runWindow(windowMillis, isPaused = { pressed }) { left = it }
    asking = false
  }
  val fadeIn = fadeIn(effectsSpec())
  val fadeOut = fadeOut(effectsSpec())
  Box(modifier.fillMaxWidth()) {
    LiveAnnouncement(if (asking) "Sign out? Double-tap again to confirm" else "")
    AnimatedContent(
      asking,
      transitionSpec = { fadeIn togetherWith fadeOut },
      contentAlignment = Alignment.CenterStart,
    ) { confirming ->
      if (confirming) {
        ConfirmSignOut(
          onClick = {
            asking = false
            onSignOut()
          },
          left = { left },
          interactionSource = confirmInteractions,
        )
      } else {
        SignedIn(name, avatarUrl, onAsk = { asking = true }, onSignOutNow = onSignOut)
      }
    }
  }
}

/** Sign out's name and its tooltip on the desktop (#521). */
internal const val SIGN_OUT = "Sign out"

/** The avatar and [name], with Sign out: a tap calls [onAsk]; its TalkBack custom action, [onSignOutNow]. */
@Composable
private fun SignedIn(name: String, avatarUrl: String?, onAsk: () -> Unit, onSignOutNow: () -> Unit) {
  Row(
    Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(32.dp)) {
      Box(contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
        if (!avatarUrl.isNullOrEmpty()) {
          AsyncImage(
            model = avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(32.dp).clip(CircleShape),
          )
        }
      }
    }
    Text(
      name,
      style = MaterialTheme.typography.labelLarge,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    ButtonHint(SIGN_OUT) {
      IconButton(
        onClick = onAsk,
        modifier = Modifier.semantics {
          customActions = listOf(CustomAccessibilityAction("Sign out now") { onSignOutNow(); true })
        },
      ) {
        Icon(logout, contentDescription = SIGN_OUT)
      }
    }
  }
}

/**
 * "Sign out? Tap again" in the error container, the row's full width and Sign out's 48 dp height, with Sign out's
 * icon in the window's ring where Sign out was, so the second tap lands where the first did. It takes the focus Sign
 * out had, so Enter on a keyboard confirms too; a touch screen gives none, and nothing changes there.
 */
@Composable
private fun ConfirmSignOut(onClick: () -> Unit, left: () -> Float, interactionSource: MutableInteractionSource) {
  val focus = remember { FocusRequester() }
  Surface(
    onClick = onClick,
    shape = CircleShape,
    color = MaterialTheme.colorScheme.errorContainer,
    contentColor = MaterialTheme.colorScheme.onErrorContainer,
    interactionSource = interactionSource,
    modifier = Modifier.fillMaxWidth().height(48.dp).focusRequester(focus),
  ) {
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(
        "Sign out? Tap again",
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Box(Modifier.size(40.dp).countdownRing(left), contentAlignment = Alignment.Center) {
        Icon(logout, contentDescription = null, modifier = Modifier.size(20.dp))
      }
    }
  }
}
