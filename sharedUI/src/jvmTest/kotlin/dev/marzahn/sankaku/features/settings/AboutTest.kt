package dev.marzahn.sankaku.features.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import dev.marzahn.sankaku.APP_VERSION
import dev.marzahn.sankaku.platformBackKeys
import dev.marzahn.sankaku.resources.Res
import dev.marzahn.sankaku.ui.Messages
import dev.marzahn.sankaku.ui.theme.SankakuTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings → About (#206): its rows, the licences page over Settings, and the list the build bundles. State and
 * semantics only; how it looks is checked in the running app.
 */
@OptIn(ExperimentalTestApi::class)
class AboutTest {
  private val export = """
    {"libraries": [{"uniqueId": "a:lib", "name": "Some Library", "artifactVersion": "1.2", "licenses": ["MIT"], "targets": ["jvm"]}],
     "licenses": {"MIT": {"name": "MIT License", "content": "Permission is hereby granted"}}}
  """.trimIndent()

  @Test
  fun aboutShowsTheVersionTheLicenceAndTheSource() = runComposeUiTest {
    setContent { SankakuTheme { Column { AboutRows(messages = Messages()) } } }

    onNodeWithText("Version").assertExists()
    onNodeWithText(APP_VERSION).assertExists()
    onNodeWithText("GPL-3.0-only · Copyright the Tessera authors").assertExists()
    onNodeWithText("Source code").assertExists()
    onNodeWithText("github.com/dev-marzahn/tessera-app").assertExists()
    onNodeWithText(OpenSourceLicencesTitle).assertExists()
  }

  @Test
  fun theLicencesPageListsEachLibraryAndOpensItsLicenceText() = runComposeUiTest {
    var dismissed = false
    setContent { SankakuTheme { OpenSourceLicences(onDismiss = { dismissed = true }, touch = false, load = { export }) } }

    onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, OpenSourceLicencesTitle)).assertExists()
    onNodeWithText("Some Library").assertIsDisplayed()
    onNodeWithText("a:lib · 1.2").assertExists()
    onNodeWithText("MIT License").assertExists()
    onNodeWithText("Permission is hereby granted").assertDoesNotExist()

    onNodeWithText("Some Library").performClick()
    waitForIdle()
    onNodeWithText("Permission is hereby granted").assertIsDisplayed()

    onNodeWithContentDescription("Close").performClick()
    assertTrue(dismissed)
  }

  @Test
  fun escClosesOnlyTheLicencesPageNotTheSettingsTabBehindIt() = runComposeUiTest {
    var tabClosed = 0
    var pageClosed = 0
    setContent {
      SankakuTheme {
        var showing by remember { mutableStateOf(true) }
        val behind = remember { FocusRequester() }
        // The Settings tab's Esc, as App's tab content has it, with focus on a row behind the page.
        Box(Modifier.platformBackKeys { tabClosed++ }) {
          Box(Modifier.focusRequester(behind).focusable())
          LaunchedEffect(Unit) { behind.requestFocus() }
          if (showing) {
            OpenSourceLicences(onDismiss = { pageClosed++; showing = false }, touch = false, load = { export })
          }
        }
      }
    }
    waitForIdle()

    onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, OpenSourceLicencesTitle))
      .performKeyInput { pressKey(Key.Escape) }
    waitForIdle()

    assertEquals(1, pageClosed)
    assertEquals(0, tabClosed)
  }

  @Test
  fun whileTheListIsReadThePageShowsItsLoading() = runComposeUiTest {
    val read = CompletableDeferred<String>()
    setContent { SankakuTheme { OpenSourceLicences(onDismiss = {}, touch = false, load = { read.await() }) } }

    val loading = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)
    onNode(loading).assertExists()

    read.complete(export)
    waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Some Library")).fetchSemanticsNodes().isNotEmpty() }
    onNode(loading).assertDoesNotExist()
  }

  @Test
  fun aListThatCantBeReadSaysSo() = runComposeUiTest {
    setContent { SankakuTheme { OpenSourceLicences(onDismiss = {}, touch = true, load = { error("missing") }) } }

    onNode(hasText("Couldn't read the licences")).assertExists()
    onNodeWithContentDescription("Back").assertExists()
  }

  @Test
  fun theBuildBundlesEachPlatformsLibrariesWithTheirLicenceTexts() = runTest {
    val json = Res.readBytes(LICENCES_RESOURCE).decodeToString()
    val desktop = openSourceLibraries(json, "jvm")
    val phone = openSourceLibraries(json, "android")
    val desktopIds = desktop.map { it.id }.toSet()
    val phoneIds = phone.map { it.id }.toSet()

    // Each platform's own libraries, and the shared ones in both.
    assertTrue("com.github.javakeyring:java-keyring" in desktopIds)
    assertTrue("org.slf4j:slf4j-nop" in desktopIds)
    assertTrue("com.google.crypto.tink:tink-android" !in desktopIds)
    assertTrue("com.google.crypto.tink:tink-android" in phoneIds)
    assertTrue("com.github.javakeyring:java-keyring" !in phoneIds)
    listOf("io.ktor:ktor-client-core", "io.coil-kt.coil3:coil", "org.jetbrains.kotlin:kotlin-stdlib").forEach {
      assertTrue(it in desktopIds && it in phoneIds, it)
    }
    // No test-only library ships.
    assertTrue(listOf("app.cash.turbine:turbine", "io.ktor:ktor-client-mock").none { it in desktopIds || it in phoneIds })
    // java-keyring's POM names "BSD-3" with no text; sharedUI/licences gives it BSD-3-Clause's.
    val keyring = desktop.single { it.id == "com.github.javakeyring:java-keyring" }
    assertEquals(listOf("BSD 3-Clause \"New\" or \"Revised\" License"), keyring.licences.map { it.name })
    // Every library has a licence with its full text, so the list reads offline.
    val textless = (desktop + phone).filter { library -> library.licences.none { it.text != null } }.map { it.id }
    assertEquals(emptyList(), textless)
  }
}
