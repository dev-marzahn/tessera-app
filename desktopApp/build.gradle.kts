import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import javax.inject.Inject

plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.composeCompiler)
}

dependencies {
  implementation(projects.sharedUI)
  implementation(compose.desktop.currentOs)
  implementation(libs.kotlinx.coroutines.swing)
  implementation(libs.jna)
}

// `-D` on the Gradle command line only reaches the Gradle JVM, so every `sankaku.*` property is forwarded to `run`.
tasks.withType<JavaExec>().configureEach {
  val forwarded = providers.systemPropertiesPrefixedBy("sankaku.")
  jvmArgumentProviders += CommandLineArgumentProvider {
    forwarded.get().map { (key, value) -> "-D$key=$value" }
  }
}

// From the owner's ~/.gradle/gradle.properties: the Developer ID Application identity (the part after
// "Developer ID Application: ") and the name of a `notarytool store-credentials` keychain profile.
// Blank counts as unset, as in the root project's release checks.
val signingIdentity = providers.gradleProperty("tessera.mac.signingIdentity").filter { it.isNotBlank() }
val notaryProfile = providers.gradleProperty("tessera.mac.notaryProfile").filter { it.isNotBlank() }

compose.desktop {
  application {
    mainClass = "dev.marzahn.sankaku.MainKt"
    jvmArgs += "-Xmx512m"

    nativeDistributions {
      targetFormats(TargetFormat.Dmg)
      packageName = "Tessera"
      packageVersion = libs.versions.tessera.get()
      // From `./gradlew :desktopApp:suggestRuntimeModules`, plus what java-keyring (JNA) and TLS need.
      modules("java.instrument", "java.management", "java.naming", "jdk.crypto.ec", "jdk.unsupported")
      macOS {
        bundleID = "dev.marzahn.tessera"
        iconFile = project.file("src/main/resources/icon.icns")
        // Signed with the hardened runtime when the identity is set. Compose's default entitlements (JIT, unsigned
        // executable memory, no library validation) are what the JVM and JNA need.
        signing {
          sign = signingIdentity.isPresent
          identity = signingIdentity
        }
      }
    }
  }
}

/**
 * The release DMG in build/release-dmg/. With both Mac properties (so the DMG is signed), it's notarized, stapled and
 * checked with Gatekeeper; without one, only the app inside is (ad-hoc) signed, and macOS opens it after "Open Anyway".
 */
abstract class ReleaseDmg @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
  @get:InputDirectory
  abstract val dmgDir: DirectoryProperty

  @get:Input
  abstract val appVersion: Property<String>

  @get:Input
  @get:Optional
  abstract val keychainProfile: Property<String>

  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  @TaskAction
  fun run() {
    val dmg = dmgDir.get().asFile.resolve("Tessera-${appVersion.get()}.dmg")
    val out = outputDir.get().asFile.resolve(dmg.name)
    outputDir.get().asFile.listFiles()!!.forEach { it.delete() }
    dmg.copyTo(out)
    val profile = keychainProfile.orNull
    if (profile == null) {
      logger.warn("${dmg.name} is not notarized: set tessera.mac.signingIdentity and tessera.mac.notaryProfile to notarize it.")
      return
    }
    exec.exec { commandLine("xcrun", "notarytool", "submit", out, "--keychain-profile", profile, "--wait") }
    // Fails unless Apple accepted it, so an invalid submission stops here.
    exec.exec { commandLine("xcrun", "stapler", "staple", out) }
    exec.exec { commandLine("xcrun", "stapler", "validate", out) }
    exec.exec { commandLine("spctl", "--assess", "--type", "open", "--context", "context:primary-signature", "-v", out) }
  }
}

tasks.register<ReleaseDmg>("releaseDmg") {
  description = "The release DMG: notarized when tessera.mac.signingIdentity and tessera.mac.notaryProfile are set."
  dependsOn("packageDmg")
  dmgDir = layout.buildDirectory.dir("compose/binaries/main/dmg")
  appVersion = libs.versions.tessera
  // Only with both: notarizing an unsigned DMG fails at Apple, after the upload.
  keychainProfile = signingIdentity.flatMap { notaryProfile }
  outputDir = layout.buildDirectory.dir("release-dmg")
}

// `release` checks the signing properties before the long build starts.
tasks.matching { it.name == "packageDmg" }.configureEach {
  mustRunAfter(":checkAndroidReleaseProperties", ":checkMacReleaseProperties")
}
