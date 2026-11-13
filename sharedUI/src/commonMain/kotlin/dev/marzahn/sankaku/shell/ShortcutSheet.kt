package dev.marzahn.sankaku.shell

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.fonts.icons.close
import com.google.fonts.icons.search
import dev.marzahn.sankaku.ui.components.ButtonHint
import dev.marzahn.sankaku.ui.components.KbdBadge
import dev.marzahn.sankaku.ui.components.KeyHint
import dev.marzahn.sankaku.ui.components.KeyScroll
import dev.marzahn.sankaku.ui.components.KeyScrollLine
import dev.marzahn.sankaku.ui.components.KEY_SCROLL_SCREEN
import dev.marzahn.sankaku.ui.components.ModalSideSheet
import dev.marzahn.sankaku.ui.components.ShortcutGroup
import dev.marzahn.sankaku.ui.components.ShortcutGroups
import dev.marzahn.sankaku.ui.components.ShortcutRow
import dev.marzahn.sankaku.ui.components.Shortcuts
import dev.marzahn.sankaku.ui.components.isCommand
import dev.marzahn.sankaku.ui.components.matching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.compose.koinInject

/** Whether the keyboard shortcuts sheet is open (#521): `?`, the strip's ⋮ and Settings' Keyboard row open it. */
class ShortcutSheetController {
  private val _isOpen = MutableStateFlow(false)
  val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

  fun open() {
    _isOpen.value = true
  }

  fun close() {
    _isOpen.value = false
  }

  fun toggle() {
    _isOpen.value = !_isOpen.value
  }
}

/** The sheet's title, its pane title and heading. */
internal const val SHORTCUTS_TITLE = "Keyboard shortcuts"

internal const val SHORTCUTS_FIND_TAG = "shortcuts-find"
internal const val SHORTCUTS_SCROLL_TAG = "shortcuts-scroll"

/**
 * Desktop (#521, the mockup the owner approved): every key, from [ShortcutGroups], in a side sheet like Settings', over
 * whatever is open (Settings too, which opens it). A heading with ✕, a hint row, a find field, then the groups.
 * W/S (↑/↓) scroll it, with Shift a screen; / goes to the find field and ↓ or Esc there back to the list; Esc, ? (from
 * the list), ✕ and the scrim close it.
 */
@Composable
fun ShortcutSheet(controller: ShortcutSheetController = koinInject()) {
  val open by controller.isOpen.collectAsStateWithLifecycle()
  var query by remember { mutableStateOf("") }
  LaunchedEffect(open) { if (open) query = "" }
  val scroll = rememberScrollState()
  val keyScroll = remember(scroll) { KeyScroll(scroll) }
  val scope = rememberCoroutineScope()
  val line = with(LocalDensity.current) { KeyScrollLine.toPx() }
  val list = remember { FocusRequester() }
  val find = remember { FocusRequester() }
  var finding by remember { mutableStateOf(false) }
  ModalSideSheet(open, onClose = controller::close, title = SHORTCUTS_TITLE) {
    // The list's keys, which the find field never sees: typing W there types it.
    Box(
      Modifier.fillMaxSize().onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || finding || event.isCommand || event.isAltPressed) return@onKeyEvent false
        val step = if (event.isShiftPressed) scroll.viewportSize * KEY_SCROLL_SCREEN else line
        when {
          Shortcuts.Down.matches(event.key) -> true.also { keyScroll.by(step, scope) }
          Shortcuts.Up.matches(event.key) -> true.also { keyScroll.by(-step, scope) }
          event.key == Shortcuts.Help.key && event.isShiftPressed -> true.also { controller.close() }
          event.key == Shortcuts.Search.key -> true.also { find.requestFocus() }
          else -> false
        }
      },
    ) {
      // Has the keys as the sheet opens, so W/S scroll at once.
      Box(Modifier.size(0.dp).focusRequester(list).focusable())
      LaunchedEffect(open) { if (open) list.requestFocus() }
      Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(
            SHORTCUTS_TITLE,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f).semantics { heading() },
          )
          ButtonHint("Close (${Shortcuts.Back.label})") {
            IconButton(onClick = controller::close) { Icon(close, contentDescription = "Close keyboard shortcuts") }
          }
        }
        FlowRow(
          Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          KeyHint("W S", "scroll")
          KeyHint(Shortcuts.Search.label, "find")
          KeyHint("${Shortcuts.Back.label} ${Shortcuts.Help.label}", "close")
        }
        TextField(
          value = query,
          onValueChange = { query = it },
          singleLine = true,
          placeholder = { Text("Find a shortcut") },
          leadingIcon = { Icon(search, contentDescription = null) },
          colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
          ),
          shape = RoundedCornerShape(28.dp),
          modifier = Modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth()
            .focusRequester(find)
            .onFocusChanged { finding = it.isFocused }
            // ↓ or Esc hand the keys back to the list; Esc again closes the sheet.
            .onPreviewKeyEvent { event ->
              val back = event.key == Shortcuts.Back.key || event.key == Shortcuts.Down.key
              if (back && event.type == KeyEventType.KeyDown) list.requestFocus()
              back
            }
            .testTag(SHORTCUTS_FIND_TAG),
        )
        val groups = ShortcutGroups.matching(query)
        Column(
          Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(scroll)
            .testTag(SHORTCUTS_SCROLL_TAG)
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
        ) {
          if (groups.isEmpty()) {
            Text(
              "No shortcut matches “${query.trim()}”",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 16.dp),
            )
          }
          groups.forEach { ShortcutGroupSection(it) }
        }
      }
    }
  }
}

@Composable
private fun ShortcutGroupSection(group: ShortcutGroup) {
  Text(
    group.title,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
  )
  group.rows.forEach { ShortcutLine(it) }
}

/** What a row does on the left, its keys on the right; one node, read "Close tab, X or ⌘W". */
@Composable
private fun ShortcutLine(row: ShortcutRow) {
  Row(
    Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {},
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(row.action, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
      row.keys.forEachIndexed { index, way ->
        if (index > 0) {
          Text(row.joiner, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        way.forEach { KbdBadge(it) }
      }
    }
  }
}
