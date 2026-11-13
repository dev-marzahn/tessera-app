import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidMultiplatformLibrary)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.kotlinx.serialization)
  alias(libs.plugins.koin.compiler)
  alias(libs.plugins.aboutlibraries)
}

// Every compilation here holds a startKoin (commonMain, and the jvmTest harnesses), so the plugin's own guess is strict
// too. Left unset, it guesses by reading every source file while configuring, which makes any source edit invalidate
// the configuration cache.
koinCompiler {
  strictSafety = true
}

// Settings → About's open-source licences (#206): every dependency's name, version and licence text, collected at build
// time into a Compose resource, so the list reads offline on both platforms. Each library names the Kotlin targets it's
// in (`targets`), so each platform lists only its own.
val licencesFile = layout.buildDirectory.file("generated/aboutLibraries/aboutlibraries.json")
aboutLibraries {
  // The licence texts come from `licences/licenses/` (SPDX's, by id), not the network, so every build lists the same
  // texts. A dependency under a licence not there yet fails AboutTest: add its file.
  offlineMode = true
  collect {
    // Also fixes what a POM gets wrong (java-keyring names its licence "BSD-3", with no text), in `licences/libraries/`.
    configPath = file("licences")
    includeTargets = true
  }
  export {
    outputFile = licencesFile
    excludeFields.addAll("developers", "funding", "organization", "scm")
  }
  library {
    duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE
    mergePlatformArtifacts = true
  }
}

// The export as `files/aboutlibraries.json` in a resource folder of its own: the plugin's Android task writes next to
// the export file too, which the resource tasks mustn't read.
val licencesResourcesDir = layout.buildDirectory.dir("generated/licencesResources")
val licencesResources = tasks.register<Sync>("licencesResources") {
  from(tasks.named("exportLibraryDefinitions")) { include("aboutlibraries.json") }
  into(licencesResourcesDir.map { it.dir("files") })
}

compose.resources {
  packageOfResClass = "dev.marzahn.sankaku.resources"
  customDirectory(
    sourceSetName = "commonMain",
    directoryProvider = licencesResources.map { licencesResourcesDir.get() },
  )
}

kotlin {
  jvm()

  android {
    namespace = "dev.marzahn.sankaku.ui"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()

    compilerOptions {
      jvmTarget = JvmTarget.JVM_11
    }
    androidResources {
      enable = true
    }
    // Stubbed android.jar returns defaults, so a host test can build an Intent and throw an Android exception (#358).
    withHostTest {
      isReturnDefaultValues = true
    }
  }

  sourceSets {
    commonMain.dependencies {
      api(projects.sharedLogic)
      implementation(libs.compose.runtime)
      implementation(libs.compose.foundation)
      implementation(libs.compose.ui)
      implementation(libs.compose.components.resources)
      implementation(libs.compose.uiToolingPreview)
      implementation(libs.compose.material3)
      implementation(libs.jetbrains.material3.adaptiveNavigation3)
      implementation(libs.androidx.navigation3.runtime)
      implementation(libs.jetbrains.navigation3.ui)
      implementation(libs.androidx.lifecycle.viewmodelCompose)
      implementation(libs.androidx.lifecycle.runtimeCompose)
      implementation(libs.jetbrains.lifecycle.viewmodelNavigation3)
      api(libs.koin.core)
      implementation(libs.koin.compose)
      implementation(libs.koin.compose.viewmodel)
      implementation(libs.koin.compose.navigation3)
      implementation(libs.androidx.paging.compose)
      implementation(libs.coil.compose)
      implementation(libs.coil.network.ktor3)
      implementation(libs.material.kolor)
      implementation(libs.zoomimage.compose.coil3)
      implementation(libs.kotlinx.datetime)
    }
    jvmMain.dependencies {
      implementation(compose.desktop.currentOs)
      // Desktop's video player; Android plays through Media3 directly (#189).
      implementation(libs.composemediaplayer)
      implementation(libs.kotlinx.coroutines.swing)
      implementation(libs.java.keyring)
      implementation(libs.jna)
      // The window's Esc as a back handler under the dialogs' own (#364).
      implementation(libs.jetbrains.navigationevent.compose)
      // Ktor logs through SLF4J; this silences it. Here rather than in :desktopApp, so the licences list it (#206).
      runtimeOnly(libs.slf4j.nop)
    }
    androidMain.dependencies {
      implementation(libs.kotlinx.coroutines.android)
      api(libs.koin.android)
      implementation(libs.androidx.activity.compose)
      implementation(libs.coil.gif)
      implementation(libs.androidx.media3.exoplayer)
    }
    jvmTest.dependencies {
      implementation(libs.compose.uiTest)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.turbine)
      implementation(libs.ktor.client.mock)
    }
  }
}

// The desktop UI tests are most of CI's build time (about 3 of its 6 minutes on the build runner), one class after
// another. Forks run classes side by side, each its own JVM, so each harness's startKoin stays its own; 45 s to 26 s
// locally. At most 4: the build runner's 7 GB also holds Gradle's 4 GB.
// Those tests start AWT, which on macOS gives each test JVM a Java icon in the Dock while it runs. As a UI element it
// stays out of the Dock and still renders.
tasks.withType<Test>().configureEach {
  maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
  systemProperty("apple.awt.UIElement", "true")
}
