package dev.marzahn.sankaku

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference

/**
 * The Mac's model and chip for a bug report, from `sysctlbyname`: `hw.model` (`Mac15,12`) and
 * `machdep.cpu.brand_string` (`Apple M3`). No serial number or host name.
 */
internal object MacModel {
  private interface Sysctl : Library {
    fun sysctlbyname(name: String, value: Pointer?, size: LongByReference, newValue: Pointer?, newSize: Long): Int
  }

  private val sysctl by lazy { runCatching { Native.load("System", Sysctl::class.java) }.getOrNull() }

  /** `Mac15,12 (Apple M3)`, the model alone when the chip can't be read, or null off a Mac or when neither can. */
  fun describe(): String? {
    val model = read("hw.model")
    val chip = read("machdep.cpu.brand_string")
    return when {
      model != null && chip != null -> "$model ($chip)"
      else -> model ?: chip
    }
  }

  private fun read(name: String): String? = runCatching {
    val library = sysctl ?: return null
    val size = LongByReference()
    if (library.sysctlbyname(name, null, size, null, 0) != 0 || size.value <= 0) return null
    val buffer = Memory(size.value)
    if (library.sysctlbyname(name, buffer, size, null, 0) != 0) return null
    buffer.getString(0).trim().ifEmpty { null }
  }.getOrNull()
}
