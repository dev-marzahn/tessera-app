package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.data.TextFile
import dev.marzahn.sankaku.ui.Tip
import dev.marzahn.sankaku.ui.Tips
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SavedReopenHint(val holds: Int = 0)

/**
 * Folds the old reopen hint into [Tip.ReopenHold] (#344). Until then the top line taught the hold after every close,
 * counting in [file] (`reopen-hint.json`) the holds that reopened an item. One such hold means the hold is known, so
 * the tip never shows on that install ([Tips.used]). The file is then deleted: nothing reads it again.
 */
fun migrateReopenHint(file: TextFile, tips: Tips) {
  val text = file.read() ?: return
  val holds = runCatching { json.decodeFromString(SavedReopenHint.serializer(), text).holds }.getOrDefault(0)
  if (holds > 0) tips.used(Tip.ReopenHold)
  file.delete()
}

private val json = Json { ignoreUnknownKeys = true }
