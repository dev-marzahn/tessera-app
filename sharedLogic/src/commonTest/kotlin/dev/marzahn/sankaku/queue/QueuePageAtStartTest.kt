package dev.marzahn.sankaku.queue

import dev.marzahn.sankaku.queue.QueueKind.Feed
import dev.marzahn.sankaku.queue.QueueKind.KeptPost
import dev.marzahn.sankaku.queue.QueueKind.Other
import dev.marzahn.sankaku.queue.QueueKind.Source
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #231: the queue page opens at a start only when the queue holds more than the current item. */
class QueuePageAtStartTest {
  @Test
  fun oneSourceAloneOpensOnItsGrid() {
    assertFalse(QueuePageAtStart.opens(listOf(Source)))
  }

  @Test
  fun feedItemsAndOtherScreensDontCount() {
    assertFalse(QueuePageAtStart.opens(listOf(Source, Feed)))
    assertFalse(QueuePageAtStart.opens(listOf(Source, Feed, Feed)))
    assertFalse(QueuePageAtStart.opens(listOf(Source, Other)))
    assertFalse(QueuePageAtStart.opens(listOf(Feed)))
  }

  @Test
  fun aSecondSourceOpensTheQueuePage() {
    assertTrue(QueuePageAtStart.opens(listOf(Source, Source)))
    assertTrue(QueuePageAtStart.opens(listOf(Source, Source, Feed)))
  }

  @Test
  fun oneKeptPostOpensTheQueuePage() {
    assertTrue(QueuePageAtStart.opens(listOf(Source, KeptPost)))
    assertTrue(QueuePageAtStart.opens(listOf(KeptPost)))
    assertTrue(QueuePageAtStart.opens(listOf(KeptPost, Feed)))
  }
}
