package dev.marzahn.sankaku.data

import java.io.File

/** The desktop app's folders under the user's [home] (v1 ships for macOS only). */
class DesktopFolders(private val home: File) {
  val appData: File get() = File(home, "Library/Application Support/Tessera")
  val cache: File get() = File(home, "Library/Caches/Tessera")

  /** Where Save puts a post's original file. */
  val downloads: File get() = File(home, "Downloads/Tessera")

  /** The app data folder from before the rename to Tessera, which [AppDataMigration] empties. */
  val oldAppData: File get() = File(home, "Library/Application Support/Sankaku")
}
