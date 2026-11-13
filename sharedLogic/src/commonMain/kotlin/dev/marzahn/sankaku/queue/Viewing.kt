package dev.marzahn.sankaku.queue

/**
 * One viewer's (or post tab's) part of "seen": moving on from a post that was fully shown records it ([SeenStore]).
 *
 * A post is fully shown once it's the current, settled page and its image (the sample), its GIF or its video's first
 * frame has loaded. The screen says which post is [current] and which posts have [loaded]; moving to another post, or
 * [leave] (closing the viewer, or the post tab going out of view), then records the one moved on from if it was fully
 * shown. A post only scrolled past in a grid never reaches this, so it's never seen by it. A post kept while shown
 * ([keeping]) isn't recorded by that showing, which came before the keep (#537).
 */
class Viewing(private val seen: SeenStore) {
  private var current: Shown? = null

  // Posts whose image has loaded: the pages beside the current one load too, before they're stepped to.
  private val loaded = ArrayDeque<String>()

  /** The current, settled post is [postId] (with its [md5]), or none. Moving off a fully shown post records it. */
  fun current(postId: String?, md5: String?) {
    if (current?.id == postId) return
    leave()
    current = postId?.let { Shown(it, md5) }
  }

  /** The post [postId]'s image, GIF or first video frame has loaded. */
  fun loaded(postId: String) {
    if (postId in loaded) return
    loaded.addLast(postId)
    while (loaded.size > LOADED_KEPT) loaded.removeFirst()
  }

  /**
   * The post [postId] is about to be kept from this viewer (#537). If it's the current post, this showing came before
   * the keep, so moving on from it doesn't make the post seen since it was kept: it records nothing. Showing it again
   * after moving off does count. Nothing is recorded now either, so a keep of a post kept already still answers
   * "Already" rather than "Kept again".
   */
  fun keeping(postId: String) {
    current?.takeIf { it.id == postId }?.let { current = it.copy(keptWhileShown = true) }
  }

  /** The viewer closed, or its post went out of view: the current post, if it was fully shown, is seen. */
  fun leave() {
    val post = current ?: return
    current = null
    if (!post.keptWhileShown && post.id in loaded) seen.record(post.id, post.md5)
  }

  // [keptWhileShown]: kept during this showing, which then doesn't count ([keeping]).
  private data class Shown(val id: String, val md5: String?, val keptWhileShown: Boolean = false)

  private companion object {
    // The current page and the pages composed around it, with room to spare.
    const val LOADED_KEPT = 8
  }
}
