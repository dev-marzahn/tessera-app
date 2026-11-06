package dev.marzahn.sankaku.auth

import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import dev.marzahn.sankaku.api.dto.UserSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * The OS keychain, in three entries because Windows caps one at 2,560 bytes. [save] writes `refresh`, `access`, then
 * `meta`; every partial state an interrupted save leaves still works, so [load] needs no consistency check.
 */
class KeyringTokenStore(private val keyring: Keyring) : TokenStore {
  @Serializable
  private data class Meta(val accessExpiresAt: Long, val refreshExpiresAt: Long, val user: UserSummary)

  override suspend fun load(): StoredTokens? = withContext(Dispatchers.IO) {
    val refresh = read(REFRESH) ?: return@withContext null
    val access = read(ACCESS) ?: return@withContext null
    val meta = read(META)?.let { runCatching { Json.decodeFromString<Meta>(it) }.getOrNull() } ?: return@withContext null
    StoredTokens(
      accessToken = access,
      refreshToken = refresh,
      accessExpiresAt = Instant.fromEpochMilliseconds(meta.accessExpiresAt),
      refreshExpiresAt = Instant.fromEpochMilliseconds(meta.refreshExpiresAt),
      user = meta.user,
    )
  }

  override suspend fun save(tokens: StoredTokens) = withContext(Dispatchers.IO) {
    val meta = Meta(tokens.accessExpiresAt.toEpochMilliseconds(), tokens.refreshExpiresAt.toEpochMilliseconds(), tokens.user)
    keyring.setPassword(SERVICE, REFRESH, tokens.refreshToken)
    keyring.setPassword(SERVICE, ACCESS, tokens.accessToken)
    keyring.setPassword(SERVICE, META, Json.encodeToString(meta))
  }

  override suspend fun clear() = withContext(Dispatchers.IO) {
    for (account in listOf(META, ACCESS, REFRESH)) {
      try {
        keyring.deletePassword(SERVICE, account)
      } catch (_: PasswordAccessException) {
        // Already absent.
      }
    }
  }

  private fun read(account: String): String? = try {
    keyring.getPassword(SERVICE, account)
  } catch (_: PasswordAccessException) {
    null
  }

  private companion object {
    /** `dev.marzahn.sankaku` before the rename to Tessera; tokens there aren't read, so the user signs in once more. */
    const val SERVICE = "dev.marzahn.tessera"
    const val REFRESH = "refresh"
    const val ACCESS = "access"
    const val META = "meta"
  }
}
