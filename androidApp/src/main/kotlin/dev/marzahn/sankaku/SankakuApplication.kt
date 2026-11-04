package dev.marzahn.sankaku

import android.app.Application
import android.content.Context
import android.content.Intent
import dev.marzahn.sankaku.di.startSankakuKoin
import dev.marzahn.sankaku.log.CrashReports
import dev.marzahn.sankaku.log.installUncaughtHandler
import org.koin.android.ext.koin.androidContext
import org.koin.mp.KoinPlatform

/**
 * Koin lives as long as the process, so rotating the screen doesn't rebuild `AuthSession`. The first activity starts
 * it, because a debug build's flags come from that activity's launch intent and `AuthSession` restores during start.
 * A component that runs without an activity (a service, receiver or worker) has to start it too before using Koin.
 */
class SankakuApplication : Application() {
  /**
   * Crash capture (#208), set up as soon as the app has a context: before content providers and `onCreate`. The
   * handler runs on the crashing thread, the main thread included, and writes the report synchronously before
   * Android's own handler kills the process.
   */
  private lateinit var crashReports: CrashReports

  override fun attachBaseContext(base: Context) {
    super.attachBaseContext(base)
    crashReports = androidCrashReports(this).also { it.installUncaughtHandler() }
  }

  fun startKoinOnce(launch: Intent) {
    if (KoinPlatform.getKoinOrNull() != null) return
    startSankakuKoin(androidPlatformModule(launchDebugFlags(this, launch), crashReports)) {
      androidContext(this@SankakuApplication)
    }
  }
}
