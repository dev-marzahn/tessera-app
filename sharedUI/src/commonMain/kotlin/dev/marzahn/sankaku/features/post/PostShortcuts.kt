package dev.marzahn.sankaku.features.post

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.ui.components.Shortcuts
import dev.marzahn.sankaku.ui.components.isCommand

/**
 * The keys of every screen that shows a post (the viewer, the post tab): Favourite toggles [post], 1–5 vote (the same
 * vote again takes it back), Back calls [onBack]. Screens add their own keys around it. While [enabled] is false, keys pass through to what's underneath.
 * Needs focus inside: a `KeyFocusHolder`.
 */
fun Modifier.postShortcuts(
  post: () -> PostDto?,
  actions: PostActions,
  onBack: () -> Unit,
  enabled: () -> Boolean = { true },
): Modifier = onKeyEvent { event ->
  if (!enabled() || event.type != KeyEventType.KeyDown) return@onKeyEvent false
  // With Cmd or Ctrl a key is an app shortcut: Cmd/Ctrl-F opens the search rather than favouriting.
  if (event.isCommand) return@onKeyEvent false
  when (event.key) {
    Shortcuts.Favourite.key -> true.also { post()?.let(actions::toggleFavourite) }
    Shortcuts.Back.key -> true.also { onBack() }
    else -> Shortcuts.voteFor(event.key)?.let { score ->
      post()?.let { actions.vote(it, if (it.userVote == score) null else score) }
      true
    } ?: false
  }
}
