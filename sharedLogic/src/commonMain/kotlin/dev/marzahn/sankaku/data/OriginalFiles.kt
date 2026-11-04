package dev.marzahn.sankaku.data

import dev.marzahn.sankaku.api.ImageRefusedException
import dev.marzahn.sankaku.api.dto.PostDto
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readBuffer
import kotlinx.io.RawSink
import kotlin.time.Clock

/** The post has no original file to download, even after a refetch (signed out, a post hidden behind sign-up). */
class NoOriginalFileException : Exception("No original file")

/** The image host answered a download with an error status. Holds no URL: those carry the signature. */
class DownloadFailedException(val status: Int) : Exception("Download failed ($status)")

/**
 * Downloads a post's original file (`file_url`: the image, video or GIF as uploaded), for Save. Its URL is the one
 * the app holds, refetched through the post request ([PostsRepository.refetch], POSTS headers, never V2) when it's
 * missing, about to expire, or refused; a refusal retries once. Through the main client, so the download gets the
 * User-Agent, the rate limiter and the image hosts' retry, and never the bearer token.
 */
class OriginalFiles(
  private val client: HttpClient,
  private val repository: PostsRepository,
  private val store: PostStateStore,
  private val clock: Clock = Clock.System,
) {
  /**
   * Streams [post]'s original file into [sink], which sees bytes only once the host has answered with the file, so a
   * refused attempt leaves it untouched. It doesn't close [sink].
   */
  suspend fun download(post: PostDto, sink: RawSink) {
    val known = store.overrides.value[post.id]?.urls?.file ?: post.fileUrl
    if (known == null || !isSignedUrlUsable(known, clock.now())) return fetch(freshUrl(post.id), sink)
    try {
      fetch(known, sink)
    } catch (e: Exception) {
      if (!e.isRefusal()) throw e
      fetch(freshUrl(post.id), sink)
    }
  }

  private suspend fun freshUrl(postId: String): String = repository.refetch(postId).fileUrl ?: throw NoOriginalFileException()

  private suspend fun fetch(url: String, sink: RawSink) {
    client.prepareGet(url).execute { response ->
      val status = response.status.value
      if (status !in 200..299) throw DownloadFailedException(status)
      val body = response.bodyAsChannel()
      while (!body.exhausted()) body.readBuffer(CHUNK_BYTES).transferTo(sink)
      sink.flush()
    }
  }

  private fun Throwable.isRefusal(): Boolean = generateSequence(this) { it.cause }.any {
    it is ImageRefusedException || (it is DownloadFailedException && it.status in REFUSED_STATUSES)
  }

  private companion object {
    const val CHUNK_BYTES = 256L * 1024
    val REFUSED_STATUSES = setOf(401, 403, 404, 410)
  }
}

/**
 * `<md5>.<ext>`, as the file is called on Sankaku's file host. Both parts come from the API, so only letters and
 * digits are kept: a `/`, `..` or control character can never reach a path or a MediaStore name.
 */
fun originalFileName(post: PostDto): String {
  fun String.safe(max: Int) = filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.take(max).lowercase()
  val ext = post.fileExt?.safe(MAX_EXT)?.ifEmpty { null }
    // The subtype without a structured suffix or parameters: `image/svg+xml` is `svg`, `video/mp4; codecs=…` is `mp4`.
    ?: post.fileType?.substringAfter('/', "")?.substringBefore('+')?.substringBefore(';')?.safe(MAX_EXT)?.ifEmpty { null }
    ?: "bin"
  val base = post.md5.safe(MAX_BASE).ifEmpty { "post" }
  return "$base.$ext"
}

private const val MAX_EXT = 8
private const val MAX_BASE = 64

/** The file's MIME type, from the post's `file_type`, else its extension; null when neither says. */
fun originalMimeType(post: PostDto): String? =
  // Without parameters (`video/mp4; codecs=…` is `video/mp4`), as the file name's extension takes it.
  post.fileType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { '/' in it } ?: when (post.fileExt?.lowercase()) {
  "jpg", "jpeg" -> "image/jpeg"
  "png" -> "image/png"
  "gif" -> "image/gif"
  "webp" -> "image/webp"
  "avif" -> "image/avif"
  "mp4" -> "video/mp4"
  "webm" -> "video/webm"
  else -> null
}
