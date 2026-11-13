package dev.marzahn.sankaku.features.post

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.fonts.icons.visibility_off
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.BlacklistApi
import dev.marzahn.sankaku.api.couldNot
import dev.marzahn.sankaku.api.dto.TagDto
import dev.marzahn.sankaku.ui.Message
import dev.marzahn.sankaku.ui.Messages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Blacklisting a tag from its menu (#6): a `hide` rule on the account, which the server applies to every search at
 * once, here and on the site. Runs on the app scope, so it finishes if the menu's screen closes. Success says so with
 * Undo, which removes the rule again; a failure says why.
 */
class TagBlacklisting(private val api: BlacklistApi, private val messages: Messages, private val scope: CoroutineScope) {
  /** How many blacklist rules the account uses, or null if that couldn't be loaded. */
  suspend fun usedRules(): Int? = try {
    api.usedRules()
  } catch (_: ApiError) {
    null
  }

  /** Adds a `hide` rule on [tag]; the snackbar's Undo removes it. */
  fun blacklist(tag: TagDto) {
    scope.launch {
      try {
        val rule = api.hideTag(tag.searchName)
        messages.show(Message("Blacklisted ${tag.displayName}", actionLabel = "Undo") { undo(tag, rule.ruleId) })
      } catch (e: ApiError) {
        messages.showError(e.couldNot("blacklist ${tag.displayName}"))
      }
    }
  }

  private fun undo(tag: TagDto, ruleId: String) {
    scope.launch {
      try {
        api.remove(ruleId)
        messages.show(Message("${tag.displayName} is off the blacklist", short = true))
      } catch (e: ApiError) {
        messages.showError(e.couldNot("take ${tag.displayName} off the blacklist"))
      }
    }
  }
}

/** The confirmation's line about the rules in use: [used] null while it loads ([loaded] false) or if it failed. */
internal fun blacklistRulesLine(used: Int?, loaded: Boolean): String = when {
  !loaded -> "Counting your blacklist rules…"
  used == null -> "How many blacklist rules you use couldn't be loaded."
  used == 1 -> "You use 1 blacklist rule; this adds one."
  else -> "You use $used blacklist rules; this adds one."
}

/**
 * Asks before blacklisting [tag]: it hides the tag's posts account-wide, and how many rules the account uses
 * ([usedRules], asked when it opens). Cancel has the focus, so a second Space from the menu doesn't blacklist; Tab
 * moves to Blacklist and Space presses it, all left-hand keys; Esc cancels.
 */
@Composable
internal fun BlacklistConfirmation(tag: TagDto, usedRules: suspend () -> Int?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
  val used by produceState<Pair<Boolean, Int?>>(false to null, tag.searchName) { value = true to usedRules() }
  val cancel = remember { FocusRequester() }
  LaunchedEffect(Unit) { cancel.requestFocus() }
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = { Icon(visibility_off, contentDescription = null) },
    title = { Text("Blacklist ${tag.displayName}?") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Posts tagged ${tag.searchName} will be hidden everywhere on your account: in this app and on the Sankaku site.")
        Text(
          blacklistRulesLine(used.second, used.first),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("blacklist-rules"),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onDismiss(); onConfirm() }) { Text("Blacklist", color = MaterialTheme.colorScheme.error) }
    },
    dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancel)) { Text("Cancel") } },
  )
}
