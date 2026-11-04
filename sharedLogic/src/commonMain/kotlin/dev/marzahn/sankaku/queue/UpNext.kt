package dev.marzahn.sankaku.queue

import kotlin.time.Instant

/**
 * What the Up next card offers when the user moves past the last source or kept post (spec: Tabs → Queue → Up next,
 * #216, #230), in order. Only the order: which faded sources exist and what answering does are the queue's
 * (`TabsController`, `UpNextCard`).
 *
 * Up next offers only what is out of view, so it never loops back to an item still in the queue:
 *
 * 1. A **Last chance** for a faded source within its last week ([Fading.LAST_CHANCE]): "Last chance: artist_x · Keep /
 *    Let go". It outranks everything and is offered once.
 * 2. The other **faded sources**, the one that faded first first: "Pick up artist_x again?".
 *
 * The feeds come after these, as queue items rather than offers (#229): they are the queue's last items. Nothing here
 * depends on sessions.
 */
object UpNext {
  /** A faded source Up next may offer: its [id] and when it [fadedAt]. */
  class Faded(val id: String, val fadedAt: Instant)

  /** One thing the card shows. */
  sealed interface Offer {
    /** The id of the faded source it is about. */
    val id: String

    /** A faded source in its last week: Keep or Let go. */
    data class LastChance(override val id: String) : Offer

    /** A faded source to pick up again: Open or Skip. */
    data class Pickup(override val id: String) : Offer
  }

  /**
   * The offers in the order the card shows them. [lastChance] is the faded source to offer as a Last chance, or null;
   * [faded] are every faded source to consider, in any order. [passed] are the ids of the offers already shown and
   * answered or skipped. A source offered as the Last chance isn't offered again as a pickup.
   */
  fun offers(lastChance: String?, faded: List<Faded>, passed: Set<String> = emptySet()): List<Offer> = buildList {
    if (lastChance != null && lastChance !in passed) add(Offer.LastChance(lastChance))
    // sortedBy is stable: two sources that faded at the same moment keep the order given.
    faded
      .filter { it.id != lastChance && it.id !in passed }
      .sortedBy { it.fadedAt }
      .forEach { add(Offer.Pickup(it.id)) }
  }
}
