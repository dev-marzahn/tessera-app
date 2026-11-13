package dev.marzahn.sankaku.features.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import com.google.fonts.icons.favorite
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.dto.BookDto
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.data.BooksRepository
import dev.marzahn.sankaku.data.favourite
import dev.marzahn.sankaku.data.ReadingProgress
import dev.marzahn.sankaku.data.SavedReading
import dev.marzahn.sankaku.data.Variant
import dev.marzahn.sankaku.data.isSafe
import dev.marzahn.sankaku.features.grid.CURSOR_GAP
import dev.marzahn.sankaku.features.grid.CURSOR_RING
import dev.marzahn.sankaku.features.grid.Rating
import dev.marzahn.sankaku.features.grid.RatingBadge
import dev.marzahn.sankaku.features.grid.openInBackgroundLabel
import dev.marzahn.sankaku.ui.components.AlreadyTab
import dev.marzahn.sankaku.ui.components.InTabChip
import dev.marzahn.sankaku.ui.components.InTabWash
import dev.marzahn.sankaku.ui.components.SeenBadge
import dev.marzahn.sankaku.ui.theme.Amber
import dev.marzahn.sankaku.ui.components.seenImage
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import dev.marzahn.sankaku.images.PostImage
import dev.marzahn.sankaku.images.postImageRequest
import dev.marzahn.sankaku.shell.RetryWhenBack
import dev.marzahn.sankaku.ui.components.HeartToggle
import dev.marzahn.sankaku.ui.openOnClick
import dev.marzahn.sankaku.platformIsTouch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject

/**
 * What a row says beside its pictures: a book's own title, page count, ♡ count, rating and progress; a series' title,
 * "N books" and rating. [pictures] is the book whose cover and first pages it shows.
 */
internal data class BookRowText(
  val title: String,
  val count: String,
  val favCount: Int?,
  val rating: Rating?,
  /** Where the book was left ([ReadingProgress.savedOf]); null when never opened (or a series). */
  val reading: SavedReading?,
)

/** [reading] is [ReadingProgress.savedOf] this book. */
internal fun BookDto.rowText(reading: SavedReading?): BookRowText = BookRowText(
  title = title.ifBlank { UNTITLED },
  count = pagesLabel(postCount),
  favCount = favCount,
  rating = Rating.ofCode(rating),
  reading = reading,
)

internal fun pagesLabel(pages: Int) = if (pages == 1) "1 page" else "$pages pages"

/** A book row's favourite count as a screen reader says it (#521). */
internal fun favouritesLabel(count: Int) = if (count == 1) "1 favourite" else "$count favourites"

internal fun booksLabel(books: Int) = if (books == 1) "1 book" else "$books books"

/** "Page 12 / 34": where the account stopped; "Read" once finished. */
internal fun readingLabel(reading: SavedReading) = if (reading.finished) READ else "Page ${reading.page} / ${reading.pages}"

/** A finished book's mark on its cover (#384). */
internal const val READ = "Read"

/**
 * Where a book was left ([ReadingProgress.savedOf]) for rows, [signedIn] or not. A row that calls it recomposes
 * whenever a place is saved, so closing a book updates its cover at once.
 */
@Composable
internal fun rememberReadings(signedIn: Boolean, progress: ReadingProgress = koinInject()): (BookDto) -> SavedReading? {
  val revision by progress.revision.collectAsStateWithLifecycle()
  return { book -> revision.let { progress.savedOf(book, signedIn) } }
}

internal const val UNTITLED = "Untitled"

/** How many of the first pages a row shows beside its cover: 3 with room for them, else 2 (#377 decision 4). */
internal fun pageThumbs(rowWidth: Dp): Int = if (rowWidth >= WIDE_ROW) 3 else 2

/** How a book row lays out: [thumbs] pages after the cover, and the text beside them or, [stacked], under them. */
internal data class BookRowLayout(val thumbs: Int, val stacked: Boolean)

/**
 * A row's layout in [width] (inside its padding) (#525): above 100% text, page thumbnails drop, the last first, until the title has
 * [MIN_TITLE] (scaled by [fontScale]) beside them and the [trailing] heart; when even the cover alone leaves it less,
 * the text goes under the pictures, which then show all [maxThumbs].
 */
internal fun bookRowLayout(width: Dp, cover: Dp, thumb: Dp, maxThumbs: Int, trailing: Dp, fontScale: Float): BookRowLayout {
  // At 100% text or less the row is the spec's, with all its pages: only larger text gives way.
  if (fontScale <= 1f) return BookRowLayout(maxThumbs, stacked = false)
  fun titleRoom(thumbs: Int) = width - DETAILS_GAP - (cover + 8.dp + (thumb + 4.dp) * thumbs) - trailing
  val needed = MIN_TITLE * fontScale
  val fits = (maxThumbs downTo 0).firstOrNull { titleRoom(it) >= needed }
  return if (fits == null) BookRowLayout(maxThumbs, stacked = true) else BookRowLayout(fits, stacked = false)
}

/** The least room a row's title gets beside the pictures at 100% text: about twelve characters a line. */
private val MIN_TITLE = 96.dp

/** The gap between the pictures and the text. */
private val DETAILS_GAP = 12.dp

/**
 * The pages a row shows after [cover]: the first ones, without the cover itself when it's the first page (it usually
 * is), at most [count].
 */
internal fun rowPages(pages: List<PostDto>, cover: PostDto?, count: Int): List<PostDto> =
  pages.filterNot { cover != null && it.md5 == cover.md5 }.take(count)

/**
 * One row of the Books list (#380): [pictures]'s cover, then its first pages as smaller thumbnails, then [text]. The
 * pages are asked for only once the row is composed, which a lazy list does as it nears the screen, and come from
 * [BooksRepository]'s 10-minute cache when they can; grey placeholders stand until then, and for good when they
 * can't load (a pending book has none). With the rating [locked], a picture of another rating stays grey. A tap
 * anywhere is [onOpen]; a middle or Cmd/Ctrl-click (a long-press on touch, "Keep") [onOpenInNewTab]. A book's row
 * ([favourite] set; a series has none) has its ♡ by the title, and its ♡ count follows it (#383). [coverModifier] goes
 * on the cover, which is see-through while [coverHidden]: the reader stands in for it as it grows and shrinks (#382).
 *
 * [marks] are a post's in a grid (#385): a kept book has the "Kept" chip ("In tab" on desktop) under its counts, which
 * says "Already item N" or "Kept again" when keeping it again found it, and a book seen since it was kept from this
 * search has its cover faded with the seen badge.
 *
 * The keyboard [cursor]'s row (#417) has the grid cursor's amber ring around it, and is selected.
 */
@Composable
internal fun BookRow(
  pictures: BookDto,
  text: BookRowText,
  locked: Boolean,
  signedIn: Boolean,
  onOpen: () -> Unit,
  onOpenInNewTab: () -> Unit,
  modifier: Modifier = Modifier,
  favourite: BookDto? = null,
  coverHidden: Boolean = false,
  coverModifier: Modifier = Modifier,
  marks: BookMarks = BookMarks(),
  cursor: Boolean = false,
  books: BooksRepository = koinInject(),
  actions: BookActions = koinInject(),
) {
  val pending = pictures.status == "pending"
  val cached = remember(pictures.id, signedIn) { books.cachedFirstPages(pictures.id, signedIn) }
  // An ask that failed for want of a connection is asked again once back online (#128); any other failure, the next
  // time the row is shown. Placeholders stay meanwhile.
  var attempt by remember(pictures.id) { mutableIntStateOf(0) }
  var unreachable by remember(pictures.id) { mutableStateOf(false) }
  RetryWhenBack {
    if (unreachable) {
      unreachable = false
      attempt++
    }
  }
  val firstPages by produceState(cached, pictures.id, signedIn, pending, attempt) {
    // produceState keeps its value across new keys: signing in or out must not keep the other state's pages.
    value = books.cachedFirstPages(pictures.id, signedIn)
    if (value != null || pending) return@produceState
    value = try {
      books.firstPages(pictures.id, signedIn)
    } catch (e: ApiError) {
      unreachable = e is ApiError.Network
      null
    }
  }
  Surface(
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = modifier
      .fillMaxWidth()
      .cursorRing(cursor)
      .clip(MaterialTheme.shapes.large)
      // On desktop the row's focus is the cursor (#418), so its ring is the only focus indicator.
      .openOnClick(onOpen, onOpenInNewTab, inTab = marks.kept, longPressLabel = openInBackgroundLabel(), focusFill = platformIsTouch)
      .semantics(mergeDescendants = true) {
        role = Role.Button
        testTag = BOOK_ROW_TAG
        selected = cursor
        // The chip's live region is merged into the row, so the row says "Already in tab N" or "Kept again".
        if (marks.already != null) liveRegion = LiveRegionMode.Polite
        // TalkBack's Actions list leaves a long-press out, as on a grid cell.
        customActions = listOf(CustomAccessibilityAction(openInBackgroundLabel()) { onOpenInNewTab(); true })
      },
  ) {
    BoxWithConstraints(Modifier.padding(ROW_PADDING)) {
      val wide = pageThumbs(maxWidth + ROW_PADDING * 2) == 3
      val coverWidth = if (wide) 88.dp else 72.dp
      val thumbWidth = if (wide) 60.dp else 48.dp
      // The heart's 48 dp target, less the 8 dp it reaches past the row's padding.
      val layout = bookRowLayout(
        maxWidth,
        coverWidth,
        thumbWidth,
        pageThumbs(maxWidth + ROW_PADDING * 2),
        trailing = if (favourite != null) 40.dp else 0.dp,
        fontScale = LocalDensity.current.fontScale,
      )
      val thumbs = layout.thumbs
      val pictures = @Composable {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
          // The reader grows from the cover and shrinks back into it (#382), standing in for it meanwhile.
          // Hidden, it stays composed (and its picture loaded), so it's there the frame the reader lands in it. Its
          // progress mark (#384) and its kept and seen marks (#385) hide with it.
          Box(coverModifier.size(coverWidth, coverWidth * 1.5f).alpha(if (coverHidden) 0f else 1f)) {
            BookPicture(pictures.coverPost, locked, Modifier.matchParentSize().seenImage(marks.seen))
            CoverMarks(marks, Modifier.matchParentSize())
            text.reading?.let { CoverProgress(it, Modifier.matchParentSize()) }
          }
          // The cover stands a little apart from its pages.
          Spacer(Modifier.width(4.dp))
          if (!pending) {
            // Loaded, as many as the book has; until then, a placeholder for each.
            val shown = firstPages?.let { rowPages(it, pictures.coverPost, thumbs) }
            val slots = shown?.size ?: thumbs
            repeat(slots) { index ->
              BookPicture(shown?.getOrNull(index), locked, Modifier.size(thumbWidth, thumbWidth * 1.5f))
            }
          }
        }
      }
      val details = @Composable { detailsModifier: Modifier ->
        if (favourite != null) {
          val overrides by actions.favourites.overrides.collectAsStateWithLifecycle()
          val shown = favourite.favourite(overrides)
          BookRowDetails(text.copy(favCount = shown.count), detailsModifier, marks) {
            HeartToggle(
              checked = shown.on,
              onCheckedChange = { actions.toggleFavourite(favourite) },
              rejected = rememberBookRejections(actions, favourite.id),
              // Its corner, not its 48 dp target, lines up with the title's top.
              modifier = Modifier.offset(x = 8.dp, y = (-8).dp),
            )
          }
        } else {
          BookRowDetails(text, detailsModifier, marks)
        }
      }
      if (layout.stacked) {
        // Too narrow for the text beside even the cover alone (large text on a phone): the text goes under (#525).
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          pictures()
          details(Modifier.fillMaxWidth())
        }
      } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
          pictures()
          details(Modifier.weight(1f))
        }
      }
    }
  }
}

/**
 * The marks a book's row has as a post's cell does (#385): [kept] (some tab keeps it), [already] while keeping it again
 * found it there, and [seen] (kept from this search and seen since).
 */
internal data class BookMarks(val kept: Boolean = false, val already: AlreadyTab? = null, val seen: Boolean = false)

/**
 * [marks] over a cover, as a cell's: the wash of a kept book and the seen badge in the top-left corner. The chip
 * stands under the row's text ([BookRowDetails]): a 72 dp cover has no room for "Already item 12".
 */
@Composable
private fun CoverMarks(marks: BookMarks, modifier: Modifier) {
  if (!marks.kept && !marks.seen) return
  Box(modifier.clip(PictureShape)) {
    if (marks.kept) InTabWash()
    // Top-left: the progress bar (#384) runs along the bottom, and "Read" sits top-right.
    if (marks.seen) SeenBadge(Modifier.align(Alignment.TopStart).padding(4.dp))
  }
}

@Composable
private fun BookRowDetails(
  text: BookRowText,
  modifier: Modifier = Modifier,
  marks: BookMarks = BookMarks(),
  trailing: (@Composable () -> Unit)? = null,
) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.Top) {
      Text(text.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
      trailing?.invoke()
    }
    Text(text.count, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      text.rating?.let { RatingBadge(it) }
      text.favCount?.let { count ->
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
          // Said as text, after the title (#521): as the icon's description, it named every row "Favourites".
          Icon(favorite, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
          Text(
            "$count",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics { this.text = AnnotatedString(favouritesLabel(count)) },
          )
        }
      }
    }
    // A kept book's "Kept" chip ("In tab" on desktop), under the counts so the title never moves (#385).
    // Flat: it's in the text, not over a picture.
    if (marks.kept) InTabChip(Modifier.padding(top = 2.dp), marks.already, elevated = false)
    text.reading?.let { reading ->
      Text(readingLabel(reading), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/**
 * The cover's progress mark (#377 decision 17): a bar along its bottom edge for a started book, a "Read" badge in
 * its corner once finished.
 */
@Composable
internal fun CoverProgress(reading: SavedReading, modifier: Modifier) {
  Box(modifier.clip(PictureShape)) {
    if (reading.finished) {
      Surface(
        shape = RoundedCornerShape(bottomStart = 8.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.align(Alignment.TopEnd).semantics { testTag = COVER_READ_TAG },
      ) {
        Text(READ, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
      }
    } else {
      // White on a dark track, inset from the corners, so it reads on any cover.
      LinearProgressIndicator(
        progress = { reading.fraction },
        color = Color.White,
        trackColor = Color.Black.copy(alpha = 0.6f),
        gapSize = 0.dp,
        drawStopIndicator = {},
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(horizontal = 6.dp, vertical = 6.dp)
          .fillMaxWidth()
          .height(5.dp)
          .clip(RoundedCornerShape(3.dp))
          .semantics { testTag = COVER_PROGRESS_TAG },
      )
    }
  }
}

/**
 * A cover or page at a book's 2:3, cropped, over a grey placeholder. [post] null (not loaded, or none), sign-in-only,
 * or with the rating [locked] not G, it's only the placeholder, and nothing is asked for. The reader's cover, as it
 * grows from its row, and its "Next" card draw theirs with it too (#382).
 */
@Composable
internal fun BookPicture(post: PostDto?, locked: Boolean, modifier: Modifier) {
  Box(modifier) {
    Box(Modifier.matchParentSize().clip(PictureShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
      val shown = post?.takeIf { !it.redirectToSignup && (!locked || it.rating.isSafe()) }
      if (shown != null) {
        AsyncImage(
          model = postImageRequest(LocalPlatformContext.current, PostImage.of(shown, Variant.Preview)),
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.matchParentSize(),
        )
      }
    }
  }
}

private val PictureShape = RoundedCornerShape(8.dp)

/**
 * The keyboard cursor's row (#417): the grid cursor's amber ring, [CURSOR_GAP] outside the row's edge, in the gap
 * between rows, which is as wide as the grid's so the ring stays clear of the next row (#459). The list's top content
 * padding leaves [CURSOR_REACH] for it.
 */
private fun Modifier.cursorRing(shown: Boolean): Modifier = drawBehind {
  if (!shown) return@drawBehind
  val out = (CURSOR_GAP + CURSOR_RING / 2).toPx()
  drawRoundRect(
    Amber,
    topLeft = Offset(-out, -out),
    size = Size(size.width + 2 * out, size.height + 2 * out),
    cornerRadius = CornerRadius(ROW_CORNER.toPx() + out),
    style = Stroke(CURSOR_RING.toPx()),
  )
}

/** The row's corner: M3's large shape, which the row clips to. */
private val ROW_CORNER = 16.dp

private val ROW_PADDING = 12.dp

/** A row this wide fits the cover and 3 pages beside its text. */
private val WIDE_ROW = 440.dp

internal const val BOOK_ROW_TAG = "book-row"
internal const val COVER_PROGRESS_TAG = "cover-progress"
internal const val COVER_READ_TAG = "cover-read"
