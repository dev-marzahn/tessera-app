package dev.marzahn.sankaku.queue

/**
 * One viewer's (or post tab's) part of "seen": moving on from a post that was fully shown records it ([SeenStore]).
 *
 * A post is fully shown once it's the current, settled page and its image (the sample), its GIF or its video's first
 * frame has loaded. The screen says which post is [current] and which posts have [loaded]; moving to another post, or
 * [leave] (closing the viewer, or the post tab going out of view), then records the one moved on from if it was fully
 * shown. A post only scrolled past in a grid never reaches this, so it's never seen by it.
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

  /** The viewer closed, or its post went out of view: the current post, if it was fully shown, is seen. */
  fun leave() {
    val post = current ?: return
    current = null
    if (post.id in loaded) seen.record(post.id, post.md5)
  }

  private data class Shown(val id: String, val md5: String?)

  private companion object {
    // The current page and the pages composed around it, with room to spare.
    const val LOADED_KEPT = 8
  }
}
