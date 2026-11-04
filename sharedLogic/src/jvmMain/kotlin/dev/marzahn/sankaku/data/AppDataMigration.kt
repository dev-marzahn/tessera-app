package dev.marzahn.sankaku.data

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files

/**
 * Moves the app data folder from before the rename to Tessera (`Sankaku`) into the new one: saved tabs, known tags,
 * player settings and whatever else is in it. Tokens aren't moved (they're in the keychain, under a service that was
 * renamed too), so the user signs in once more.
 *
 * Runs on every launch before anything opens a file in [to]; once [from] is gone it does nothing. It first takes the
 * old folder's `instance.lock`: while a pre-rename build still runs and holds it, nothing moves, and the next launch
 * tries again. Otherwise every entry but the lock moves; a file that already exists in [to] wins and its old copy
 * stays in [from], which is then kept. A file that can't be moved is left where it is and retried on the next launch.
 * Once the lock is released, the old lock file and then [from] are deleted (the folder only if it's empty).
 */
object AppDataMigration {
  /** The single-instance lock file `SingleInstance` keeps in the app data folder. */
  private const val LOCK_FILE = "instance.lock"

  fun run(from: File, to: File) {
    if (!from.isDirectory) return
    val lockFile = File(from, LOCK_FILE)
    val moved = try {
      RandomAccessFile(lockFile, "rw").use { file ->
        val lock = try {
          file.channel.tryLock()
        } catch (_: OverlappingFileLockException) {
          null // Held by this JVM.
        } ?: return
        lock.use { moveEntries(from, to) }
      }
      true
    } catch (_: IOException) {
      false // The lock couldn't be taken; the next launch tries again.
    }
    if (!moved) return
    lockFile.delete()
    // Only succeeds once the folder is empty.
    from.delete()
  }

  private fun moveEntries(from: File, to: File) {
    val entries = from.listFiles() ?: return
    to.mkdirs()
    for (entry in entries) {
      if (entry.name == LOCK_FILE) continue
      val target = File(to, entry.name)
      if (target.exists()) continue
      try {
        Files.move(entry.toPath(), target.toPath())
      } catch (_: IOException) {
        // Left in place; the next launch tries again.
      }
    }
  }
}
