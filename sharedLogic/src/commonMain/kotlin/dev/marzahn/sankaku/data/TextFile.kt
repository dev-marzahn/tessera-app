package dev.marzahn.sankaku.data

/** One small text file the app keeps between runs, such as the known tags. Platforms back it with a real file. */
interface TextFile {
  /** Null when there's nothing yet or it can't be read. */
  fun read(): String?

  fun write(text: String)

  /** Removes the file, so [read] returns null; nothing happens if there's none. */
  fun delete()
}

/** For tests and for platforms without a store yet. */
class InMemoryTextFile(private var text: String? = null) : TextFile {
  override fun read(): String? = text

  override fun write(text: String) {
    this.text = text
  }

  override fun delete() {
    text = null
  }
}
