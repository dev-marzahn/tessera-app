package dev.marzahn.sankaku.features.post

internal actual fun playsVideoFile(source: String): Boolean = desktopPlaysVideoFile(source, isMac = IS_MAC)

/**
 * On macOS, what [avPlayerOpens]; the other desktop systems' backends weren't checked, so they try every file. [isMac]
 * is a parameter so a test covers both.
 */
internal fun desktopPlaysVideoFile(source: String, isMac: Boolean): Boolean = !isMac || avPlayerOpens(source)

/**
 * AVPlayer, behind ComposeMediaPlayer on macOS, opens MP4 but not WebM, and never fails fast: the library waits about
 * 7 s for a video size that never comes, then fails (#192). WebM isn't opened there at all.
 */
internal fun avPlayerOpens(source: String): Boolean = !source.substringAfterLast('/').endsWith(".webm", ignoreCase = true)

private val IS_MAC = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)
