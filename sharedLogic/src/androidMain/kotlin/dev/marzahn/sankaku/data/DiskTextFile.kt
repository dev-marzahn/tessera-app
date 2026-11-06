package dev.marzahn.sankaku.data

import java.io.File
import java.nio.file.StandardCopyOption

/** [file] on disk, replaced whole on each write so a crash mid-write leaves the previous version. */
class DiskTextFile(private val file: File) : TextFile {
  override fun read(): String? = runCatching { file.readText() }.getOrNull()

  override fun write(text: String) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, "${file.name}.tmp")
    temp.writeText(text)
    java.nio.file.Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  override fun delete() {
    file.delete()
  }
}
