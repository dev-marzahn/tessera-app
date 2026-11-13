package dev.marzahn.sankaku.features.grid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marzahn.sankaku.data.GridSettings
import org.koin.compose.koinInject

/** The grid's narrowest column: Settings → Grid → Post size once it's set, else its default (`PostSizes.default`). */
@Composable
fun gridMinCellWidth(settings: GridSettings = koinInject()): Dp {
  val cellWidth by settings.cellWidth.collectAsStateWithLifecycle()
  return (cellWidth ?: settings.sizes.default).dp
}

/**
 * How many columns of at least [minCell] a grid [width] wide fits with [gap] between them, as
 * `StaggeredGridCells.Adaptive` counts them: at least one. All in px.
 */
internal fun gridColumns(width: Int, minCell: Int, gap: Int): Int =
  ((width + gap) / (minCell + gap).coerceAtLeast(1)).coerceAtLeast(1)
