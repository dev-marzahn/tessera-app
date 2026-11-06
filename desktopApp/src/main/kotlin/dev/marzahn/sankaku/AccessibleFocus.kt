package dev.marzahn.sankaku

import java.awt.Component
import java.awt.DefaultKeyboardFocusManager
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.lang.ref.WeakReference

/**
 * Keeps the window's focused accessibility element valid while the app is in the background, so accessibility
 * clients (VoiceOver, background automation) can still act on the window.
 *
 * AppKit answers `AXFocusedUIElement` through `sun.lwawt.macosx.CAccessibility.getFocusOwner`, which asks AWT for its
 * focus owner. AWT clears that while the app is inactive, and the element AppKit then returns is already invalid
 * (`kAXErrorInvalidUIElement`). Native Cocoa windows keep reporting their first responder instead.
 *
 * This hands that one caller the last focused window's most recent focus owner. Every other caller still gets null,
 * so focus handling and what the user sees don't change. Call it before the first window is created:
 * `CAccessibility` subscribes to the focus manager that is current when the first accessibility request arrives.
 */
internal fun keepAccessibleFocusInBackground() {
  if (System.getProperty("os.name").startsWith("Mac")) {
    KeyboardFocusManager.setCurrentKeyboardFocusManager(BackgroundAccessibleFocusManager())
  }
}

private class BackgroundAccessibleFocusManager : DefaultKeyboardFocusManager() {
  // The window, not the focus owner: Compose fires fake `focusOwner` events for VoiceOver whose value is a
  // `ComposeAccessible`, which is a Component but not part of the window.
  private var lastWindow = WeakReference<Window>(null)
  private val stack = StackWalker.getInstance()

  init {
    addPropertyChangeListener("focusedWindow") { event ->
      (event.newValue as? Window)?.let { lastWindow = WeakReference(it) }
    }
  }

  override fun getFocusOwner(): Component? =
    super.getFocusOwner() ?: lastWindow.get()?.takeIf { it.isShowing && calledByMacAccessibility() }?.mostRecentFocusOwner

  private fun calledByMacAccessibility(): Boolean =
    stack.walk { frames -> frames.anyMatch { it.className.startsWith("sun.lwawt.macosx.CAccessibility") } }
}
