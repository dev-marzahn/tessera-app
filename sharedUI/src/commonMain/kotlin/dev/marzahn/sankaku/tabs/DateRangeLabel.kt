package dev.marzahn.sankaku.tabs

/**
 * A `date:A..B` tag in words, for a tab's title: "1 Jan 2012" for one whole day, "1 Jan 2012 – 3 Jan 2012" for a longer
 * range, and "1 Jan 2012, 00:00–08:00" for a range inside one day. The times are UTC and to the hour, as the search's
 * are (docs/api.md, step 18), and are left out when the range has none. Null for any other tag, or a range that isn't two dates (the tag is then shown as typed).
 */
internal fun dateRangeLabel(tag: String): String? {
  val range = tag.takeIf { it.startsWith("date:", ignoreCase = true) }?.drop("date:".length)?.split("..") ?: return null
  if (range.size != 2) return null
  val from = DateTime.of(range[0]) ?: return null
  val to = DateTime.of(range[1]) ?: return null
  // An empty range (the end is exclusive, and a date alone means hour 0) or a reversed one isn't a window to name: it's
  // shown as typed.
  if (from.instant >= to.instant) return null
  return when {
    from.time != null && to.time != null && from.day == to.day -> "${from.day}, ${from.time}–${to.time}"
    from.time == null && to.time == null && to.next(from) -> from.day
    else -> "${from.text()} – ${to.text()}"
  }
}

/** One end of a range: `2012-01-01`, or with a time `2012-01-01T08:00:00` (a trailing `Z` is allowed). */
private class DateTime(val year: Int, val month: Int, val dayOfMonth: Int, val time: String?) {
  val day: String get() = "$dayOfMonth ${MONTHS[month - 1]} $year"

  /** Year, month, day and hour as one number that orders as time does; a date with no time is hour 0. */
  val instant: Long get() = (((year * 100L + month) * 100 + dayOfMonth) * 100) + (time?.take(2)?.toInt() ?: 0)

  fun text() = if (time == null) day else "$day $time"

  /** Whether this is the day after [other], so a range of two dates a day apart reads as the one day. */
  fun next(other: DateTime): Boolean {
    val length = daysIn(other.year, other.month)
    return when {
      other.dayOfMonth < length -> year == other.year && month == other.month && dayOfMonth == other.dayOfMonth + 1
      other.month < 12 -> year == other.year && month == other.month + 1 && dayOfMonth == 1
      else -> year == other.year + 1 && month == 1 && dayOfMonth == 1
    }
  }

  companion object {
    private val FORMAT = Regex("""(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::\d{2})?)?Z?""")

    fun of(text: String): DateTime? {
      val match = FORMAT.matchEntire(text.trim()) ?: return null
      val (year, month, day, hour, minute) = match.destructured
      val m = month.toInt()
      if (m !in 1..12 || day.toInt() !in 1..daysIn(year.toInt(), m)) return null
      // The search works to the UTC hour (docs/api.md, step 18): the minutes would claim a window the grid doesn't show.
      return DateTime(year.toInt(), m, day.toInt(), if (hour.isEmpty()) null else "$hour:00")
    }
  }
}

private fun daysIn(year: Int, month: Int): Int = when (month) {
  2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
  4, 6, 9, 11 -> 30
  else -> 31
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
