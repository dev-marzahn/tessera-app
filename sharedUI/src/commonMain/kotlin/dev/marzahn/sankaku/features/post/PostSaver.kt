package dev.marzahn.sankaku.features.post

import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.NoOriginalFileException
import dev.marzahn.sankaku.data.OriginalFiles
import dev.marzahn.sankaku.data.originalFileName
import dev.marzahn.sankaku.data.originalMimeType
import dev.marzahn.sankaku.ui.Message
import dev.marzahn.sankaku.ui.Messages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.io.RawSink

/** A file Save wrote, which the "Saved" snackbar's Open shows in the platform's viewer. */
fun interface SavedFile {
  fun open()
}

/**
 * Where Save puts files: `~/Downloads/Tessera` on desktop, `Download/Tessera` through MediaStore on Android (no
 * storage permission).
 */
interface SaveFolder {
  /** The file called [name] that Save wrote before, or null. */
  suspend fun find(name: String): SavedFile?

  /**
   * Writes the file called [name] through [write]. The file shows in the folder only once [write] has returned; if it
   * throws, nothing is left behind.
   */
  suspend fun save(name: String, mimeType: String?, write: suspend (RawSink) -> Unit): SavedFile
}

/** How a save ended, for the control that started it to show. */
sealed interface SaveOutcome {
  data class Saved(val file: SavedFile) : SaveOutcome

  /** [noFile]: the post has no original file, so a retry can't help. */
  data class Failed(val noFile: Boolean) : SaveOutcome
}

/**
 * Save: a post's original file into the [SaveFolder], then "Saved" with Open. Runs on the app scope, so a download
 * finishes even if its screen closes. A post already in the folder isn't downloaded again; a post already saving
 * isn't started twice.
 *
 * A control showing a post can [claim] it (touch: the viewer's ⋮, `.scratch/android-feedback/`, C, rows 8, 11 and 12).
 * While a post is claimed, "Saving…" and "Saved · Open" aren't messages: the control shows them from [saving] and
 * [fresh]. A failure's reason is always a message. Nothing claims on desktop, so there every step is a message.
 */
class PostSaver(
  private val files: OriginalFiles,
  private val folder: SaveFolder,
  private val messages: Messages,
  private val scope: CoroutineScope,
) {
  private val _saving = MutableStateFlow(emptySet<String>())

  /** The ids of the posts being saved now. */
  val saving: StateFlow<Set<String>> = _saving.asStateFlow()

  private val _saved = MutableStateFlow(emptyMap<String, SavedFile>())

  /** The files saved in this run, by post id: ⋮'s "Open saved file". */
  val saved: StateFlow<Map<String, SavedFile>> = _saved.asStateFlow()

  private val _fresh = MutableStateFlow(emptyMap<String, SaveOutcome>())

  /** Outcomes that ended while their post was claimed, until the control's window for them ends ([settle]). */
  val fresh: StateFlow<Map<String, SaveOutcome>> = _fresh.asStateFlow()

  private val claims = MutableStateFlow(emptyMap<String, Int>())

  /**
   * Marks [postId] as shown by a control, until the returned function is called. When the last claim goes while a
   * "Saved" is still fresh (the viewer closed within its window, or it ended in immersive and was never shown), it
   * goes to the messages as "Saved · Open", so a success is never lost.
   */
  fun claim(postId: String): () -> Unit {
    claims.update { it + (postId to (it[postId] ?: 0) + 1) }
    var released = false
    return {
      if (!released) {
        released = true
        claims.update { val left = (it[postId] ?: 1) - 1; if (left > 0) it + (postId to left) else it - postId }
        if (!claimed(postId)) {
          val unshown = _fresh.value[postId]
          settle(postId)
          if (unshown is SaveOutcome.Saved) messages.show(savedMessage(unshown.file))
        }
      }
    }
  }

  private fun savedMessage(file: SavedFile) = Message("Saved", actionLabel = "Open", onAction = file::open)

  /** Ends [postId]'s fresh outcome: its window on the control is over, or the control left the post. */
  fun settle(postId: String) {
    _fresh.update { it - postId }
  }

  private fun claimed(postId: String) = postId in claims.value

  fun save(post: PostDto) {
    var started = false
    _saving.update {
      started = post.id !in it
      it + post.id
    }
    if (!started) return
    settle(post.id)
    scope.launch {
      // The id leaves `saving` first: the final message never shows while the post still counts as saving.
      val outcome = try {
        val name = originalFileName(post)
        val saved = folder.find(name) ?: run {
          if (!claimed(post.id)) messages.show("Saving…")
          folder.save(name, originalMimeType(post)) { sink -> files.download(post, sink) }
        }
        SaveOutcome.Saved(saved)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        SaveOutcome.Failed(noFile = e is NoOriginalFileException)
      } finally {
        _saving.update { it - post.id }
      }
      if (outcome is SaveOutcome.Saved) _saved.update { it + (post.id to outcome.file) }
      val onControl = claimed(post.id)
      if (onControl) _fresh.update { it + (post.id to outcome) }
      when (outcome) {
        is SaveOutcome.Saved -> if (!onControl) messages.show(savedMessage(outcome.file))
        // Never the exception's message: a network error's can hold the signed URL.
        is SaveOutcome.Failed -> messages.showError(if (outcome.noFile) "Couldn't save: this post has no file" else "Couldn't save the file")
      }
    }
  }
}

/** The post's page on the site, for Open in browser and Share. */
fun postPageUrl(post: PostDto): String = "https://www.sankakucomplex.com/posts/${post.id}"
