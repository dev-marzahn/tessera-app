package dev.marzahn.sankaku.features.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.fonts.icons.check
import com.google.fonts.icons.chevron_right
import dev.marzahn.sankaku.api.dto.BookDto

/**
 * What a book's end card offers after "Finished" (#503): the [nextVolume] of its series, when the reader knows it
 * (#377 decision 13: only a book opened from its series' row or list), the [nextResult] of the list it was opened from,
 * and [back], the name of the reader's close: "Back to Books" over a list, "Close" for a book opened on its own or kept.
 * With the rating [locked], the covers of other ratings stay grey, as on the rows.
 */
internal class BookEnd(
  val nextVolume: BookDto? = null,
  val onNextVolume: () -> Unit = {},
  val nextResult: BookDto? = null,
  val onNextResult: () -> Unit = {},
  val back: String = CLOSE_BOOK,
  val locked: Boolean = true,
)

/**
 * The strip's end (#503, replacing #382's "Next" card): "Finished", the book's [title] and [pages], then the choices
 * [end] has, the next volume first, and the way back ([onBack]). Clear of the count pill and the navigation bar, so
 * it's whole on screen at the strip's end.
 */
@Composable
internal fun EndCard(title: String, pages: Int, end: BookEnd, onBack: () -> Unit) {
  Surface(
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.surfaceContainer,
    modifier = Modifier
      .widthIn(max = MAX_STRIP_WIDTH)
      .fillMaxWidth()
      .windowInsetsPadding(WindowInsets.navigationBars)
      .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = END_CARD_BOTTOM)
      .semantics { testTag = END_CARD_TAG },
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) {
          Box(contentAlignment = Alignment.Center) {
            Icon(check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
          }
        }
        Column(Modifier.weight(1f)) {
          Text(FINISHED, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
          Text(
            titleAndPages(title.ifBlank { null }, pages),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      end.nextVolume?.let { book ->
        EndChoice(NEXT_VOLUME, book, end.locked, primary = true, onClick = end.onNextVolume, label = nextVolumeLabel(book.titleOrUntitled()))
      }
      end.nextResult?.let { book ->
        EndChoice(NEXT_RESULT, book, end.locked, primary = end.nextVolume == null, onClick = end.onNextResult, label = nextResultLabel(book.titleOrUntitled()))
      }
      TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(end.back) }
    }
  }
}

/** One way on: [heading] over the [book]'s title and pages, its cover beside them. [label] is what it's read as. */
@Composable
private fun EndChoice(heading: String, book: BookDto, locked: Boolean, primary: Boolean, onClick: () -> Unit, label: String) {
  Surface(
    onClick = onClick,
    shape = MaterialTheme.shapes.large,
    color = if (primary) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = label },
  ) {
    Row(
      Modifier.padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      BookPicture(book.coverPost, locked, Modifier.size(40.dp, 60.dp))
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(heading, style = MaterialTheme.typography.titleSmall)
        Text(
          titleAndPages(book.titleOrUntitled(), book.postCount),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Icon(chevron_right, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

private fun BookDto.titleOrUntitled() = title.ifBlank { UNTITLED }

/** "Title · 32 pages", leaving out what isn't known. */
private fun titleAndPages(title: String?, pages: Int) =
  listOfNotNull(title, pagesLabel(pages).takeIf { pages > 0 }).joinToString(" · ")

/** The next volume's choice, as read aloud. */
internal fun nextVolumeLabel(title: String) = "$NEXT_VOLUME: $title"

/** The next result's choice, as read aloud. */
internal fun nextResultLabel(title: String) = "$NEXT_RESULT: $title"

internal const val FINISHED = "Finished"
internal const val NEXT_VOLUME = "Next volume"
internal const val NEXT_RESULT = "Next result"
internal const val BACK_TO_BOOKS = "Back to Books"
internal const val CLOSE_BOOK = "Close"
internal const val END_CARD_TAG = "book-end"

/** The strip's end item's key: not a page. */
internal const val END_KEY = "end"

/** Room under the end card for the queue's count pill (#483). */
private val END_CARD_BOTTOM = 96.dp
