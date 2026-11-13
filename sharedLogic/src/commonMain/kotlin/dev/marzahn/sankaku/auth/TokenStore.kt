package dev.marzahn.sankaku.auth

import dev.marzahn.sankaku.api.dto.UserSummary
import kotlin.time.Instant

/** The password is never part of this. */
data class StoredTokens(
  val accessToken: String,
  val refreshToken: String,
  val accessExpiresAt: Instant,
  val refreshExpiresAt: Instant,
  val user: UserSummary,
)

interface TokenStore {
  suspend fun load(): StoredTokens?

  suspend fun save(tokens: StoredTokens)

  suspend fun clear()
}
