package dev.marzahn.sankaku.features.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.fonts.icons.menu_book
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.dto.BookDto
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.data.BooksRepository
import dev.marzahn.sankaku.data.PrivacySettings
import dev.marzahn.sankaku.features.grid.ratingLocked
import dev.marzahn.sankaku.ui.openOnClick
import kotlinx.coroutines.flow.first
import dev.marzahn.sankaku.Navigator
import org.koin.compose.koinInject

/**
 * The post sheet's "From the book *name*" row (#386): shown only for a post that is a book's page, once
 * [BooksRepository.bookOf] has found the book; a post in no book shows nothing and asks nothing, and the one request
 * a book's page needs waits until [onScreen] (the details are read). A click opens the book at its saved page through
 * [BookOpener], over the window like a row of the Books list; [onOpen] runs first: opening the book acts on the post.
 * A middle-click or long-press keeps it as a queue item (#385).
 */
@Composable
fun FromTheBook(
  post: PostDto,
  modifier: Modifier = Modifier,
  onScreen: () -> Boolean = { true },
  onOpen: () -> Unit = {},
  books: BooksRepository = koinInject(),
  opener: BookOpener = koinInject(),
  navigator: Navigator = koinInject(),
  session: AuthSession = koinInject(),
  privacy: PrivacySettings = koinInject(),
) {
  val signedIn by session.isSignedIn.collectAsState()
  val gOnly by privacy.gOnly.collectAsState()
  val locked = ratingLocked(signedIn, gOnly)
  // Held with what it was found for, so the previous post's book, or one found before signing out or turning G only
  // on, never shows while the next one is found; a kept one shows at once.
  val asked = Asked(post.id, signedIn, locked)
  // The held value outlives a change of post; a kept book for the new one shows from its first frame.
  val kept = remember(asked) { books.cachedBookOf(post, signedIn, locked) }
  val visible by rememberUpdatedState(onScreen)
  val book by produceState(kept?.let { asked to it }, asked, post.inVisiblePool) {
    // Only once the details are read, as the sheet's strips: a sheet at peek or a closed panel asks nothing.
    snapshotFlow { visible() }.first { it }
    value = try {
      books.bookOf(post, signedIn, locked)?.let { asked to it }
    } catch (_: ApiError) {
      null
    }
  }
  val shown = book?.takeIf { it.first == asked }?.second ?: kept ?: return
  FromTheBookRow(
    shown,
    modifier,
    open = {
      onOpen()
      opener.open(shown)
    },
    // Books are queue items now (#385): a middle-click or long-press keeps it, as a post's is kept.
    keep = {
      // Acts on the post as a click does, and as the sheet's other background opens do.
      onOpen()
      navigator.openInNewTab(shown.keptRoute())
    },
  )
}

@Composable
private fun FromTheBookRow(book: BookDto, modifier: Modifier, open: () -> Unit, keep: () -> Unit) {
  val label = "From the book ${book.title}"
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = ROW_SHAPE,
    modifier = modifier
      .fillMaxWidth()
      .clip(ROW_SHAPE)
      .openOnClick(onOpen = open, onOpenInNewTab = keep)
      .clearAndSetSemantics {
        contentDescription = label
        role = Role.Button
      },
  ) {
    Row(
      Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(menu_book, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
      Column {
        Text("From the book", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
          book.title,
          style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/** What a post's book was asked for: the post, and the sign-in and rating lock it was picked under. */
private data class Asked(val postId: String, val signedIn: Boolean, val ratingLocked: Boolean)

private val ROW_SHAPE = RoundedCornerShape(12.dp)
