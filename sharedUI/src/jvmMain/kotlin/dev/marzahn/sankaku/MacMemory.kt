package dev.marzahn.sankaku

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * The process's `phys_footprint` from Mach's `task_info(TASK_VM_INFO)`, the number Activity Monitor shows as Memory.
 * The JVM's own figures (heap, `Runtime`) leave out native image memory and Skia surfaces, most of what the app uses.
 */
internal object MacMemory {
  @Suppress("FunctionName")
  private interface Mach : Library {
    fun task_info(task: Int, flavor: Int, info: Pointer, count: IntByReference): Int
  }

  private const val TASK_VM_INFO = 22

  /** `phys_footprint`'s offset in `task_vm_info_data_t`: after 17 64-bit fields and 2 ints. */
  private const val PHYS_FOOTPRINT_OFFSET = 144L

  /** Room for the whole struct in any revision (natural_t units); the kernel fills what it has and says how much. */
  private const val INFO_INTS = 128

  /** Through `phys_footprint` (`TASK_VM_INFO_REV1_COUNT`); a shorter answer is an older kernel without it. */
  private const val REV1_INTS = ((PHYS_FOOTPRINT_OFFSET + 8) / 4).toInt()

  private val mach by lazy {
    runCatching {
      val system = NativeLibrary.getInstance("System")
      // mach_task_self() is a macro for this global.
      val task = system.getGlobalVariableAddress("mach_task_self_").getInt(0)
      Native.load("System", Mach::class.java) to task
    }.getOrNull()
  }

  fun physFootprint(): Long? {
    val (library, task) = mach ?: return null
    val info = Memory(INFO_INTS * 4L)
    val count = IntByReference(INFO_INTS)
    if (library.task_info(task, TASK_VM_INFO, info, count) != 0 || count.value < REV1_INTS) return null
    return info.getLong(PHYS_FOOTPRINT_OFFSET)
  }
}
