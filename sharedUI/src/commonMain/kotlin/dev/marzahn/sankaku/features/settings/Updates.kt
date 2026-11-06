package dev.marzahn.sankaku.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marzahn.sankaku.platformOpenUrl
import dev.marzahn.sankaku.ui.components.UPDATE_AVAILABLE_DOT
import dev.marzahn.sankaku.update.AvailableUpdate
import dev.marzahn.sankaku.update.UpdateCheck
import org.koin.compose.koinInject

/** The update-check switch's label (#209). */
internal const val CHECK_FOR_UPDATES = "Check for updates"

/** What the switch says it does: where it asks, how often, and that it only tells. */
internal const val CheckForUpdatesHelp = "Once a day, asks GitHub whether a newer version exists. Nothing is downloaded."

/** The row's label for a newer [update]: "Update available · 100.1.0". */
internal fun updateAvailableLabel(update: AvailableUpdate): String = "$UPDATE_AVAILABLE_DOT · ${update.version}"

/**
 * Settings → Updates (#209): the update-check switch, on by default, and while a newer release exists, "Update
 * available · <version>", which opens its release page in the browser through [open]. Nothing is downloaded.
 */
@Composable
internal fun UpdatesRows(updates: UpdateCheck = koinInject(), open: (String) -> Unit = ::platformOpenUrl) {
  val enabled by updates.enabled.collectAsStateWithLifecycle()
  val available by updates.available.collectAsStateWithLifecycle()
  SwitchRow(CHECK_FOR_UPDATES, CheckForUpdatesHelp, enabled, updates::setEnabled)
  available?.let { update ->
    ActionRow(updateAvailableLabel(update), help = "Opens the release page", onClick = { open(update.url) })
  }
}
