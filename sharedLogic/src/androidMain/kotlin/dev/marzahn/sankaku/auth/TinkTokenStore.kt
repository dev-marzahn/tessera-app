package dev.marzahn.sankaku.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dev.marzahn.sankaku.api.dto.UserSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/** The key [TinkTokenStore] encrypts with. [delete] throws it away, so the next [aead] makes a new one. */
interface TokenKey {
  /** Throws [GeneralSecurityException] or [IOException] when the key can't be read. */
  fun aead(): Aead

  fun delete()
}

/**
 * The tokens as one AEAD-encrypted DataStore value. A key failure (a Keystore key that's gone or unusable, or a value
 * the key can't decrypt) clears the value and the key, so [load] returns null and the user is signed out.
 *
 * `AuthSession` reads the tokens before every request, so they're decrypted once and then served from memory; this
 * store is the only writer of its DataStore.
 */
class TinkTokenStore(private val dataStore: DataStore<Preferences>, private val key: TokenKey) : TokenStore {
  @Serializable
  private data class Payload(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
    val user: UserSummary,
  )

  private val mutex = Mutex()
  private var loaded = false
  private var cached: StoredTokens? = null
  private var aead: Aead? = null

  override suspend fun load(): StoredTokens? = mutex.withLock {
    if (!loaded) {
      cached = withContext(Dispatchers.IO) { read() }
      loaded = true
    }
    cached
  }

  /** If the key or the file fails here, the tokens are kept in memory only: the session lasts until the app stops. */
  override suspend fun save(tokens: StoredTokens): Unit = mutex.withLock {
    cached = tokens
    loaded = true
    withContext(Dispatchers.IO) {
      val payload = Payload(
        tokens.accessToken,
        tokens.refreshToken,
        tokens.accessExpiresAt.toEpochMilliseconds(),
        tokens.refreshExpiresAt.toEpochMilliseconds(),
        tokens.user,
      )
      val sealed = orNullOnKeyFailure { aead().encrypt(Json.encodeToString(payload).encodeToByteArray(), ASSOCIATED_DATA) }
      if (sealed == null) {
        dropKeyAndValue()
      } else {
        try {
          dataStore.edit { it[TOKENS] = Base64.encode(sealed) }
        } catch (_: IOException) {
          // Kept in memory.
        }
      }
    }
  }

  /** If the value can't be removed, the key is: what's left can't be decrypted, so the next launch is signed out too. */
  override suspend fun clear(): Unit = mutex.withLock {
    cached = null
    loaded = true
    withContext(Dispatchers.IO) {
      try {
        dataStore.edit { it.remove(TOKENS) }
      } catch (_: IOException) {
        dropKeyAndValue()
      }
    }
  }

  private suspend fun read(): StoredTokens? {
    // A file that can't be read now (not a corrupt one, which DataStore replaces) signs out for this run only.
    val stored = try {
      dataStore.data.first()[TOKENS]
    } catch (_: IOException) {
      null
    } ?: return null
    val sealed = runCatching { Base64.decode(stored) }.getOrNull()
    val plain = sealed?.let { orNullOnKeyFailure { aead().decrypt(it, ASSOCIATED_DATA) } }
    val payload = plain?.let { runCatching { Json.decodeFromString<Payload>(it.decodeToString()) }.getOrNull() }
    if (payload == null) {
      dropKeyAndValue()
      return null
    }
    return StoredTokens(
      accessToken = payload.accessToken,
      refreshToken = payload.refreshToken,
      accessExpiresAt = Instant.fromEpochMilliseconds(payload.accessExpiresAt),
      refreshExpiresAt = Instant.fromEpochMilliseconds(payload.refreshExpiresAt),
      user = payload.user,
    )
  }

  private fun aead(): Aead = aead ?: key.aead().also { aead = it }

  private suspend fun dropKeyAndValue() {
    aead = null
    runCatching { key.delete() }
    try {
      dataStore.edit { it.remove(TOKENS) }
    } catch (_: IOException) {
      // Unreadable without the key anyway.
    }
  }

  /** [block]'s result, or null when the key fails: it can't be read, or a value can't be decrypted with it. */
  private inline fun <T> orNullOnKeyFailure(block: () -> T): T? = try {
    block()
  } catch (_: GeneralSecurityException) {
    null
  } catch (_: IOException) {
    null
  }

  companion object {
    private val TOKENS = stringPreferencesKey("tokens")

    /** Binds the ciphertext to its use, so it can't be passed off as another value encrypted with the same key. */
    private val ASSOCIATED_DATA = "dev.marzahn.tessera/tokens".encodeToByteArray()

    /** The app's one instance: DataStore allows a single one per file. */
    fun create(context: Context): TinkTokenStore = TinkTokenStore(
      // A file DataStore can't parse holds no usable tokens: it starts empty, so the user is signed out.
      PreferenceDataStoreFactory.create(ReplaceFileCorruptionHandler { emptyPreferences() }) {
        context.preferencesDataStoreFile("tokens")
      },
      KeystoreTokenKey(context.applicationContext),
    )
  }
}

/** A Tink AES-256-GCM keyset in shared preferences, itself encrypted with a key in the Android Keystore. */
class KeystoreTokenKey(private val context: Context) : TokenKey {
  /** The Keystore reports some failures as [ProviderException]; they're passed on as key failures too. */
  override fun aead(): Aead = try {
    AeadConfig.register()
    AndroidKeysetManager.Builder()
      .withSharedPref(context, KEYSET_NAME, PREFS_FILE)
      .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
      .withMasterKeyUri("android-keystore://$MASTER_KEY_ALIAS")
      .build()
      .keysetHandle
      .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
  } catch (e: ProviderException) {
    throw GeneralSecurityException(e)
  }

  override fun delete() {
    context.deleteSharedPreferences(PREFS_FILE)
    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(MASTER_KEY_ALIAS)
  }

  private companion object {
    const val PREFS_FILE = "tessera_token_keyset"
    const val KEYSET_NAME = "keyset"
    const val MASTER_KEY_ALIAS = "tessera_token_key"
  }
}
