package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.data.StartOn
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * #177: a start (a launch, or a return after more than 5 minutes away) lands where Start on says. #264: the queue
 * page's blur lasts until the queue's first use in the process, which no start undoes.
 */
class QueueStartTest {
  private val time = TestTimeSource()
  private val tabs = TabsController().apply { open(Route.Grid(listOf("a")), background = false) }
  private val privacy = PrivacySettings(InMemoryTextFile())
  private val start = QueueStart(tabs, privacy, time)

  private fun titles() = tabs.tabs.value.map { it.title }

  @Test
  fun aLaunchOpensTheQueuePageAndChangesNothingInTheQueue() {
    start.launched()

    assertTrue(start.pageOpen)
    assertEquals(listOf("Browse", "A"), titles())
    assertEquals("A", tabs.activeTab.title)
  }

  @Test
  fun aLaunchOnBrowseShowsABrowseGridInFrontOfTheCurrentBlock() {
    privacy.setStartOn(StartOn.Browse)

    start.launched()

    assertFalse(start.pageOpen)
    assertEquals(listOf("Browse", "Browse", "A"), titles())
    assertEquals(1, tabs.tabs.value.indexOf(tabs.activeTab))
  }

  @Test
  fun aRecreatedActivityIsNotAStart() {
    start.launched()
    start.closePage()

    start.launched()

    assertFalse(start.pageOpen)
  }

  @Test
  fun comingBackAfterMoreThanFiveMinutesIsAStartButDoesNotBlurAgain() {
    start.launched()
    start.interact()
    start.closePage()

    start.left()
    time += 5.minutes + 1.seconds
    start.returned()

    // #264: the blur lasts until the queue's first use in this process, not in this start.
    assertTrue(start.pageOpen)
    assertTrue(start.interacted)
  }

  // #264: a launch is blurred until the queue is first used.
  @Test
  fun aLaunchIsNotAUse() {
    start.launched()
    start.shown(tabs.activeTab.id)

    assertFalse(start.interacted)
  }

  @Test
  fun aLaunchOnBrowseIsNotAUseEitherAndNorIsAReturnToBrowse() {
    privacy.setStartOn(StartOn.Browse)
    start.launched()
    start.shown(tabs.activeTab.id)

    start.left()
    time += 5.minutes + 1.seconds
    start.returned()
    // The new Browse grid the return showed.
    start.shown(tabs.activeTab.id)

    assertFalse(start.interacted)
  }

  @Test
  fun anotherItemShownIsAUse() {
    start.launched()
    start.closePage()
    tabs.previous()
    start.shown(tabs.activeTab.id)

    assertTrue(start.interacted)
  }

  @Test
  fun aBlurWaitingAtLaunchStillWaitsAfterAReturn() {
    start.launched()

    start.left()
    time += 5.minutes + 1.seconds
    start.returned()

    assertFalse(start.interacted)
  }

  // #231: the queue page opens only when the queue holds more than the current item.
  private fun launchedOn(controller: TabsController) = QueueStart(controller, privacy, time).apply { launched() }

  private fun route(route: Route) = Json.encodeToString(Route.serializer(), route)

  /** A `tabs.json` v1, as written before #174, holding [routes] (one list per tab) with the first tab active. */
  private fun v1(vararg routes: List<Route>): TabsController {
    val tabs = routes.mapIndexed { i, r -> """{"id":"t$i","routes":[${r.joinToString(",", transform = ::route)}]}""" }
    val file = InMemoryTextFile("""{"activeIndex":0,"tabs":[${tabs.joinToString(",")}]}""")
    return TabsController(FileTabStateStore(file))
  }

  @Test
  fun aFreshInstallOpensOnBrowse() {
    val fresh = TabsController()
    val start = launchedOn(fresh)

    assertFalse(start.pageOpen)
    assertEquals(listOf("Browse"), fresh.tabs.value.map { it.title })
    assertEquals(Route.Grid(emptyList()), fresh.activeTab.top)
  }

  @Test
  fun aMigratedV1FileWithOnlyTheBrowseGridOpensOnIt() {
    assertFalse(launchedOn(v1(listOf(Route.Grid(emptyList())))).pageOpen)
    // A feed item doesn't count (#229), and a grid's back stack is still one source.
    assertFalse(launchedOn(v1(listOf(Route.Grid(emptyList())), listOf(Route.Grid(listOf("feed:following"))))).pageOpen)
    assertFalse(launchedOn(v1(listOf(Route.Grid(emptyList()), Route.Grid(listOf("a"))))).pageOpen)
  }

  @Test
  fun aMigratedV1FileWithAKeptPostOrTwoSourcesOpensTheQueuePage() {
    val post = Route.Post("p1", "m1", listOf("a"))
    assertTrue(launchedOn(v1(listOf(Route.Grid(listOf("a"))), listOf(post))).pageOpen)
    // A post kept over its grid becomes the grid and a kept post (#181).
    assertTrue(launchedOn(v1(listOf(Route.Grid(listOf("a")), post))).pageOpen)
    assertTrue(launchedOn(v1(listOf(Route.Grid(emptyList())), listOf(Route.Grid(listOf("a"))))).pageOpen)
  }

  @Test
  fun oneKeptPostOpensTheQueuePage() {
    val queue = TabsController().apply { open(Route.Post("p1", "m1", emptyList()), background = true) }

    assertTrue(launchedOn(queue).pageOpen)
  }

  @Test
  fun aReturnWithOnlyTheGridLeftStaysOnIt() {
    start.launched()
    assertTrue(start.pageOpen)
    tabs.close(tabs.tabs.value.first { it.title == "A" }.id)

    start.left()
    time += 5.minutes + 1.seconds
    start.returned()

    assertFalse(start.pageOpen)
  }

  @Test
  fun comingBackWithinFiveMinutesIsNot() {
    start.launched()
    start.closePage()

    start.left()
    time += 5.minutes
    start.returned()

    assertFalse(start.pageOpen)
  }
}
