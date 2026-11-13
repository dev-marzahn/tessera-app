package dev.marzahn.sankaku.features.settings

import androidx.compose.foundation.clickable
import dev.marzahn.sankaku.ui.components.ButtonHint
import dev.marzahn.sankaku.ui.components.Shortcuts
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.fonts.icons.arrow_back
import com.google.fonts.icons.close
import dev.marzahn.sankaku.api.dto.TagDto
import dev.marzahn.sankaku.data.FollowedTagList
import dev.marzahn.sankaku.data.TagFollows
import dev.marzahn.sankaku.features.auth.SignInController
import dev.marzahn.sankaku.features.post.PostActions
import dev.marzahn.sankaku.features.post.compactCount
import dev.marzahn.sankaku.features.tag.TagPages
import dev.marzahn.sankaku.platformBackKeys
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.ui.components.AppLoadingIndicator
import dev.marzahn.sankaku.ui.components.AppTopBar
import dev.marzahn.sankaku.ui.components.ApiErrorState
import dev.marzahn.sankaku.ui.components.LargeIndicatorSize
import dev.marzahn.sankaku.ui.components.TagChip
import dev.marzahn.sankaku.ui.components.TagChipState
import org.koin.compose.koinInject

/** The page's title, and Account's row that opens it. */
internal const val FollowedTagsTitle = "Followed tags"

/** Account's row's second line. */
internal const val FollowedTagsHelp = "The tags whose posts Following shows"

/** What the page says when the account follows no tag. */
internal const val NoFollowedTags = "You don't follow any tags. Follow one from a post's tags or a tag's page."

/** What the followed tags page holds while it reads the list. */
private sealed interface FollowedState {
  data object Loading : FollowedState
  data class Failed(val error: Throwable) : FollowedState
  data class Loaded(val tags: List<TagDto>) : FollowedState
}

/** The test tag of a followed tag's row. */
internal fun followedTagRowTag(tag: TagDto) = "followed-tag-${tag.searchName}"

/**
 * Settings → Account → Followed tags (#314): every tag the account follows (`GET /users/followings?content_type=tags`),
 * over Settings as the licences page is. A tag's chip opens its page ([TagPages], closing Settings and this page with
 * [onOpenTag]). Unfollow acts at once, through [PostActions.toggleFollow] as a tag's bell does; the row stays, dimmed,
 * with Follow to take it back until the page closes.
 */
@Composable
internal fun FollowedTagsPage(
  onDismiss: () -> Unit,
  onOpenTag: () -> Unit,
  touch: Boolean = platformIsTouch,
  list: FollowedTagList = koinInject(),
  follows: TagFollows = koinInject(),
  toggle: (TagDto) -> Unit = koinInject<PostActions>().let { actions -> actions::toggleFollow },
  tagPages: TagPages = koinInject(),
) {
  var attempt by remember { mutableIntStateOf(0) }
  val state by produceState<FollowedState>(FollowedState.Loading, attempt) {
    value = FollowedState.Loading
    value = runCatching { list.all() }.fold(
      onSuccess = { FollowedState.Loaded(it) },
      onFailure = { FollowedState.Failed(it) },
    )
  }
  val overrides by follows.overrides.collectAsStateWithLifecycle()
  val changed by follows.changed.collectAsStateWithLifecycle()
  // The ids of the rows this page has shown, added once a composition is applied (never while composing).
  val shown = remember { mutableStateSetOf<String>() }
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    val size = if (touch) Modifier.fillMaxSize() else Modifier.widthIn(max = 720.dp).fillMaxWidth(0.9f).fillMaxHeight(0.9f)
    // Focus moves in, so Esc and Back close only this page, not the Settings sheet behind it.
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    Surface(
      size.semantics { paneTitle = FollowedTagsTitle }.focusRequester(focus).focusable().platformBackKeys(onDismiss),
      shape = if (touch) RectangleShape else MaterialTheme.shapes.extraLarge,
    ) {
      Scaffold(
        topBar = {
          AppTopBar(
            title = FollowedTagsTitle,
            navigationIcon = {
              ButtonHint("Close (${Shortcuts.Back.label})") {
                IconButton(onClick = onDismiss) {
                  if (touch) Icon(arrow_back, contentDescription = "Back") else Icon(close, contentDescription = "Close")
                }
              }
            },
          )
        },
      ) { padding ->
        when (val current = state) {
          FollowedState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            AppLoadingIndicator(size = LargeIndicatorSize)
          }
          is FollowedState.Failed -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            ApiErrorState(current.error, onRetry = { attempt++ }, onSignIn = koinInject<SignInController>()::open)
          }
          // This run's follows show at once, even before Sankaku's list has them (#539).
          // A row once shown stays until the page closes, so one followed here and unfollowed again can be followed back.
          is FollowedState.Loaded -> {
            val tags = follows.withChanges(current.tags, changed, overrides, keep = shown)
            SideEffect { tags.mapNotNullTo(shown) { it.id } }
            if (tags.isEmpty()) {
              Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(NoFollowedTags, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            } else {
              LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
              ) {
                items(tags, key = { it.id ?: it.searchName }) { tag ->
                  FollowedTagRow(
                    tag,
                    following = follows.isFollowing(tag, overrides),
                    onToggle = { toggle(tag) },
                    onOpen = {
                      onOpenTag()
                      tagPages.open(tag)
                    },
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

/** A followed tag: its chip (which opens its page), its type, and Unfollow, or Follow once unfollowed here. */
@Composable
private fun FollowedTagRow(tag: TagDto, following: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 4.dp).testTag(followedTagRowTag(tag)),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(Modifier.weight(1f).alpha(if (following) 1f else UNFOLLOWED_ALPHA), verticalAlignment = Alignment.CenterVertically) {
      val interaction = remember { MutableInteractionSource() }
      Column(Modifier.weight(1f)) {
        TagChip(
          name = tag.displayName,
          type = tag.category,
          count = tag.postCount?.let(::compactCount),
          state = TagChipState.Plain,
          selected = false,
          interactionSource = interaction,
          modifier = Modifier.clickable(interaction, indication = null, role = Role.Button, onClickLabel = "Open the tag's page", onClick = onOpen),
        )
        Text(
          tag.category.name,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 4.dp),
        )
      }
    }
    if (following) {
      FilledTonalButton(onClick = onToggle) { Text("Unfollow") }
    } else {
      TextButton(onClick = onToggle) { Text("Follow") }
    }
  }
}

private const val UNFOLLOWED_ALPHA = 0.6f
