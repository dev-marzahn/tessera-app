package dev.marzahn.sankaku.queue

/**
 * Whether "Start on: Queue" opens the queue page at a start (#231; spec: Tabs → Queue → Start on). The page is worth a
 * tap only when the queue holds more than the current item: at least one kept post, or a second source. Feed items
 * (#229) don't count, nor does anything else ([QueueKind.Other]). Otherwise the app opens straight on the current
 * item, so a fresh install, or a queue that's only the default Browse grid, never opens on a list of one.
 */
object QueuePageAtStart {
  /** Whether a queue whose items are of [kinds] opens on the queue page. */
  fun opens(kinds: List<QueueKind>): Boolean =
    QueueKind.KeptPost in kinds || kinds.count { it == QueueKind.Source } > 1
}
