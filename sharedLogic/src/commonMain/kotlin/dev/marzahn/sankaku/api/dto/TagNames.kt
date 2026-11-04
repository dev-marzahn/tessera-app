package dev.marzahn.sankaku.api.dto

/**
 * How a tag's name reads, on its chip and in every title (#246). [shown] is what [TagDto] and [TagSuggestion] show;
 * [ofKey] names a search tag the app has learnt nothing about.
 */
object TagNames {
  /**
   * [name] as the site gives it ("Coconut (Girls und Panzer)", "^_^"), unless it is only the search key [key] in some
   * case ("Jien_(Nikke)" for `jien_(nikke)`, or for an excluded suggestion's `-jien_(nikke)`); then, as with no key at
   * all, with spaces for its underscores.
   */
  fun shown(name: String, key: String?): String =
    if (key == null || name.removePrefix("-").equals(key.removePrefix("-"), ignoreCase = true)) name.replace('_', ' ') else name

  /**
   * A search key with no known name (a source restored from `tabs.json`, a typed tag): spaces for its underscores and
   * each word's first letter capitalised, so `jien_(nikke)` is "Jien (Nikke)". Nothing is lowercased, and a `-` inside a
   * word starts no new one ("K-suwabe").
   */
  fun ofKey(key: String): String {
    val spaced = key.replace('_', ' ')
    return buildString(spaced.length) {
      spaced.forEachIndexed { i, c -> append(if (i == 0 || spaced[i - 1] == ' ' || spaced[i - 1] == '(') c.uppercaseChar() else c) }
    }
  }
}
