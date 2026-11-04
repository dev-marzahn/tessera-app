package dev.marzahn.sankaku.tabs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marzahn.sankaku.api.Feed
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.data.QueueSettings
import dev.marzahn.sankaku.queue.FeedItems
import org.koin.compose.koinInject

/**
 * The feed items (#229, [FeedItems.pinned]) for the sign-in and "Show For you in the queue" as they are now. With no
 * [session] (a test's Koin graph of the shell alone) it's signed out.
 */
internal fun pinnedFeeds(session: AuthSession?, settings: QueueSettings?): List<Feed> =
  FeedItems.pinned(session?.isSignedIn?.value == true, settings?.forYouInQueue?.value == true)

/**
 * Keeps the queue's feed items in step with the sign-in and the setting ([TabsController.pinFeeds]): signing in adds
 * Following, signing out takes it away. The controller is pinned once as it's made, so the first frame has them already.
 */
@Composable
internal fun QueueFeeds(
  tabs: TabsController = koinInject(),
  session: AuthSession = koinInject(),
  settings: QueueSettings = koinInject(),
) {
  val signedIn by session.isSignedIn.collectAsStateWithLifecycle()
  val forYou by settings.forYouInQueue.collectAsStateWithLifecycle()
  LaunchedEffect(signedIn, forYou) { tabs.pinFeeds(FeedItems.pinned(signedIn, forYou)) }
}
