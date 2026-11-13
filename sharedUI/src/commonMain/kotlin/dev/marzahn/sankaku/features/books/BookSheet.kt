package dev.marzahn.sankaku.features.books

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.fonts.icons.chevron_right
import dev.marzahn.sankaku.Route
import dev.marzahn.sankaku.api.dto.BookDto
import dev.marzahn.sankaku.api.dto.SeriesDto
import dev.marzahn.sankaku.api.dto.TagDto
import dev.marzahn.sankaku.data.BookFavourite
import dev.marzahn.sankaku.ui.components.HeartToggle
import dev.marzahn.sankaku.ui.components.TagChip
import dev.marzahn.sankaku.ui.components.TagChipState
import dev.marzahn.sankaku.ui.openOnClick
import kotlinx.coroutines.launch

/** A Books search for [tag] alone: what a tag in the book sheet opens. */
internal fun booksSearchFor(tag: TagDto): Route.Books = Route.Books(listOf(tag.searchName))

/**
 * A book's description as plain text: the site writes it in DText, whose wiki links (`[[tag]]`, `[[tag|shown]]`) and
 * links (`"text":https://…`) read as their text.
 */
internal fun plainDescription(dtext: String): String = dtext
  .replace(WIKI_LINK) { it.groupValues[2].ifEmpty { it.groupValues[1] } }
  .replace(LINK) { it.groupValues[1] }
  .trim()

private val WIKI_LINK = Regex("""\[\[([^\]|]+)(?:\|([^\]]*))?]]""")
private val LINK = Regex(""""([^"]+)":(?:https?://|/)\S+""")

/**
 * The series the reader knows a book is in (#383): the [id] of the result it was opened as ([ResultBook.seriesId]), and
 * whether the reader was opened from that series' own list ([listed]), which is then under it. A book's own data doesn't
 * name its series (`:cli` step d20), so a book opened any other way has none.
 */
data class SheetSeries(val id: String, val listed: Boolean)

/** A series as the sheet shows it: [route] opens its books; the book is [position] of [count]. */
internal data class InSeries(val route: Route.BookSeries, val title: String, val position: Int?, val count: Int)

/** [series] as the sheet shows it for [bookId]. */
internal fun inSeries(series: SeriesDto, bookId: String): InSeries = InSeries(
  route = Route.BookSeries(series.id, series.title),
  title = series.title,
  position = series.books.firstOrNull { it.id == bookId }?.sequence,
  count = maxOf(series.poolCount, series.books.size),
)

/** "Book 2 of 6", or "6 books" when the book's place isn't known. */
internal fun seriesPlace(series: InSeries): String =
  series.position?.let { "Book $it of ${series.count}" } ?: booksLabel(series.count)

/**
 * The book sheet (#377 decision 10): the book's title, ♡ and page count, its description, artist and genre tags and
 * the series it was opened from. A tag opens its page ([onOpenTag], #486: its posts and books), or on a middle-click or
 * long-press a Books search for it ([onOpenInNewTab]); the series opens its books: [onOpen], or [onOpenInNewTab]. It
 * opens at its full height when it fits, as the tag sheet does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookSheet(
  book: BookDto,
  pages: Int,
  favourite: BookFavourite,
  favouriteRejected: Int,
  series: InSeries?,
  onToggleFavourite: () -> Unit,
  onOpen: (Route) -> Unit,
  onOpenInNewTab: (Route) -> Unit,
  onOpenTag: (TagDto) -> Unit,
  onDismiss: () -> Unit,
) {
  val sheet = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
  val scope = rememberCoroutineScope()
  // The chips stay tappable while the sheet slides away, so a second tap mustn't open a second search.
  var chosen by remember { mutableStateOf(false) }
  fun choose(action: () -> Unit) {
    if (chosen) return
    chosen = true
    action()
    scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() }
  }
  val colors = MaterialTheme.colorScheme
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheet,
    containerColor = colors.surfaceContainerLow,
    modifier = Modifier.semantics { paneTitle = BOOK_SHEET },
  ) {
    Column(
      Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(start = 24.dp, end = 12.dp, bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            book.title.ifBlank { UNTITLED },
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
          )
          Text(pagesLabel(pages), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        HeartToggle(checked = favourite.on, onCheckedChange = { onToggleFavourite() }, rejected = favouriteRejected)
      }
      book.shownDescription?.let { description ->
        Text(plainDescription(description), Modifier.padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
      }
      TagGroup(ARTISTS, book.artistTags, onOpen = { choose { onOpenTag(it) } }, onOpenInNewTab = { onOpenInNewTab(booksSearchFor(it)) })
      TagGroup(GENRES, book.genreTags, onOpen = { choose { onOpenTag(it) } }, onOpenInNewTab = { onOpenInNewTab(booksSearchFor(it)) })
      if (series != null) {
        HorizontalDivider(color = colors.outlineVariant)
        SeriesRow(series, onOpen = { choose { onOpen(series.route) } }, onOpenInNewTab = { onOpenInNewTab(series.route) })
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagGroup(label: String, tags: List<TagDto>, onOpen: (TagDto) -> Unit, onOpenInNewTab: (TagDto) -> Unit) {
  if (tags.isEmpty()) return
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      label,
      Modifier.semantics { heading() },
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      tags.forEach { tag ->
        val interactions = remember { MutableInteractionSource() }
        TagChip(
          name = tag.displayName,
          type = tag.category,
          count = null,
          state = TagChipState.Plain,
          selected = false,
          interactionSource = interactions,
          modifier = Modifier
            .openOnClick(onOpen = { onOpen(tag) }, onOpenInNewTab = { onOpenInNewTab(tag) }, interactionSource = interactions)
            .semantics { role = Role.Button },
        )
      }
    }
  }
}

@Composable
private fun SeriesRow(series: InSeries, onOpen: () -> Unit, onOpenInNewTab: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .openOnClick(onOpen = onOpen, onOpenInNewTab = onOpenInNewTab)
      .semantics(mergeDescendants = true) { role = Role.Button },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f).widthIn(min = 0.dp)) {
      Text(SERIES, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(series.title.ifBlank { UNTITLED }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Text(seriesPlace(series), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Icon(chevron_right, contentDescription = null, Modifier.padding(horizontal = 12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

internal const val BOOK_SHEET = "Book details"
private const val ARTISTS = "Artists"
private const val GENRES = "Genres"
private const val SERIES = "Series"
