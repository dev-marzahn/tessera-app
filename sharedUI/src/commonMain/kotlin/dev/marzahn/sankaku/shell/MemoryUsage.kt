package dev.marzahn.sankaku.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import dev.marzahn.sankaku.processMemoryBytes
import dev.marzahn.sankaku.tabs.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The whole app's memory, as Activity Monitor counts it, read every [MEMORY_POLL_MS] while shown; in the error colour
 * past the 1 GB target (Non-Functional Requirements). Nothing where the platform can't read it.
 */
@Composable
fun MemoryUsage(modifier: Modifier = Modifier) {
  val bytes by produceState<Long?>(null) {
    while (true) {
      value = withContext(Dispatchers.Default) { processMemoryBytes() }
      delay(MEMORY_POLL_MS)
    }
  }
  val shown = bytes ?: return
  Text(
    "Memory usage ${formatBytes(shown)}",
    style = MaterialTheme.typography.labelSmall,
    color = if (shown > MEMORY_TARGET_BYTES) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier,
  )
}

private const val MEMORY_POLL_MS = 2_000L
private const val MEMORY_TARGET_BYTES = 1024L * 1024 * 1024
