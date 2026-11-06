package dev.marzahn.sankaku.ui

import dev.marzahn.sankaku.data.InMemoryTextFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TipsTest {
  @Test
  fun aTipShowsOnceAndNotAfterARestart() {
    val file = InMemoryTextFile()
    val tips = Tips(file, enabled = true)
    assertTrue(tips.offer(Tip.GridGestures))
    assertEquals(Tip.GridGestures, tips.current)
    tips.appeared(Tip.GridGestures)
    tips.dismissed(Tip.GridGestures)
    assertNull(tips.current)
    assertFalse(tips.offer(Tip.GridGestures))

    val restarted = Tips(file, enabled = true)
    assertFalse(restarted.offer(Tip.GridGestures))
    assertTrue(restarted.offer(Tip.TabList))
  }

  @Test
  fun theReopenTipShowsOnceAndNotAfterARestart() {
    val file = InMemoryTextFile()
    val tips = Tips(file, enabled = true)
    assertTrue(tips.offer(Tip.ReopenHold))
    tips.appeared(Tip.ReopenHold)
    // Got it, a tap elsewhere or back.
    tips.dismissed(Tip.ReopenHold)
    assertNull(tips.current)
    assertFalse(tips.offer(Tip.ReopenHold))
    assertFalse(Tips(file, enabled = true).offer(Tip.ReopenHold))
  }

  @Test
  fun aTipCutShortWhileOnScreenCountsAsSeen() {
    val file = InMemoryTextFile()
    val tips = Tips(file, enabled = true)
    tips.offer(Tip.TabList)
    tips.appeared(Tip.TabList)
    // The app closed with the tip on screen.
    assertFalse(Tips(file, enabled = true).pending(Tip.TabList))
  }

  @Test
  fun aTipWhoseAnchorWentBeforeItShowedComesLaterAndFreesItsPlace() {
    val file = InMemoryTextFile()
    val tips = Tips(file, enabled = true)
    assertTrue(tips.offer(Tip.GridGestures))
    // Its cell scrolled away, or the viewer reopened, during the wait.
    tips.dismissed(Tip.GridGestures)
    assertNull(tips.current)
    assertTrue(tips.pending(Tip.GridGestures))
    assertTrue(Tips(file, enabled = true).pending(Tip.GridGestures))
    // The other tip isn't held up meanwhile.
    assertTrue(tips.offer(Tip.TabList))
  }

  @Test
  fun aTipWhoseActionWasUsedNeverShows() {
    val file = InMemoryTextFile()
    val tips = Tips(file, enabled = true)
    tips.used(Tip.GridGestures)
    tips.used(Tip.TabList)
    assertFalse(tips.offer(Tip.GridGestures))
    assertFalse(tips.offer(Tip.TabList))
    assertNull(tips.current)
    assertFalse(Tips(file, enabled = true).pending(Tip.GridGestures))
  }

  @Test
  fun usingOneTipsActionLeavesTheOther() {
    val tips = Tips(InMemoryTextFile(), enabled = true)
    tips.used(Tip.GridGestures)
    assertTrue(tips.offer(Tip.TabList))
  }

  @Test
  fun oneTipAtATime() {
    val tips = Tips(InMemoryTextFile(), enabled = true)
    assertTrue(tips.offer(Tip.GridGestures))
    assertFalse(tips.offer(Tip.TabList))
    // Still pending: it comes once the first has gone.
    assertTrue(tips.pending(Tip.TabList))
    tips.dismissed(Tip.TabList)
    assertEquals(Tip.GridGestures, tips.current)
    tips.dismissed(Tip.GridGestures)
    assertTrue(tips.offer(Tip.TabList))
  }

  @Test
  fun aSecondTabAppearsOnlyOnARiseFromOne() {
    val tips = Tips(InMemoryTextFile(), enabled = true)
    // Two tabs restored at launch: no tip at launch.
    tips.counted(2)
    assertFalse(tips.secondTabAppeared)
    tips.counted(1)
    assertFalse(tips.secondTabAppeared)
    tips.counted(2)
    assertTrue(tips.secondTabAppeared)
  }

  @Test
  fun disabledShowsNone() {
    val tips = Tips(InMemoryTextFile(), enabled = false)
    assertFalse(tips.offer(Tip.GridGestures))
    assertFalse(tips.offer(Tip.TabList))
  }

  @Test
  fun anUnreadableFileStartsFresh() {
    assertTrue(Tips(InMemoryTextFile("not json"), enabled = true).offer(Tip.GridGestures))
  }
}
