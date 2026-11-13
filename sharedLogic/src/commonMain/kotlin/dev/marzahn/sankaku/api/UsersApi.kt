package dev.marzahn.sankaku.api

import dev.marzahn.sankaku.api.dto.UserResponse
import dev.marzahn.sankaku.api.dto.UserSummary
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod

/** The app doesn't call this; `:cli` does. */
class UsersApi(private val client: HttpClient) {
  suspend fun me(): UserSummary =
    client.apiRequest<UserResponse>(HttpMethod.Get, API_BASE, "/users/me", HeaderSet.NONE).user
}
