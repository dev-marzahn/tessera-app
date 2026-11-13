package dev.marzahn.sankaku.cli

import dev.marzahn.sankaku.api.BrowserFingerprint
import dev.marzahn.sankaku.api.BrowserPlatform
import dev.marzahn.sankaku.api.RateLimiter
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * `--rerecord` overwrites `docs/api-recorded.json`; `--slow` adds discovery step 7 (waits about an hour); `--android`
 * sends the Android app's User-Agent and client hints instead of the desktop's (#204). `--rate-probe` runs only
 * [RateProbe] (`--levels=2,4,6`, `--seconds=15`, `--burst=25`, `--limiter=6,3`); it's meant to meet a 429, so run it
 * by hand, not as part of the smoke run.
 */
fun main(args: Array<String>) {
  if ("--rate-probe" in args) {
    fun arg(name: String) = args.firstOrNull { it.startsWith("--$name=") }?.substringAfter("=")
    val probe = RateProbe(
      loadEnv(),
      levels = arg("levels")?.split(",")?.map { it.toDouble() } ?: listOf(2.0, 4.0, 6.0, 8.0, 12.0, 16.0, 24.0, 32.0),
      burst = arg("burst")?.toInt() ?: 0,
      levelSeconds = arg("seconds")?.toInt() ?: 15,
      bucket = arg("limiter")?.split(",")?.let { (rate, burst) -> RateLimiter.Limit(rate.toDouble(), burst.toInt()) },
    )
    try {
      runBlocking { probe.run() }
    } finally {
      probe.close()
    }
    return
  }
  val report = Report(File("cli/out"))
  val recordings = Recordings(File("docs/api-recorded.json"), rerecord = "--rerecord" in args)
  val env = loadEnv()
  val ledger = FeedLedger.default(env)
  val fingerprint = if ("--android" in args) {
    BrowserFingerprint(BrowserPlatform.AndroidDesktopSite)
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
