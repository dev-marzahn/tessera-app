package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.ui.Tip
import dev.marzahn.sankaku.ui.Tips
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The old top-line hint's `reopen-hint.json`, folded into [Tip.ReopenHold] (#344). */
class ReopenHintMigrationTest {
  @Test
  fun anInstallThatHadUsedTheHoldNeverSeesTheTip() {
    for (holds in listOf(1, 20)) {
      val old = InMemoryTextFile("""{"holds":$holds}""")
      val file = InMemoryTextFile()
      migrateReopenHint(old, Tips(file, enabled = true))
      assertNull(old.read())
      // Across a restart, with the old file gone.
      val restarted = Tips(file, enabled = true).also { migrateReopenHint(old, it) }
      assertFalse(restarted.offer(Tip.ReopenHold))
    }
  }

  @Test
  fun anInstallThatNeverHeldStillSeesTheTip() {
    for (old in listOf(InMemoryTextFile("""{"holds":0}"""), InMemoryTextFile("{}"), InMemoryTextFile("not json"))) {
      val tips = Tips(InMemoryTextFile(), enabled = true)
      migrateReopenHint(old, tips)
      assertNull(old.read())
      assertTrue(tips.offer(Tip.ReopenHold))
    }
  }

  @Test
  fun aNewInstallSeesTheTipAndOtherTipsAreLeft() {
    val file = InMemoryTextFile()
    val tips = Tips(file, enabled = true).apply { used(Tip.TabList) }
    migrateReopenHint(InMemoryTextFile(), tips)
    assertTrue(tips.pending(Tip.ReopenHold))
    assertTrue(tips.pending(Tip.GridGestures))
    assertFalse(Tips(file, enabled = true).pending(Tip.TabList))
  }
}
