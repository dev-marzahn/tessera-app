plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinx.serialization)
  application
}

dependencies {
  implementation(projects.sharedLogic)
  runtimeOnly(libs.slf4j.nop)
}

application {
  mainClass = "dev.marzahn.sankaku.cli.MainKt"
}

// `.env.local`, `docs/api-recorded.json` and `cli/out/` are read and written relative to the repository root.
tasks.named<JavaExec>("run") {
  workingDir = rootProject.projectDir
}
