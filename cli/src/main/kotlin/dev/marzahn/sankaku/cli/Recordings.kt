package dev.marzahn.sankaku.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** What a discovery step observed: status, top-level JSON keys and an optional step-specific marker. */
@Serializable
data class Recorded(val status: Int, val keys: List<String>, val marker: String? = null)

/**
 * `docs/api-recorded.json`. A missing entry is recorded and passes; later runs pass only if the observation equals
 * the stored one, so a failure means the API changed. `--rerecord` overwrites every entry it observes.
 */
class Recordings(private val file: File, private val rerecord: Boolean) {
  private val json = Json { prettyPrint = true }
  private val entries: MutableMap<String, Recorded> =
    if (file.exists()) json.decodeFromString<Map<String, Recorded>>(file.readText()).toMutableMap() else mutableMapOf()
  private var dirty = false

  /** Returns whether the step passes, and its note. */
  fun check(id: String, observed: Recorded): Pair<Boolean, String> {
    val stored = entries[id]
    return when {
      stored == null || rerecord -> {
        entries[id] = observed
        dirty = true
        true to "recorded${observed.marker?.let { ": $it" } ?: ""}"
      }
      stored == observed -> true to "matches${observed.marker?.let { ": $it" } ?: ""}"
      else -> false to "changed: ${diff(stored, observed)}"
    }
  }

  fun save() {
    if (!dirty) return
    file.parentFile?.mkdirs()
    file.writeText(json.encodeToString<Map<String, Recorded>>(entries.toSortedMap().toMap()) + "\n")
  }

  private fun diff(old: Recorded, new: Recorded): String = buildList {
    if (old.status != new.status) add("status ${old.status} → ${new.status}")
    val added = new.keys - old.keys.toSet()
    val removed = old.keys - new.keys.toSet()
    if (added.isNotEmpty()) add("keys +${added.joinToString(",")}")
    if (removed.isNotEmpty()) add("keys -${removed.joinToString(",")}")
    if (old.marker != new.marker) add("marker ${old.marker} → ${new.marker}")
  }.joinToString("; ")
}
