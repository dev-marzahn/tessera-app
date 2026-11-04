package dev.marzahn.sankaku.auth

/**
 * Where the platform keeps tokens: the desktop keychain, an encrypted file on Android (`TinkTokenStore`), or memory.
 * `InMemory` means the user signs in on every launch.
 */
enum class TokenStorageStatus { Keychain, Encrypted, InMemory }
