package dev.marzahn.sankaku.auth

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.marzahn.sankaku.api.dto.UserSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * [TinkTokenStore] with the real Android Keystore key, run on a device or emulator with
 * `./gradlew :sharedLogic:connectedAndroidDeviceTest`. Each "restart" is a new DataStore, key and store over the same
 * files, as a new process would have.
 */
@RunWith(AndroidJUnit4::class)
class TinkTokenStoreKeystoreTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val file = File(context.filesDir, "keystore-test.preferences_pb")
  private val scopes = mutableListOf<CoroutineScope>()

  private val tokens = StoredTokens(
    accessToken = "access-secret",
    refreshToken = "refresh-secret",
    accessExpiresAt = Instant.fromEpochMilliseconds(1_000),
    refreshExpiresAt = Instant.fromEpochMilliseconds(2_000),
    user = UserSummary(id = "42", name = "tester"),
  )

  private fun restart(): Pair<TinkTokenStore, () -> String?> {
    closeAll()
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    scopes += scope
    val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
    return TinkTokenStore(dataStore, KeystoreTokenKey(context)) to { runBlocking { dataStore.data.first()[TOKENS] } }
  }

  @Before
  @After
  fun cleanUp() {
    closeAll()
    file.delete()
    KeystoreTokenKey(context).delete()
  }

  /**
   * Cancels every DataStore's scope and waits for it to finish: DataStore releases its file only when the scope's job
   * completes, not when it's cancelled, so a new instance made before that fails with "multiple DataStores active".
   */
  private fun closeAll() = runBlocking {
    scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
  }

  @Test
  fun tokensSurviveARestart() = runBlocking {
    restart().first.save(tokens)

    assertEquals(tokens, restart().first.load())
  }

  @Test
  fun aLostKeystoreKeySignsOutAndTheNextSignInWorks() = runBlocking {
    restart().first.save(tokens)
    // As after the lock screen or the device changed: the keyset in shared preferences stays, its Keystore key is gone.
    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("tessera_token_key")

    val (store, stored) = restart()
    assertNull(store.load())
    assertNull(stored())

    store.save(tokens)
    assertEquals(tokens, restart().first.load())
  }

  private companion object {
    val TOKENS = stringPreferencesKey("tokens")
  }
}
