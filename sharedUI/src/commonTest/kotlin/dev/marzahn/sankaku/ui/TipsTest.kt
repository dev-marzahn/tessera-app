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
    val tips = Tips(file, touch = true)
    assertTrue(tips.offer(Tip.GridGestures))
    assertEquals(Tip.GridGestures, tips.current)
    tips.appeared(Tip.GridGestures)
    tips.dismissed(Tip.GridGestures)
    assertNull(tips.current)
    assertFalse(tips.offer(Tip.GridGestures))

    val restarted = Tips(file, touch = true)
    assertFalse(restarted.offer(Tip.GridGestures))
    assertTrue(restarted.offer(Tip.TabList))
  }

  @Test
  fun theReopenTipShowsOnceAndNotAfterARestart() {
    val file = InMemoryTextFile()
    val tips = Tips(file, touch = true)
    assertTrue(tips.offer(Tip.ReopenHold))
    tips.appeared(Tip.ReopenHold)
    // Got it, a tap elsewhere or back.
    tips.dismissed(Tip.ReopenHold)
    assertNull(tips.current)
    assertFalse(tips.offer(Tip.ReopenHold))
    assertFalse(Tips(file, touch = true).offer(Tip.ReopenHold))
  }

  @Test
  fun aTipCutShortWhileOnScreenCountsAsSeen() {
    val file = InMemoryTextFile()
    val tips = Tips(file, touch = true)
    tips.offer(Tip.TabList)
    tips.appeared(Tip.TabList)
    // The app closed with the tip on screen.
    assertFalse(Tips(file, touch = true).pending(Tip.TabList))
  }

  @Test
  fun aTipWhoseAnchorWentBeforeItShowedComesLaterAndFreesItsPlace() {
    val file = InMemoryTextFile()
    val tips = Tips(file, touch = true)
    assertTrue(tips.offer(Tip.GridGestures))
    // Its cell scrolled away, or the viewer reopened, during the wait.
    tips.dismissed(Tip.GridGestures)
    assertNull(tips.current)
    assertTrue(tips.pending(Tip.GridGestures))
    assertTrue(Tips(file, touch = true).pending(Tip.GridGestures))
    // The other tip isn't held up meanwhile.
    assertTrue(tips.offer(Tip.TabList))
  }

  @Test
  fun aTipWhoseActionWasUsedNeverShows() {
    val file = InMemoryTextFile()
    val tips = Tips(file, touch = true)
    tips.used(Tip.GridGestures)
    tips.used(Tip.TabList)
    assertFalse(tips.offer(Tip.GridGestures))
    assertFalse(tips.offer(Tip.TabList))
    assertNull(tips.current)
    assertFalse(Tips(file, touch = true).pending(Tip.GridGestures))
  }

  @Test
  fun usingOneTipsActionLeavesTheOther() {
    val tips = Tips(InMemoryTextFile(), touch = true)
    tips.used(Tip.GridGestures)
    assertTrue(tips.offer(Tip.TabList))
  }

  @Test
  fun oneTipAtATime() {
    val tips = Tips(InMemoryTextFile(), touch = true)
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
  fun aTipThatLeftTheScreenHoldsTheNextUntilTheNextGestureEnds() {
    // #538: "Tap the count…" came at once after Got it on the swipe tip, and took the first swipe to dismiss itself.
    val tips = Tips(InMemoryTextFile(), touch = true)
    assertTrue(tips.offer(Tip.QueueSwipe))
    tips.appeared(Tip.QueueSwipe)
    tips.dismissed(Tip.QueueSwipe)
    assertTrue(tips.resting)
    // The swipe after Got it went through: the next tip may come.
    tips.gestureEnded()
    assertFalse(tips.resting)
  }

  @Test
  fun aTipThatNeverShowedHoldsNothingUp() {
    val tips = Tips(InMemoryTextFile(), touch = true)
    assertTrue(tips.offer(Tip.QueueSwipe))
    // Its anchor went during the wait: nothing was on screen to take a touch.
    tips.dismissed(Tip.QueueSwipe)
    assertFalse(tips.resting)
  }

  @Test
  fun aTipOfferedOnAnActionDoesntWaitForTheRest() {
    // #538 review: the keep tip is offered once, as the viewer closes; the close was the user's action.
    val tips = Tips(InMemoryTextFile(), touch = true)
    tips.offer(Tip.TabList)
    tips.appeared(Tip.TabList)
    tips.dismissed(Tip.TabList)
    assertTrue(tips.offer(Tip.GridGestures))
  }

  @Test
  fun aSecondTabAppearsOnlyOnARiseFromOne() {
    val tips = Tips(InMemoryTextFile(), touch = true)
    // Two tabs restored at launch: no tip at launch.
    tips.counted(2)
    assertFalse(tips.secondTabAppeared)
    tips.counted(1)
    assertFalse(tips.secondTabAppeared)
    tips.counted(2)
    assertTrue(tips.secondTabAppeared)
  }

  @Test
  fun eachPlatformShowsOnlyItsOwnTips() {
    // Desktop (#504): the keep loop's tip, and none of the phone's.
    val desktop = Tips(InMemoryTextFile(), touch = false)
    assertFalse(desktop.offer(Tip.GridGestures))
    assertFalse(desktop.offer(Tip.TabList))
    assertFalse(desktop.offer(Tip.ReopenHold))
    assertTrue(desktop.offer(Tip.KeepLoop))

    val phone = Tips(InMemoryTextFile(), touch = true)
    assertFalse(phone.offer(Tip.KeepLoop))
    assertTrue(phone.offer(Tip.GridGestures))
  }

  @Test
  fun keepingBeforeTheKeepLoopTipCameMeansItNeverShows() {
    val file = InMemoryTextFile()
    Tips(file, touch = false).used(Tip.KeepLoop)
    assertFalse(Tips(file, touch = false).offer(Tip.KeepLoop))
  }

  @Test
  fun theLongPressTipComesOnceAfterTheFirstHeartThatFavourites() {
    val file = InMemoryTextFile()
    val tips = Tips(file, touch = true)
    // An unfavourite, or a ♡ that opened sign-in, isn't the moment.
    assertFalse(tips.offerLongPressTip(wasFavourited = true, ran = true))
    assertFalse(tips.offerLongPressTip(wasFavourited = false, ran = false))
    assertNull(tips.current)

    assertTrue(tips.offerLongPressTip(wasFavourited = false, ran = true))
    assertEquals(Tip.LongPressFavourite, tips.current)
    tips.appeared(Tip.LongPressFavourite)
    tips.dismissed(Tip.LongPressFavourite)

    assertFalse(tips.offerLongPressTip(wasFavourited = false, ran = true))
    assertFalse(Tips(file, touch = true).offerLongPressTip(wasFavourited = false, ran = true))
  }

  @Test
  fun theLongPressTipNeverComesOnceTheUserHasLongPressed() {
    val file = InMemoryTextFile()
    Tips(file, touch = true).used(Tip.LongPressFavourite)
    val tips = Tips(file, touch = true)
    assertFalse(tips.offerLongPressTip(wasFavourited = false, ran = true))
    assertNull(tips.current)
  }

  @Test
  fun theLongPressTipIsThePhonesOnly() {
    assertFalse(Tips(InMemoryTextFile(), touch = false).offerLongPressTip(wasFavourited = false, ran = true))
  }

  @Test
  fun anUnreadableFileStartsFresh() {
    assertTrue(Tips(InMemoryTextFile("not json"), touch = true).offer(Tip.GridGestures))
  }
}
