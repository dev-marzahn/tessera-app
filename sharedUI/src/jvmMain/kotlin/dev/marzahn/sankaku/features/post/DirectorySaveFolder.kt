package dev.marzahn.sankaku.features.post

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.RawSink
import kotlinx.io.asSink
import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A folder on disk (`~/Downloads/Tessera`). A file is written as a hidden `.part` beside its name and moved into place
 * once whole, so a half-written file never carries the name [find] looks for.
 */
class DirectorySaveFolder(private val dir: File, private val opener: (File) -> Unit = ::openWithDesktop) : SaveFolder {
  override suspend fun find(name: String): SavedFile? = withContext(Dispatchers.IO) {
    File(dir, name).takeIf { it.isFile }?.let(::file)
  }

  override suspend fun save(name: String, mimeType: String?, write: suspend (RawSink) -> Unit): SavedFile =
    withContext(Dispatchers.IO) {
      dir.mkdirs()
      val target = File(dir, name)
      val part = File(dir, ".$name.part")
      try {
        part.outputStream().asSink().use { write(it) }
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      } catch (e: Throwable) {
        part.delete()
        throw e
      }
      file(target)
    }

  private fun file(file: File) = SavedFile { opener(file) }
}

/** Opens [file] in the app the system uses for its type (Preview, QuickTime). */
private fun openWithDesktop(file: File) {
  // Off the UI thread: `Desktop.open` waits for Launch Services.
  Thread { runCatching { Desktop.getDesktop().open(file) } }.start()
}
