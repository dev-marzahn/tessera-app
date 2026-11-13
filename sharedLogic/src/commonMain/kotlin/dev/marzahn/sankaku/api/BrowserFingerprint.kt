package dev.marzahn.sankaku.api

import io.ktor.http.Url
import io.ktor.util.AttributeKey

/**
 * The Chrome major version both platforms claim, in the User-Agent and the client hints. Bumped to the current stable
 * Chrome on each release (see README → Release checklist).
 */
const val CHROME_MAJOR_VERSION = 158

/** The platform whose Chrome the client claims to be (#204, decision 3 in #196; the phone's changed in #475). */
enum class BrowserPlatform(
  /** The platform part of Chrome's reduced User-Agent, the same on every device of the platform. */
  internal val userAgentPlatform: String,
  internal val mobile: Boolean,
  /** `Sec-CH-UA-Platform`'s value. */
  internal val clientHintPlatform: String,
) {
  /**
   * Chrome on an Android phone. The phone claimed it until #475, but Sankaku leaves pages out of books (and maybe
   * more) for any User-Agent with "Android" in it; it's now only for requests about the numeric ids it gets.
   */
  Android("Linux; Android 10; K", mobile = true, clientHintPlatform = "Android"),
  Mac("Macintosh; Intel Mac OS X 10_15_7", mobile = false, clientHintPlatform = "macOS"),

  /** Chrome on an Android phone with "Desktop site" on, which the phone claims (#475): ids are obfuscated as on desktop. */
  AndroidDesktopSite("X11; Linux x86_64", mobile = false, clientHintPlatform = "Linux"),
  ;

  /**
   * The platform to claim for a request about a numeric id ([aboutNumericId]), which only an Android User-Agent finds:
   * the phone kept such ids (queue items, seen marks, reading places) from builds before #475. Null to claim this one.
   */
  val forNumericIds: BrowserPlatform? get() = if (this == AndroidDesktopSite) Android else null
}

private val NUMERIC = Regex("[0-9]+")

// A search token naming a post or a book by a numeric id: `id:12345678`, `pool:4012`, `parent:…`, `recommended_for_post:…`.
private val NUMERIC_TOKEN = Regex("(?:^|\\s)(?:id|pool|parent|recommended_for_post):[0-9]+(?:\\s|$)")

/**
 * Whether [url] asks Sankaku's API about a post or a book by a numeric id: in its path (`/posts/{id}`, `/post/{id}`,
 * `/pools/{id}` and what's under them) or in its `tags` (`id:`, `pool:`, `parent:`, `recommended_for_post:`).
 */
internal fun aboutNumericId(url: Url): Boolean {
  if (url.host != API_HOST) return false
  val segments = url.segments
  val inPath = segments.zipWithNext().any { (resource, id) -> resource in ID_RESOURCES && NUMERIC.matches(id) }
  return inPath || url.parameters.getAll("tags").orEmpty().any { NUMERIC_TOKEN.containsMatchIn(it) }
}

private val ID_RESOURCES = setOf("posts", "post", "pools")

/**
 * The id a request is about when it's in neither its path nor its `tags` (a series' id in GraphQL's body, a book's in
 * `pool_ids`), set by
 * `apiRequest(aboutId = …)`, so a numeric one claims [BrowserPlatform.forNumericIds] too.
 */
internal val AboutId = AttributeKey<String>("AboutId")

/** Whether a request about [url], and about [aboutId] if set, names a numeric id ([aboutNumericId]). */
internal fun aboutNumericId(url: Url, aboutId: String?): Boolean =
  aboutNumericId(url) || aboutId != null && NUMERIC.matches(aboutId)

/** The platform this build runs on: Android's desktop site on Android, Mac on desktop (the only desktop release). */
internal expect val currentBrowserPlatform: BrowserPlatform

/**
 * What Chrome [majorVersion] on [platform] sends on every request: its reduced User-Agent and its low-entropy client
 * hints. Since Chrome's User-Agent reduction every Chrome on a platform sends these same strings, so the client blends
 * in with them (a random string per install would stand out).
 */
class BrowserFingerprint(val platform: BrowserPlatform, val majorVersion: Int = CHROME_MAJOR_VERSION) {
  /** What a request about a numeric id claims instead ([BrowserPlatform.forNumericIds]), if anything. */
  internal val forNumericIds: BrowserFingerprint? by lazy { platform.forNumericIds?.let { BrowserFingerprint(it, majorVersion) } }

  val userAgent: String =
    "Mozilla/5.0 (${platform.userAgentPlatform}) AppleWebKit/537.36 (KHTML, like Gecko) " +
      "Chrome/$majorVersion.0.0.0 ${if (platform.mobile) "Mobile " else ""}Safari/537.36"

  /** `Sec-CH-UA`, `Sec-CH-UA-Mobile` and `Sec-CH-UA-Platform`, named in lowercase as Chrome sends them. */
  val clientHints: Map<String, String> = mapOf(
    "sec-ch-ua" to brandList(majorVersion),
    "sec-ch-ua-mobile" to if (platform.mobile) "?1" else "?0",
    "sec-ch-ua-platform" to "\"${platform.clientHintPlatform}\"",
  )

  companion object {
    private val greaseChars = listOf(" ", "(", ":", "-", ".", "/", ")", ";", "=", "?", "_")
    private val greaseVersions = listOf("8", "99", "24")
    private val orders = listOf(
      listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0),
    )

    /**
     * Chrome's `Sec-CH-UA` brand list, as Chromium builds it from the major version
     * (`components/embedder_support/user_agent_utils.cc`: `GenerateBrandVersionList`,
     * `GetGreasedUserAgentBrandVersion`, `ShuffleBrandList`): a GREASE brand, "Chromium" and "Google Chrome", in an
     * order and with GREASE characters seeded by the version.
     */
    internal fun brandList(majorVersion: Int): String {
      val seed = majorVersion
      val grease = "Not${greaseChars[seed % greaseChars.size]}A${greaseChars[(seed + 1) % greaseChars.size]}Brand"
      val brands = listOf(
        grease to greaseVersions[seed % greaseVersions.size],
        "Chromium" to "$majorVersion",
        "Google Chrome" to "$majorVersion",
      )
      val order = orders[seed % orders.size]
      val shuffled = arrayOfNulls<Pair<String, String>>(brands.size)
      brands.forEachIndexed { i, brand -> shuffled[order[i]] = brand }
      return shuffled.filterNotNull().joinToString(", ") { (name, version) -> "\"$name\";v=\"$version\"" }
    }

    /** This build's: [currentBrowserPlatform] at [CHROME_MAJOR_VERSION]. Last, as it needs the tables above. */
    val Current: BrowserFingerprint = BrowserFingerprint(currentBrowserPlatform)
  }
}
