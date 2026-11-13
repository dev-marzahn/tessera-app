package dev.marzahn.sankaku.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SavedGrid(val cellWidth: Int? = null)

/**
 * The sizes Post size offers, in dp: from [min] to [max] in steps of [step], and the one used until it's set,
 * [default]. [Desktop]'s suit a window: 220 dp is wide enough that a first page fills a large window once. [Phone]'s
 * suit a phone's 360 to 450 dp: 110 dp makes three columns on a 360 dp one (348 dp of grid between its 6 dp edges,
 * 4 dp gaps), where 120 dp made two (#468).
 */
data class PostSizes(val min: Int, val max: Int, val step: Int, val default: Int) {
  init {
    require(step > 0 && min < max && (max - min) % step == 0) { "max - min must be a whole number of steps" }
    require(default in min..max && (default - min) % step == 0) { "the default must be one of the sizes" }
  }

  /** [dp] in the range, at the nearest step. */
  fun snap(dp: Int): Int {
    val clamped = dp.coerceIn(min, max)
    val steps = (clamped - min + step / 2) / step
    return min + steps * step
  }

  companion object {
    val Desktop = PostSizes(min = 160, max = 560, step = 20, default = 220)
    val Phone = PostSizes(min = 80, max = 200, step = 10, default = 110)
  }
}

/**
 * The grid's settings, kept in [file] between runs and written at once.
 *
 * - [cellWidth] ("Post size"): the narrowest a grid column may be, in dp, one of [sizes]. The grid fits as many
 *   columns of at least that as its width holds. Null until it's set: [PostSizes.default].
 *
 * "Clear everything" leaves this file alone: the settings are preferences, not history.
 */
class GridSettings(private val file: TextFile, val sizes: PostSizes = PostSizes.Desktop) {
  private val saved = file.read()?.let { runCatching { json.decodeFromString(SavedGrid.serializer(), it) }.getOrNull() }
    ?: SavedGrid()

  private val _cellWidth = MutableStateFlow(saved.cellWidth?.let(sizes::snap))
  val cellWidth: StateFlow<Int?> = _cellWidth.asStateFlow()

  /** Sets [cellWidth] to [dp], snapped to [sizes]. */
  fun setCellWidth(dp: Int) {
    val snapped = sizes.snap(dp)
    if (_cellWidth.value == snapped) return
    _cellWidth.value = snapped
    // The setting has already changed for this run; a full disk or locked file only costs it the next restart.
    runCatching { file.write(json.encodeToString(SavedGrid.serializer(), SavedGrid(snapped))) }
  }

  private companion object {
    val json = Json { ignoreUnknownKeys = true }
  }
}
