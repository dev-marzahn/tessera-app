package dev.marzahn.sankaku.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Settings is open over the app: the phone's page ([SettingsPage], #205) or the desktop's side sheet over the
 * current tab ([SettingsSheet], #460). Never a tab or a queue item: the queue holds what's being looked through.
 */
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
