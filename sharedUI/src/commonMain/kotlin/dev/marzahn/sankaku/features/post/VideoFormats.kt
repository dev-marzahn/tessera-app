package dev.marzahn.sankaku.features.post

/** Whether this platform's player opens [source] (a URL without its query, see [videoSource]). */
internal expect fun playsVideoFile(source: String): Boolean
