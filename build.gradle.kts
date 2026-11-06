plugins {
  // this is necessary to avoid the plugins to be loaded multiple times
  // in each subproject's classloader
  alias(libs.plugins.androidApplication) apply false
  alias(libs.plugins.androidMultiplatformLibrary) apply false
  alias(libs.plugins.composeMultiplatform) apply false
  alias(libs.plugins.composeCompiler) apply false
  alias(libs.plugins.kotlinJvm) apply false
  alias(libs.plugins.kotlinMultiplatform) apply false
  alias(libs.plugins.kotlinx.serialization) apply false
  alias(libs.plugins.koin.compiler) apply false
  alias(libs.plugins.aboutlibraries) apply false

  // The dependency-update check: `versionCatalogUpdate --interactive`, then `versionCatalogApplyUpdates`.
  alias(libs.plugins.ben.manes.versions)
  alias(libs.plugins.version.catalog.update)
}

// `compat`: kotlinx-datetime's 0.8.0-0.6.x-compat builds, which versionCatalogUpdate proposed as an update (#213).
private val preRelease = Regex("""(?i)[.-](alpha|beta|rc|cr|m|eap|dev|snapshot|compat)[.\d-]*""")

private fun isPreRelease(version: String) = preRelease.containsMatchIn(version)

private fun minorOf(version: String) = version.split('.').take(2).joinToString(".")

private val composeVersion = libs.versions.composeMultiplatform.get()

/** Whether the update check skips [candidate]: AGENTS.md → Versions. */
private fun skipsUpdate(candidate: ModuleComponentIdentifier, currentVersion: String): Boolean {
  val isMaterial3 = candidate.group == "org.jetbrains.compose.material3" && candidate.module == "material3"
  return if (isMaterial3) {
    // material3 tracks the newest pre-release whose minor version matches Compose Multiplatform's.
    minorOf(candidate.version) != minorOf(composeVersion)
  } else {
    isPreRelease(candidate.version) && !isPreRelease(currentVersion)
  }
}

// versionCatalogUpdate picks versions with its own selector, not dependencyUpdates' rejectVersionIf: without this it
// proposed material3 1.13.0-beta01 with Compose Multiplatform 1.12.2 (#213).
versionCatalogUpdate {
  // Keeps the catalog's order and its group comments; sorted, versionCatalogApplyUpdates scatters them (#213). It
  // still drops the blank lines between the groups: put them back before committing.
  sortByKey = false
  versionSelector(
    object : nl.littlerobots.vcu.plugin.resolver.ModuleVersionSelector {
      override fun select(candidate: nl.littlerobots.vcu.plugin.resolver.ModuleVersionCandidate) =
        !skipsUpdate(candidate.candidate, candidate.currentVersion)
    },
  )
}

tasks.named<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask>("dependencyUpdates") {
  rejectVersionIf { skipsUpdate(candidate, currentVersion) }
}
// `./gradlew release`: the signed, R8-minified APK and the DMG of the catalog's `tessera` version, in build/release/.
// It's `releaseApk` plus `releaseMac`, which the public repository's release workflow runs on Ubuntu and macOS. Both
// read the signing settings as Gradle properties from ~/.gradle/gradle.properties; the workflow passes the APK's as
// TESSERA_SIGNING_* environment variables instead. The DMG is signed and notarized when both Mac properties are set.
// Publishing is the README's release checklist.
val releaseVersion = libs.versions.tessera.get()
val releaseDir = layout.buildDirectory.dir("release")

// Blank counts as missing: the workflow passes a secret the environment lacks as an empty variable.
fun missingProperties(names: List<String>) = names.filter { providers.gradleProperty(it).orNull.isNullOrBlank() }

fun registerPropertyCheck(name: String, problem: Provider<String>) = tasks.register(name) {
  doLast {
    check(problem.get().isEmpty()) {
      "release: ${problem.get()} in ~/.gradle/gradle.properties (see the README's Releases section)"
    }
  }
}

/** Copies the built files into build/release/ under their release names, keeping the other platform's files there. */
fun registerReleaseCopy(name: String, files: Map<String, String>, keep: List<String>, configure: Sync.() -> Unit) =
  tasks.register<Sync>(name) {
    group = "distribution"
    configure()
    files.forEach { (release, built) -> from(layout.projectDirectory.file(built)) { rename { release } } }
    into(releaseDir)
    preserve { include(keep) }
    // A missing source is skipped silently, so a moved output path shows up here instead of as a missing upload.
    val out = releaseDir
    val expected = files.keys
    doLast {
      val missing = expected.filterNot { out.get().file(it).asFile.exists() }
      check(missing.isEmpty()) { "release: ${missing.joinToString()} not built" }
    }
  }

// The APK's signing settings: the tessera.signing.* Gradle property, or else the TESSERA_SIGNING_* environment variable
// (storeFile → TESSERA_SIGNING_STORE_FILE), which the release workflow sets. Not ORG_GRADLE_PROJECT_tessera.signing.*:
// gradlew runs under /bin/sh, and Ubuntu's dash drops variables with dots in their names (#213).
// androidApp/build.gradle.kts reads them the same way.
val androidSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")

fun androidSigningEnv(name: String) = "TESSERA_SIGNING_" + name.replace(Regex("([A-Z])"), "_$1").uppercase()

val checkAndroidReleaseProperties = registerPropertyCheck(
  "checkAndroidReleaseProperties",
  providers.provider {
    androidSigning.filter {
      providers.gradleProperty("tessera.signing.$it").orNull.isNullOrBlank() &&
        providers.environmentVariable(androidSigningEnv(it)).orNull.isNullOrBlank()
    }.let { missing ->
      if (missing.isEmpty()) "" else "set ${missing.joinToString { "tessera.signing.$it (or ${androidSigningEnv(it)})" }}"
    }
  },
)

val macSigning = listOf("tessera.mac.signingIdentity", "tessera.mac.notaryProfile")
val checkMacReleaseProperties = registerPropertyCheck(
  "checkMacReleaseProperties",
  providers.provider {
    missingProperties(macSigning).singleOrNull()?.let { "set $it too, or neither Mac property" } ?: ""
  },
)

val releaseApk = registerReleaseCopy(
  "releaseApk",
  mapOf(
    "Tessera-$releaseVersion.apk" to "androidApp/build/outputs/apk/release/androidApp-release.apk",
    // R8's mapping, to read the release APK's stack traces.
    "Tessera-$releaseVersion-mapping.txt" to "androidApp/build/outputs/mapping/release/mapping.txt",
  ),
  keep = listOf("*.dmg"),
) {
  description = "Builds the signed, R8-minified release APK and its mapping into build/release/."
  dependsOn(checkAndroidReleaseProperties, ":androidApp:assembleRelease")
}

val releaseMac = registerReleaseCopy(
  "releaseMac",
  mapOf("Tessera-$releaseVersion.dmg" to "desktopApp/build/release-dmg/Tessera-$releaseVersion.dmg"),
  keep = listOf("*.apk", "*-mapping.txt"),
) {
  description = "Builds the release DMG (notarized when configured) into build/release/."
  dependsOn(checkMacReleaseProperties, ":desktopApp:releaseDmg")
  mustRunAfter(releaseApk)
}

tasks.register("release") {
  group = "distribution"
  description = "Builds the signed release APK and the DMG (notarized when configured) into build/release/."
  dependsOn(releaseApk, releaseMac)
}
