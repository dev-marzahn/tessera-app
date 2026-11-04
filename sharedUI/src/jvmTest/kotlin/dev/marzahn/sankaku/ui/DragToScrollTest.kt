package dev.marzahn.sankaku.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DragToScrollTest {
  private val list = LazyListState()
  private val clicked = mutableListOf<Int>()

  private fun androidx.compose.ui.test.ComposeUiTest.showList() = setContent {
    LazyColumn(Modifier.size(200.dp, 400.dp).testTag("list").dragToScroll(list), state = list) {
      items(100) { i -> Box(Modifier.fillMaxWidth().height(100.dp).clickable { clicked += i }) }
    }
  }

  @Test
  fun aMouseDragScrollsAndOpensNothing() = runComposeUiTest {
    showList()

    onNodeWithTag("list").performMouseInput {
      moveTo(Offset(100f, 350f))
      press()
      repeat(10) { moveBy(Offset(0f, -25f)) }
      release()
    }
    waitForIdle()

    assertTrue(list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0)
    assertEquals(emptyList(), clicked)
  }

  @Test
  fun aClickWithinTheSlopStillOpens() = runComposeUiTest {
    showList()

    onNodeWithTag("list").performMouseInput {
      moveTo(Offset(100f, 50f))
      press()
      moveBy(Offset(0f, -2f))
      release()
    }
    waitForIdle()

    assertEquals(0, list.firstVisibleItemIndex)
    assertEquals(0, list.firstVisibleItemScrollOffset)
    assertEquals(listOf(0), clicked)
  }
}
