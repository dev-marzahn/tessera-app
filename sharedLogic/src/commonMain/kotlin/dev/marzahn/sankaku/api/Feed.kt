package dev.marzahn.sankaku.api

/**
 * One of the home page's feeds: the keyset list with [flag] set (discovery step 13). A grid carries it among its tags
 * as [tag], a word of the app's own that the API never sees: [PostsApi.keyset] sends it as the flag. So a feed is
 * routed, saved with its tab, searched within and shown as a filter like any other tag. Following needs a session:
 * signed out, the API ignores its flag and returns the plain list.
 */
enum class Feed(val tag: String, val flag: String, val label: String) {
  ForYou("feed:for-you", "is_recommendation", "For you"),
  Following("feed:following", "is_following", "Following"),
  ;

  companion object {
    fun of(tag: String): Feed? = entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }
  }
}
