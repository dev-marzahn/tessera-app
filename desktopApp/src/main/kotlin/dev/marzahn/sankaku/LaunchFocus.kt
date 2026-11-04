package dev.marzahn.sankaku

import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/** The bundle id `compose.desktop` gives the packaged app (see `desktopApp/build.gradle.kts`). */
private const val BUNDLE_ID = "dev.marzahn.tessera"

/**
 * Gives focus back to the app the user was in when this app was started from a terminal or Gradle. macOS activates
 * a process as soon as AWT starts, and the JDK has no setting against it (only `apple.awt.UIElement`, which also
 * hides the Dock icon). Opened from the Dock or Finder, macOS sets `__CFBundleIdentifier` to the app's own bundle id;
 * then the user asked for it, and it keeps focus.
 *
 * [remember] runs before AWT starts and notes the frontmost app; [yieldFocus] runs once the window is up and
 * activates that app again. `[NSApp deactivate]` alone doesn't: with cooperative activation (macOS 14) nothing takes
 * over, and this app stays frontmost.
 */
internal object LaunchFocus {
  private var previous: Pointer? = null

  private val enabled =
    System.getProperty("os.name").startsWith("Mac") && System.getenv("__CFBundleIdentifier") != BUNDLE_ID

  private val objc by lazy { NativeLibrary.getInstance("objc") }

  private fun send(receiver: Pointer?, selector: String, vararg args: Any?): Pointer? {
    val sel = objc.getFunction("sel_registerName").invokePointer(arrayOf(selector))
    return objc.getFunction("objc_msgSend").invokePointer(arrayOf(receiver, sel, *args))
  }

  private fun cls(name: String): Pointer? = objc.getFunction("objc_getClass").invokePointer(arrayOf(name))

  fun remember() {
    if (!enabled) return
    previous = runCatching {
      val workspace = send(cls("NSWorkspace"), "sharedWorkspace")
      // Retained, so it outlives the autorelease pool it came from.
      send(send(workspace, "frontmostApplication"), "retain")
    }.getOrNull()
  }

  fun yieldFocus() {
    val app = previous ?: return
    previous = null
    runCatching {
      // 0 = no options: bring that app's key window back, as switching to it would.
      send(app, "activateWithOptions:", 0L)
      send(app, "release")
    }
  }
}
