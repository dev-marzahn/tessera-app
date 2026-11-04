package dev.marzahn.sankaku.auth

import dev.marzahn.sankaku.api.dto.UserSummary

sealed interface AuthState {
  data object Restoring : AuthState

  data class SignedOut(val reason: Reason) : AuthState {
    enum class Reason { None, WrongCredentials, Expired, TwoFactorUnsupported }
  }

  data class SignedIn(val user: UserSummary) : AuthState
}
