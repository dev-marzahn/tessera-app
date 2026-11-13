package dev.marzahn.sankaku.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marzahn.sankaku.queue.SeenStore
import org.koin.compose.koinInject

/**
 * Where the item [id] stands in its block (#502, desktop), left of the search line: "3 / 8 · from Landscape" on a kept
 * item, "Landscape · 5 left" on a source with kept items, "2 / 2 · from Cat (closed)" in a closed source's block.
 * Nothing for an item in no block with kept items. A click opens the tab switcher's queue list ([onOpen]).
 */
@Composable
fun BlockChip(
  id: TabId,
  onOpen: () -> Unit,
  controller: TabsController = koinInject(),
  seen: SeenStore = koinInject(),
) {
  val tabs by controller.tabs.collectAsStateWithLifecycle()
  // Read so the chip follows what's seen; the kept times it compares with are snapshot state.
  seen.revision.collectAsStateWithLifecycle().value
  val progress = blockProgress(tabs, id) { it.seenSinceKept(seen) } ?: return
  val text = blockChipText(progress)
  val spoken = blockChipSpoken(progress)
  Surface(
    onClick = onOpen,
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier
      .widthIn(max = 280.dp)
      .testTag(BLOCK_CHIP_TAG)
      .clearAndSetSemantics {
        role = Role.Button
        contentDescription = spoken
        onClick { onOpen(); true }
      },
  ) {
    Text(
      text,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
  }
  Spacer(Modifier.width(8.dp))
}

internal const val BLOCK_CHIP_TAG = "block-chip"
