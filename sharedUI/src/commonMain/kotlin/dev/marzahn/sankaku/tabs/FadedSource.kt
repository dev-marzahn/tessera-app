package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.GridPlace
import dev.marzahn.sankaku.queue.Fading
import dev.marzahn.sankaku.queue.SessionId
import dev.marzahn.sankaku.queue.SourceKey
import kotlin.time.Instant

/**
 * A source that faded (#215, Glossary → Faded source), with the unseen kept posts that faded with it, as one block.
 * It is held as data: it has no view models and loads nothing, and it's in no queue order, so the queue's stepping,
 * swipes and counts never meet it. [TabsController.restore] puts it back, with its place.
 *
 * [fadedAt] is when it became due, `touched + 3 days` ([Fading.fadesAt]), not when the app noticed; it is deleted
 * [Fading.KEEP_FADED] after that. [offered] says Up next showed its last chance already (once).
 */
class FadedSource internal constructor(
  /** The source first, then its kept posts, each with its block set to the source's id. */
  internal val members: List<ClosedTab.Member>,
  val fadedAt: Instant,
  /** When the source was last touched, before it faded. */
  val touched: Instant,
  val offered: Boolean = false,
  /** The tag chips' names its title is made of ([TabsController.tagNames], #246). */
  private val tagNames: TagNameLookup = NoTagNames,
) {
  /** The source's id; stays its id while it is faded. */
  val id: TabId get() = members.first().id!!

  private val source: ClosedTab.Member get() = members.first()

  /** The source's grid. */
  val route: Route? get() = source.routes.lastOrNull()

  val title: String get() = tabTitle(route, names = tagNames)

  /** Where the source's grid was scrolled to; it opens there again. */
  val place: GridPlace? get() = source.place

  /** How many kept posts faded with it. */
  val keptCount: Int get() = members.size - 1

  /** Its search's key, or null for a search with none (Browse, Popular, Top). A search matching it restores it. */
  val sourceKey: SourceKey? get() = sourceKeyOf(route)

  /**
   * Whether it's R15 or R18, as the queue page counts it ([isAdult]): its grid (or Books search, #385) lets no G post
   * through. A source with no route is counted as adult, as a post of unknown rating is.
   */
  val adult: Boolean get() = route?.let { isAdult(it) { null } } ?: true

  /** Whether every word of the find [query] is part of its title or tags ([matchesFind]). */
  fun matches(query: String): Boolean {
    val words = findText(query).split(' ').filter { it.isNotEmpty() }
    val tags = sourceSearch(route).orEmpty().filterNot { it == BOOKS_SEARCH }
    val text = (listOf(title) + tags).joinToString(" ", transform = ::findText)
    return words.all { it in text }
  }

  /** When it is deleted. */
  val deletesAt: Instant get() = Fading.deletesAt(fadedAt)

  /** "Deleted in 30 days" as of [now]. */
  fun deletedIn(now: Instant): String = Fading.deletedIn(fadedAt, now)

  /** Whether Up next may offer it now: in its last week and not offered before ([TabsController.lastChance]). */
  fun isLastChance(now: Instant): Boolean = !offered && Fading.isLastChance(fadedAt, now)

  internal fun asOffered() = FadedSource(members, fadedAt, touched, offered = true, tagNames)

  internal fun saved(): SavedFaded = SavedFaded(
    at = fadedAt.epochSeconds,
    offered = offered.takeIf { it },
    tabs = members.map { member ->
      SavedTab(
        id = member.id!!.value,
        routes = member.routes,
        block = member.block?.value,
        place = member.place,
        kind = queueKind(member.routes.lastOrNull()),
        touched = touched.epochSeconds,
        addedIn = member.addedIn.value,
        keptAt = member.keptAt?.epochSeconds,
        title = member.title,
        gridTouched = true.takeIf { member.gridTouched },
        // Optional and left out when empty, as on an open tab (#232), so Delete now's entry still has it (#250).
        mosaic = member.mosaic?.thumbs?.takeIf { it.isNotEmpty() },
        posts = member.mosaic?.posts,
        refines = member.refines,
      )
    },
  )

  internal companion object {
    /** A saved block back, or null if it holds nothing usable. The source is the first tab. */
    fun of(saved: SavedFaded, tagNames: TagNameLookup = NoTagNames): FadedSource? {
      val tabs = saved.tabs.filter { it.routes.isNotEmpty() }.distinctBy { it.id }
      val head = tabs.firstOrNull() ?: return null
      val fadedAt = Instant.fromEpochSeconds(saved.at)
      val members = tabs.mapIndexed { i, tab ->
        ClosedTab.Member(
          routes = tab.routes,
          index = i,
          id = TabId(tab.id),
          block = if (i == 0) null else TabId(head.id),
          addedIn = tab.addedIn?.let(::SessionId) ?: SessionId.EARLIER,
          place = tab.place,
          keptAt = tab.keptAt?.let(Instant::fromEpochSeconds),
          title = tab.title,
          gridTouched = tab.gridTouched == true,
          mosaic = QueueMosaic.restored(tab.mosaic, tab.posts),
          refines = tab.refines,
        )
      }
      val touched = head.touched?.let(Instant::fromEpochSeconds) ?: (fadedAt - Fading.FADE_AFTER)
      return FadedSource(members, fadedAt, touched, saved.offered == true, tagNames)
    }
  }
}
