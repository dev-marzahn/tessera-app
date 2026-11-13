package dev.marzahn.sankaku.api

const val LOGIN_BASE = "https://login.sankakucomplex.com"
const val API_BASE = "https://sankakuapi.com"
const val API_HOST = "sankakuapi.com"

/** Full files, and the sample of a post without a separate one. Its connects can stall (`docs/api.md` → Quirks). */
const val FILE_HOST = "s.sankakucomplex.com"

/** `s.` serves full files, `v.` (BunnyCDN) previews and samples. Their URLs are signed with an `e` expiry parameter. */
val IMAGE_HOSTS = setOf(FILE_HOST, "v.sankakucomplex.com")

const val CLIENT_ID = "sankaku-web-app"
const val CLIENT_URL = "https://www.sankakucomplex.com"

internal const val API_REQUEST_TIMEOUT_MS = 30_000L
