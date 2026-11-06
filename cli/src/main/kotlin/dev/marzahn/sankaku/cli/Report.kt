package dev.marzahn.sankaku.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

data class StepResult(
  val pass: Boolean,
  val host: String,
  val status: String,
  val note: String,
  val skipped: Boolean = false,
)

/** Every value printed or written is a status, a header name or a JSON key; never a token, password or user field. */
class Report(private val outDir: File) {
  private val rows = mutableListOf<Pair<String, StepResult>>()

  val failed: Boolean get() = rows.any { !it.second.pass }

  fun add(step: String, result: StepResult) {
    rows += step to result
    val mark = when {
      result.skipped -> "skip"
      result.pass -> "ok"
      else -> "FAIL"
    }
    println("[$mark] $step")
  }

  /** Writes the keys and value types of [json], so the file is safe to share. */
  fun writeShape(step: String, json: JsonElement?) {
    json ?: return
    val dir = File(outDir, "shapes").apply { mkdirs() }
    val slug = step.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    File(dir, "$slug.txt").writeText(shape(json) + "\n")
  }

  fun print() {
    val header = listOf("Step", "Host", "Status", "Note")
    val lines = buildList {
      add(header.joinToString(" | ", "| ", " |"))
      add(header.joinToString(" | ", "| ", " |") { "---" })
      rows.forEach { (step, r) ->
        val status = if (r.skipped) "skipped" else r.status
        val note = if (r.pass || r.skipped) r.note else "FAIL: ${r.note}"
        add(listOf(step, r.host, status, note).joinToString(" | ", "| ", " |") { it.replace("|", "\\|") })
      }
    }
    val table = lines.joinToString("\n")
    println()
    println(table)
    outDir.mkdirs()
    File(outDir, "results.md").writeText(table + "\n")
    println("\nWritten to ${File(outDir, "results.md").path}; JSON key shapes in ${File(outDir, "shapes").path}")
  }
}

fun shape(json: JsonElement, indent: String = ""): String = when (json) {
  is JsonObject -> json.entries.joinToString("\n") { (key, value) ->
    when (value) {
      is JsonObject, is JsonArray -> "$indent$key: ${typeOf(value)}\n${shape(value, "$indent  ")}".trimEnd()
      else -> "$indent$key: ${typeOf(value)}"
    }
  }
  is JsonArray -> json.firstOrNull()?.let { shape(it, indent) } ?: "$indent(empty)"
  else -> "$indent${typeOf(json)}"
}

private fun typeOf(json: JsonElement): String = when (json) {
  is JsonNull -> "null"
  is JsonObject -> "object"
  is JsonArray -> "array[${json.size}]"
  is JsonPrimitive -> when {
    json.isString -> "string"
    json.content == "true" || json.content == "false" -> "boolean"
    else -> "number"
  }
}
