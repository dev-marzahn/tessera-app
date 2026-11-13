package dev.marzahn.sankaku.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import dev.marzahn.sankaku.TEST_USER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** A software Tink key stands in for the Keystore one, which a host JVM doesn't have. */
class TinkTokenStoreTest {
  private class FakeKey(var failing: Boolean = false) : TokenKey {
    private var handle: KeysetHandle? = null
    var deletions = 0

    override fun aead(): Aead {
      if (failing) throw GeneralSecurityException("Keystore key unusable")
      AeadConfig.register()
      val keyset = handle ?: KeysetHandle.newBuilder()
        .addEntry(KeysetHandle.generateEntryFromParameters(PredefinedAeadParameters.AES256_GCM).withRandomId().makePrimary())
        .build().also { handle = it }
      return keyset.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override fun delete() {
      handle = null
      deletions++
    }
  }

  private val dir = createTempDirectory("tink-token-store").toFile()
  private val file = File(dir, "tokens.preferences_pb")
  private val scopes = mutableListOf<CoroutineScope>()

  private val tokens = StoredTokens(
    accessToken = "access-secret",
    refreshToken = "refresh-secret",
    accessExpiresAt = Instant.fromEpochMilliseconds(1_000),
    refreshExpiresAt = Instant.fromEpochMilliseconds(2_000),
    user = TEST_USER,
  )

  /** A new DataStore over the same file, as after a restart. DataStore allows one active instance per file. */
  private suspend fun dataStore(): DataStore<Preferences> {
    closeAll()
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    scopes += scope
    return PreferenceDataStoreFactory.create(scope = scope) { file }
  }

  @AfterTest
  fun cleanUp() {
    runBlocking { closeAll() }
    dir.deleteRecursively()
  }

  /**
   * Cancels every DataStore's scope and waits for it to finish: DataStore releases its file only when the scope's job
   * completes, not when it's cancelled, so a new instance made before that fails with "multiple DataStores active".
   */
  private suspend fun closeAll() {
    scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
  }

  @Test
  fun savedTokensSurviveARestart() = runBlocking {
    val key = FakeKey()
    TinkTokenStore(dataStore(), key).save(tokens)

    assertEquals(tokens, TinkTokenStore(dataStore(), key).load())
  }

  @Test
  fun theFileHoldsNoTokenInTheClear() = runBlocking {
    TinkTokenStore(dataStore(), FakeKey()).save(tokens)

    val text = file.readBytes().decodeToString()
    assertFalse(text.contains("access-secret"))
    assertFalse(text.contains("refresh-secret"))
    assertFalse(text.contains(TEST_USER.name))
  }

  @Test
  fun clearSurvivesARestart() = runBlocking {
    val key = FakeKey()
    val store = TinkTokenStore(dataStore(), key)
    store.save(tokens)
    store.clear()

    assertNull(store.load())
    assertNull(TinkTokenStore(dataStore(), key).load())
  }

  @Test
  fun aKeyThatCantBeReadClearsTheTokens() = runBlocking {
    val key = FakeKey()
    TinkTokenStore(dataStore(), key).save(tokens)
    key.failing = true

    val store = dataStore()
    assertNull(TinkTokenStore(store, key).load())
    assertEquals(1, key.deletions)
    assertNull(store.data.first()[TOKENS])
  }

  @Test
  fun aValueTheKeyCantDecryptClearsItAndTheKey() = runBlocking {
    TinkTokenStore(dataStore(), FakeKey()).save(tokens)
    // Another key, as after the Keystore key was replaced.
    val other = FakeKey()

    val store = dataStore()
    assertNull(TinkTokenStore(store, other).load())
    assertEquals(1, other.deletions)
    assertNull(store.data.first()[TOKENS])

    // A sign-in after that saves under a new key again.
    TinkTokenStore(store, other).save(tokens)
    assertEquals(tokens, TinkTokenStore(dataStore(), other).load())
  }

  @Test
  fun aValueThatIsNotBase64ClearsIt() = runBlocking {
    val store = dataStore()
    store.edit { it[TOKENS] = "not base64 !" }

    assertNull(TinkTokenStore(store, FakeKey()).load())
    assertNull(store.data.first()[TOKENS])
  }

  @Test
  fun aKeyFailureOnSaveKeepsTheSessionForThisRunOnly() = runBlocking {
    val key = FakeKey(failing = true)
    val store = TinkTokenStore(dataStore(), key)
    store.save(tokens)

    assertEquals(tokens, store.load())
    key.failing = false
    assertNull(TinkTokenStore(dataStore(), key).load())
  }

  /** Reads from [store]; every write fails, as on a full disk. */
  private class UnwritableDataStore(private val store: DataStore<Preferences>) : DataStore<Preferences> {
    override val data = store.data

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
      throw IOException("disk full")
  }

  @Test
  fun aSignOutWhoseWriteFailsStillSignsOutAfterARestart() = runBlocking {
    val key = FakeKey()
    TinkTokenStore(dataStore(), key).save(tokens)
    val store = TinkTokenStore(UnwritableDataStore(dataStore()), key)
    assertEquals(tokens, store.load())

    store.clear()

    assertNull(store.load())
    assertNull(TinkTokenStore(dataStore(), key).load())
  }

  @Test
  fun loadReadsTheFileOnce() = runBlocking {
    val key = FakeKey()
    TinkTokenStore(dataStore(), key).save(tokens)
    val store = TinkTokenStore(dataStore(), key)
    assertEquals(tokens, store.load())

    file.delete()
    assertEquals(tokens, store.load())
    assertTrue(key.deletions == 0)
  }

  private companion object {
    val TOKENS = stringPreferencesKey("tokens")
  }
}
