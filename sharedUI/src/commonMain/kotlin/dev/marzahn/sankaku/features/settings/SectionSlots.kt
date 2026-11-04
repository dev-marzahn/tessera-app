package dev.marzahn.sankaku.features.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable

/*
 * The sections M6 fills in later (#205): each is a slot in Settings' order already, drawn under its heading once its
 * ticket sets its `…_FILLED` to true and writes its rows here. Until then it isn't drawn, so no empty heading shows.
 */

/** Whether Updates has rows yet: the update-check switch and "Update available" (#209). */
internal const val UPDATES_FILLED = true

/** Settings → Updates (#209): the update-check switch, on by default, and the "Update available" row (`Updates.kt`). */
@Composable
internal fun ColumnScope.UpdatesSettings() = UpdatesRows()

/** Whether Diagnostics has rows yet: the request log, Copy log and Report a bug (#207). */
internal const val DIAGNOSTICS_FILLED = true

/** Settings → Diagnostics (#207): the request log, newest first, Copy log and Report a bug (`DiagnosticsPane.kt`). */
@Composable
internal fun ColumnScope.DiagnosticsSettings() = DiagnosticsSection()

/** Whether About has rows yet: version, licence, source link and open-source licences (#206). */
internal const val ABOUT_FILLED = true

/** Settings → About (#206): the version, licence, source link and open-source licences. */
@Composable
internal fun ColumnScope.AboutSettings() = AboutRows()
