plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinx.serialization)
  application
}

dependencies {
  implementation(projects.sharedLogic)
  // The rate probe's own engine, with more than OkHttp's 5 requests per host in flight.
  implementation(libs.ktor.client.okhttp)
  runtimeOnly(libs.slf4j.nop)
}

application {
  mainClass = "dev.marzahn.sankaku.cli.MainKt"
}

// `.env.local`, `docs/api-recorded.json` and `cli/out/` are read and written relative to the repository root.
tasks.named<JavaExec>("run") {
  workingDir = rootProject.projectDir
}
