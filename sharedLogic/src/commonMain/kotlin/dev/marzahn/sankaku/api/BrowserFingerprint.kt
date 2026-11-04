package dev.marzahn.sankaku.api

/**
 * The Chrome major version both platforms claim, in the User-Agent and the client hints. Bumped to the current stable
 * Chrome on each release (see README → Release checklist).
 */
const val CHROME_MAJOR_VERSION = 157

/** The platform whose Chrome the client claims to be (#204, decision 3 in #196). */
enum class BrowserPlatform(
  /** The platform part of Chrome's reduced User-Agent, the same on every device of the platform. */
  internal val userAgentPlatform: String,
  internal val mobile: Boolean,
  /** `Sec-CH-UA-Platform`'s value. */
  internal val clientHintPlatform: String,
) {
  Android("Linux; Android 10; K", mobile = true, clientHintPlatform = "Android"),
  Mac("Macintosh; Intel Mac OS X 10_15_7", mobile = false, clientHintPlatform = "macOS"),
}

/** The platform this build runs on: Android on Android, Mac on desktop (the only desktop release). */
internal expect val currentBrowserPlatform: BrowserPlatform

/**
 * What Chrome [majorVersion] on [platform] sends on every request: its reduced User-Agent and its low-entropy client
 * hints. Since Chrome's User-Agent reduction every Chrome on a platform sends these same strings, so the client blends
 * in with them (a random string per install would stand out).
 */
class BrowserFingerprint(platform: BrowserPlatform, majorVersion: Int = CHROME_MAJOR_VERSION) {
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
