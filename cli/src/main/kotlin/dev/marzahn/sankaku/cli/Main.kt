package dev.marzahn.sankaku.cli

import dev.marzahn.sankaku.api.BrowserFingerprint
import dev.marzahn.sankaku.api.BrowserPlatform
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * `--rerecord` overwrites `docs/api-recorded.json`; `--slow` adds discovery step 7 (waits about an hour); `--android`
 * sends the Android app's User-Agent and client hints instead of the desktop's (#204).
 */
fun main(args: Array<String>) {
  val report = Report(File("cli/out"))
  val recordings = Recordings(File("docs/api-recorded.json"), rerecord = "--rerecord" in args)
  val env = loadEnv()
  val ledger = FeedLedger.default(env)
  val fingerprint = if ("--android" in args) {
    BrowserFingerprint(BrowserPlatform.Android)
  } else {
    BrowserFingerprint.Current
  }
  val smoke = SmokeSteps(env, recordings, report, ledger, slow = "--slow" in args, fingerprint = fingerprint)
  try {
    runBlocking { smoke.run() }
  } finally {
    recordings.save()
    report.print()
    smoke.close()
    // Last: a ledger that can't be written must not hide the table.
    runCatching { ledger.save() }.onFailure { println("Could not save the feed ledger (${it::class.simpleName}).") }
  }
  exitProcess(if (report.failed) 1 else 0)
}
