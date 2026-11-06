package dev.marzahn.sankaku.tabs

import dev.marzahn.sankaku.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A `date:` range is titled in words, not as the tag was typed. */
class DateRangeLabelTest {
  @Test
  fun aRangeInsideOneDayShowsTheDayAndTheTimes() {
    assertEquals("1 Jan 2012, 00:00–08:00", dateRangeLabel("date:2012-01-01T00:00:00..2012-01-01T08:00:00"))
    assertEquals("5 Mar 2024, 13:00–14:00", dateRangeLabel("date:2024-03-05T13:30..2024-03-05T14:00Z"))
  }

  @Test
  fun twoDatesADayApartAreThatDay() {
    assertEquals("1 Jan 2012", dateRangeLabel("date:2012-01-01..2012-01-02"))
    assertEquals("31 Dec 2012", dateRangeLabel("date:2012-12-31..2013-01-01"))
    assertEquals("29 Feb 2024", dateRangeLabel("date:2024-02-29..2024-03-01"))
  }

  @Test
  fun aLongerRangeShowsBothEnds() {
    assertEquals("1 Jan 2012 – 3 Jan 2012", dateRangeLabel("date:2012-01-01..2012-01-03"))
    assertEquals("1 Jan 2012 00:00 – 2 Jan 2012 08:00", dateRangeLabel("date:2012-01-01T00:00:00..2012-01-02T08:00:00"))
  }

  @Test
  fun anythingElseIsLeftAsTyped() {
    assertNull(dateRangeLabel("artist_x"))
    assertNull(dateRangeLabel("date:2012-01-01"))
    assertNull(dateRangeLabel("date:2012-01-01.."))
    assertNull(dateRangeLabel("date:2012-13-01..2012-13-02"))
    assertNull(dateRangeLabel("date:last_week..now"))
    // The search works to the hour and its end is exclusive, so these are empty: no window to name.
    assertNull(dateRangeLabel("date:2012-01-01T08:00:00..2012-01-01T08:00:00"))
    assertNull(dateRangeLabel("date:2012-01-01T08:10..2012-01-01T08:50"))
    assertNull(dateRangeLabel("date:2012-01-01..2012-01-01"))
    // A date alone is hour 0, and a reversed range is empty too.
    assertNull(dateRangeLabel("date:2012-01-01..2012-01-01T00:00"))
    assertNull(dateRangeLabel("date:2012-01-01T00:00..2012-01-01"))
    assertNull(dateRangeLabel("date:2012-01-03..2012-01-01"))
    assertNull(dateRangeLabel("date:2012-01-01T09:00..2012-01-01T08:00"))
  }

  @Test
  fun aTabOnARangeIsTitledInWords() {
    assertEquals("1 Jan 2012", tabTitle(Route.Grid(listOf("date:2012-01-01..2012-01-02"))))
    assertEquals("Artist X 1 Jan 2012", tabTitle(Route.Grid(listOf("artist_x", "date:2012-01-01..2012-01-02"))))
  }
}
