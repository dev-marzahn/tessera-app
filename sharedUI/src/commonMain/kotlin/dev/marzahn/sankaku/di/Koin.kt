package dev.marzahn.sankaku.di

import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration

/** The app's modules around the [platform] one, in load order; a test can add an override after them. */
fun appModules(platform: Module): List<Module> =
  listOf(sharedModule, platform, shellModule, authModule, gridModule, postModule, booksModule)

/**
 * Starts Koin outside Compose, so state survives Android Activity recreation. Called from the desktop `main` and, once
 * per process, Android's first activity (`SankakuApplication.startKoinOnce`).
 */
fun startSankakuKoin(platform: Module, extra: KoinAppDeclaration = {}): KoinApplication = startKoin {
  extra()
  modules(appModules(platform))
}
