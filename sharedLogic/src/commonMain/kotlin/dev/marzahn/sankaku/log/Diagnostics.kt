package dev.marzahn.sankaku.log

import dev.marzahn.sankaku.PUBLIC_REPO_URL
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/**
 * Where a bug report comes from: the app's [version], the [platform] as the bug form's dropdown names it (`Android`,
 * `macOS`), the [os] with its version and the [device] model. Nothing about the user, their account or their settings.
 */
data class AppEnvironment(val version: String, val platform: String, val os: String, val device: String)

/**
 * Settings → Diagnostics' two actions (#207, decision 18 in #196), and the crash report's (#208) too. Nothing here
 * sends anything: Copy log puts [copyText] on the clipboard, Report a bug opens [bugReportUrl] in the browser, and the
 * user pastes the log into the form themselves, so they see what they share.
 */
class Diagnostics(private val log: RequestLog, val environment: AppEnvironment) {
  /** The log Diagnostics shows and copies, oldest first. */
  val entries: StateFlow<List<RequestLogEntry>> get() = log.entries

  /**
   * Copy log's text: version, platform, OS and device, then the entries newest first, one a line: UTC time to the
   * second, method, host, path template, [RequestLogEntry.outcome] and duration. [RequestLog] keeps only these fields,
   * so nothing else can get in.
   */
  fun copyText(): String {
    val entries = log.entries.value
    if (entries.isEmpty()) return "$header\n\nRequests: none yet"
    val lines = entries.asReversed().map { entry ->
      listOf(utcSeconds(entry.time), entry.method, entry.host, entry.pathTemplate, entry.outcome, "${entry.durationMs} ms")
        .joinToString("  ")
    }
    return "$header\n\nRequests, newest first (${entries.size}):\n" + lines.joinToString("\n")
  }

  /**
   * The crash notice's Report text (#208): the same environment as [copyText], then the [crash] report
   * [CrashReports] saved. Not this run's request log: it began after the crash, so it says nothing about it.
   */
  fun crashText(crash: String): String = "$header\n\n$crash"

  private val header: String get() = listOf(
    "Tessera ${environment.version}",
    "Platform: ${environment.platform}",
    "OS: ${environment.os}",
    "Device: ${environment.device}",
  ).joinToString("\n")

  /**
   * The public repo's bug form with the environment filled in, by the field ids of
   * `mirror/public/.github/ISSUE_TEMPLATE/bug.yml` (that file says to keep them stable). Never the log: it'd end up
   * in the browser's history.
   */
  fun bugReportUrl(): String = URLBuilder(Url("$PUBLIC_REPO_URL/issues/new")).apply {
    parameters.append("template", BUG_FORM)
    parameters.append("version", environment.version)
    // GitHub drops a dropdown prefill that isn't an option; a dev run on another desktop leaves it for the user.
    if (environment.platform in FORM_PLATFORMS) parameters.append("platform", environment.platform)
    parameters.append("os", environment.os)
    parameters.append("device", environment.device)
  }.buildString()

  private companion object {
    /** The bug form's file name under `.github/ISSUE_TEMPLATE/`. */
    const val BUG_FORM = "bug.yml"

    /** The bug form's `platform` dropdown options. */
    val FORM_PLATFORMS = setOf("Android", "macOS")

    fun utcSeconds(time: Instant): String = Instant.fromEpochSeconds(time.epochSeconds).toString()
  }
}
