package dev.marzahn.sankaku.features.settings

import dev.marzahn.sankaku.Navigator
import dev.marzahn.sankaku.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Touch: whether the Settings page ([SettingsPage]) is open over the app (#205). Desktop shows Settings as a tab. */
class SettingsController {
  private val _isOpen = MutableStateFlow(false)
  val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

  fun open() {
    _isOpen.value = true
  }

  fun close() {
    _isOpen.value = false
  }
}

/**
 * Opens Settings from the sidebar (#205). On a phone ([touch]) it's the page over the app, for a tap and a long-press
 * alike: the queue holds what's being looked through, and Settings isn't a queue item. On desktop it's a tab: a click
 * opens or shows it ([Navigator.goTo]), a middle or Cmd/Ctrl-click opens it in the background ([inNewTab]).
 */
internal fun openSettingsFromSidebar(touch: Boolean, inNewTab: Boolean, navigator: Navigator, controller: SettingsController) {
  when {
    touch -> controller.open()
    inNewTab -> navigator.openInNewTab(Route.Settings)
    else -> navigator.goTo(Route.Settings)
  }
}
