package dev.marzahn.sankaku.features.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.rememberUpdatedState
import com.google.fonts.icons.arrow_back
import dev.marzahn.sankaku.platformIsTouch
import dev.marzahn.sankaku.ui.components.FullScreenSearchBar
import dev.marzahn.sankaku.ui.components.FullScreenSearchState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import dev.marzahn.sankaku.api.Feed
import dev.marzahn.sankaku.ui.components.centredOnLine
import dev.marzahn.sankaku.ui.components.GroupedMenu
import dev.marzahn.sankaku.ui.components.MenuGroup
import dev.marzahn.sankaku.ui.components.AppLoadingIndicator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import dev.marzahn.sankaku.ui.components.effectsSpec
import com.google.fonts.icons.star_filled
import com.google.fonts.icons.star
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.IconButton
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.fonts.icons.close
import com.google.fonts.icons.search
import dev.marzahn.sankaku.api.dto.TagSuggestion
import dev.marzahn.sankaku.data.RecentSearches
import dev.marzahn.sankaku.data.SearchTags
import dev.marzahn.sankaku.data.TagsRepository
import dev.marzahn.sankaku.features.post.compactCount
import dev.marzahn.sankaku.ui.components.KbdBadge
import dev.marzahn.sankaku.ui.components.KeyHint
import dev.marzahn.sankaku.ui.components.PrimaryButton
import dev.marzahn.sankaku.ui.components.MenuCheck
import dev.marzahn.sankaku.ui.components.MenuChoice
import dev.marzahn.sankaku.ui.components.MenuChip
import dev.marzahn.sankaku.ui.components.Shortcuts
import dev.marzahn.sankaku.ui.components.HoverHint
import dev.marzahn.sankaku.ui.components.TagBadge
import dev.marzahn.sankaku.ui.components.TagBadgeHeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** The wait after the last keystroke before asking for suggestions. */
internal const val SUGGEST_DELAY_MS = 350L
private val BarHeight = 56.dp
private val TouchBarHeight = 48.dp

/**
 * The search at rest: the [ratings] it lets through as stacked badges (none for every rating), then one line of its
 * other tags, `+N` for those that don't fit. Tags in [known] show their name and their type's colour. A click or `/`
 * opens the [SearchPanel].
 */
@Composable
fun SearchSummary(
  tags: List<String>,
  ratings: Set<Rating>,
  known: Map<String, TagSuggestion>,
  onOpen: () -> Unit,
  modifier: Modifier = Modifier,
  /** The signed-in user's name, so their favourites tag shows as the filter it is, not as their name. */
  me: String? = null,
  /** Signed out, so the search is locked to G: TalkBack says why. */
  signedOut: Boolean = false,
) {
  // Android sizes the line as the approved shell mockup does (48 dp, a 20 dp icon) and has no key badge; the tags
  // stay badges, as on desktop.
  Surface(
    onClick = onOpen,
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier = modifier.height(if (platformIsTouch) TouchBarHeight else BarHeight),
  ) {
    Row(
      Modifier.padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(if (platformIsTouch) 10.dp else 8.dp),
    ) {
      Icon(
        search,
        contentDescription = null,
        tint = if (platformIsTouch) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current,
        modifier = if (platformIsTouch) Modifier.size(20.dp) else Modifier,
      )
      if (ratings.isNotEmpty()) {
        // As text, not a description: merged into the bar, a description would stand in for the bar's own text.
        val spoken = AnnotatedString(ratingsDescription(ratings, signedOut))
        RatingStack(ratings, size = RatingBadgeSize.Tag, modifier = Modifier.clearAndSetSemantics { text = spoken })
      }
      if (tags.isEmpty()) {
        Text(
          "Search tags",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
      } else {
        OverflowRow(
          overflow = { hidden -> SummaryChip("+$hidden", muted = true) },
          modifier = Modifier.weight(1f),
        ) { tags.forEach { SearchTag(it, known, me) } }
      }
      if (!platformIsTouch) KbdBadge(Shortcuts.Search.label)
    }
  }
}

@Composable
private fun SummaryChip(text: String, muted: Boolean = false) {
  Surface(
    shape = CircleShape,
    color = if (muted) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.secondaryContainer,
  ) {
    Text(
      text,
      style = MaterialTheme.typography.labelLarge,
      maxLines = 1,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
  }
}

/**
 * One tag of a search at rest: a filter as its chip shows it (stars, "Popularity", "Only my favourites" for [me]'s
 * favourites tag, "Favourites" for any other `fav:` tag), anything else as a [TagBadge].
 */
@Composable
private fun SearchTag(tag: String, known: Map<String, TagSuggestion>, me: String?) = SearchToken(tag, known, me)

/**
 * A token of a search as the search line shows it ([SearchTag]), with a ✕ that calls [onRemove]: the empty grid's
 * chips (#135). A rating token shows its badges. A [flagged] tag is one the site doesn't know.
 */
@Composable
internal fun RemovableSearchToken(
  tag: String,
  known: Map<String, TagSuggestion>,
  me: String?,
  onRemove: () -> Unit,
  flagged: Boolean = false,
) = SearchToken(tag, known, me, onRemove, flagged)

@Composable
private fun SearchToken(
  tag: String,
  known: Map<String, TagSuggestion>,
  me: String?,
  onRemove: (() -> Unit)? = null,
  flagged: Boolean = false,
) {
  val remove: (@Composable () -> Unit)? = onRemove?.let {
    { Icon(close, contentDescription = "Remove ${tokenName(tag, known, me)}", modifier = Modifier.size(16.dp)) }
  }
  // The own favourites tag as the chip's choice; any other (or with the name not known yet) as the tab title shows it,
  // so no user's name is on the search line.
  val favourites = Favourites.of(tag, me)?.label ?: Favourites.shortLabel(tag)
  val choice = Filter.optionFor(tag)
  // The search line shows ratings as badges beside the tags, so only the empty grid's chips get here with one.
  val ratings = Rating.of(tag)
  val label: @Composable () -> Unit = when {
    favourites != null -> { { Text(favourites, style = MaterialTheme.typography.labelLarge) } }
    ratings != null -> { { RatingStack(ratings, size = RatingBadgeSize.Small) } }
    choice == null -> return KnownTagBadge(tag, known, onClick = onRemove, trailing = remove, flagged = flagged)
    choice.first == Filter.Threshold -> { { Stars(choice.second) } }
    else -> { { Text(choice.second.label, style = MaterialTheme.typography.labelLarge) } }
  }
  // Clickable through the modifier, as TagBadge is, so the chip keeps its height rather than growing to 48 dp.
  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.secondaryContainer,
    modifier = Modifier.height(TagBadgeHeight).then(if (onRemove != null) Modifier.clickable(onClick = onRemove) else Modifier),
  ) {
    Row(
      Modifier.padding(start = 12.dp, end = if (remove == null) 12.dp else 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      label()
      remove?.invoke()
    }
  }
}

/** What a screen reader calls [tag]'s chip: its label, never the user's name in a favourites tag. */
private fun tokenName(tag: String, known: Map<String, TagSuggestion>, me: String?): String =
  Favourites.of(tag, me)?.label
    ?: Favourites.shortLabel(tag)
    ?: Rating.of(tag)?.let { ratings -> "rating " + ratings.joinToString(", ") { it.badge } }
    ?: Filter.optionFor(tag)?.second?.label
    ?: knownLabel(tag, known)

/** [tag] as its badge reads: the known name (with any `-`), else the search key as it is. */
private fun knownLabel(tag: String, known: Map<String, TagSuggestion>): String =
  known[tag.removePrefix("-").lowercase()]?.let { (if (tag.startsWith("-")) "-" else "") + it.displayName } ?: tag

/** A minimum score's stars and a "+", as its chip and the top bar show it. */
@Composable
private fun Stars(option: FilterOption) {
  val minimum = option.tag.substringAfter(':').toIntOrNull() ?: 0
  // "≥" first, centred on the stars' line, so the label reads "at least ★★★".
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    Text("≥", style = MaterialTheme.typography.labelLarge.centredOnLine())
    Row {
      repeat(minimum) { Icon(star_filled, contentDescription = null, tint = StarColor, modifier = Modifier.size(16.dp)) }
    }
  }
}

/** [TagBadge] for [tag]: its name, colour and hover hint when [known] has it, else the search key as it is. */
@Composable
private fun KnownTagBadge(
  tag: String,
  known: Map<String, TagSuggestion>,
  onClick: (() -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null,
  flagged: Boolean = false,
) {
  val found = known[tag.removePrefix("-").lowercase()]
  TagBadge(AnnotatedString(knownLabel(tag, known)), found?.category, found?.hint(), onClick = onClick, flagged = flagged, trailing = trailing)
}

/** The type and post count, and the search key when the name differs from it. */
private fun TagSuggestion.hint(): String {
  val key = tagName.removePrefix("-")
  return listOfNotNull(category.name, posts?.let { "${compactCount(it)} posts" }, key.takeIf { it != displayName })
    .joinToString(" · ")
}

/**
 * Places the last of [content]'s items that fit in a row, led by [overflow] with the number left out. The end is
 * kept because the search puts its filter tokens first, so the content tags stay in view (#89).
 */
@Composable
private fun OverflowRow(
  overflow: @Composable (hidden: Int) -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  SubcomposeLayout(modifier) { constraints ->
    val gap = 8.dp.roundToPx()
    val loose = Constraints(maxHeight = constraints.maxHeight)
    val items = subcompose("items", content).map { it.measure(loose) }
    fun width(count: Int) = items.takeLast(count).sumOf { it.width } + gap * (count - 1).coerceAtLeast(0)
    var shown = 0
    while (shown < items.size && width(shown + 1) <= constraints.maxWidth) shown++
    var badge = if (shown < items.size) subcompose("overflow-${items.size - shown}") { overflow(items.size - shown) }
      .single().measure(loose) else null
    // Make room for the badge by dropping items from the start.
    while (badge != null && shown > 0 && width(shown) + gap + badge.width > constraints.maxWidth) {
      shown--
      badge = subcompose("overflow-${items.size - shown}") { overflow(items.size - shown) }.single().measure(loose)
    }
    val visible = items.takeLast(shown)
    val height = (visible.map { it.height } + listOfNotNull(badge?.height)).maxOrNull() ?: 0
    layout(constraints.maxWidth, height) {
      var x = 0
      badge?.let {
        it.placeRelative(x, (height - it.height) / 2)
        x += it.width + gap
      }
      visible.forEach {
        it.placeRelative(x, (height - it.height) / 2)
        x += it.width + gap
      }
    }
  }
}

/** What the list under the chips shows. */
private sealed interface Entry {
  data class Tag(val suggestion: TagSuggestion) : Entry
  data class Recent(val tags: List<String>) : Entry
}

/**
 * The open search: the draft's tags as chips with the input after them, a menu per [Filter], and a list of tag
 * suggestions (or recent searches while the input is empty), with the key hints under the panel. Names are typed as
 * they read, spaces and all ("blue hair" asks for `blue_hair`). Picking a suggestion, a comma, or a pasted search makes
 * chips; nothing is searched until Enter or Search, which call [onSubmit] (adding what's typed first). [onClose] drops
 * the draft.
 */
@Composable
fun SearchPanel(
  initial: List<String>,
  signedIn: Boolean,
  safeOnly: Boolean,
  recent: RecentSearches,
  suggest: suspend (String) -> List<TagSuggestion>,
  onSubmit: (List<String>) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  known: Map<String, TagSuggestion> = emptyMap(),
  me: String? = null,
) {
  val editor = rememberSearchEditor(initial, signedIn, me, safeOnly, recent, suggest, onSubmit, highlightFirst = true)
  val tagInfo = known + editor.picked
  val recentSearches by recent.searches.collectAsStateWithLifecycle()
  val focus = remember { FocusRequester() }
  val entries = editor.entries(recentSearches)

  LaunchedEffect(Unit) {
    // After the first frame: the panel enters with an animation, and focus can't land before it's placed.
    withFrameNanos {}
    focus.requestFocus()
  }

  Column(
    modifier = modifier.onPreviewKeyEvent { event ->
      if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
      // One highlight (-1 is the input itself). ↓/↑ cycle through the rows and back to the input. Tab and Enter take
      // the highlighted row, or what's typed; Enter searches only with nothing to take, or with Cmd/Ctrl held.
      val target = entries.getOrNull(editor.highlighted)
      when (event.key) {
        Shortcuts.Back.key -> onClose()
        Key.DirectionDown -> if (entries.isNotEmpty()) {
          editor.highlighted = if (editor.highlighted >= entries.lastIndex) -1 else editor.highlighted + 1
        }
        Key.DirectionUp -> if (entries.isNotEmpty()) {
          editor.highlighted = if (editor.highlighted < 0) entries.lastIndex else editor.highlighted - 1
        }
        Key.Enter, Key.NumPadEnter -> when {
          event.isShiftPressed -> editor.addTyped()
          event.isMetaPressed || event.isCtrlPressed -> {
            if (target is Entry.Tag) editor.pick(target)
            editor.submit()
          }
          else -> editor.act(entries)
        }
        Key.Tab -> when {
          target is Entry.Tag -> editor.pick(target)
          editor.typing -> editor.addTyped()
          else -> return@onPreviewKeyEvent false
        }
        Key.Backspace -> if (!editor.removeLast()) return@onPreviewKeyEvent false
        else -> return@onPreviewKeyEvent false
      }
      true
    },
  ) {
    // One sheet like a command palette: the query, the list, then the filters and Search in a footer band.
    Surface(
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surfaceBright,
      shadowElevation = 8.dp,
    ) {
      Column {
        DraftChips(
          editor.draft,
          tagInfo,
          editor.input,
          focus,
          // The click took focus from the input, and with it the panel's keys (↵ searched no more).
          onRemove = {
            editor.remove(it)
            focus.requestFocus()
          },
          onSearch = editor::submit,
          loading = editor.loading,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SuggestionList(
          entries = entries,
          highlighted = editor.highlighted,
          typed = editor.input.text.toString(),
          status = editor.shownStatus,
          known = tagInfo,
          me = editor.draft.me,
          onPick = editor::pick,
          modifier = Modifier.padding(8.dp).heightIn(max = 360.dp),
        )
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
          FilterRow(
            editor = editor,
            signedIn = signedIn,
            safeOnly = safeOnly,
            onMenuClosed = { focus.requestFocus() },
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
          )
        }
      }
    }
    KeyHints(editor.typing, Modifier.padding(top = 12.dp))
  }
}

/**
 * The search on a touch screen (Android): M3's full-screen search bar, grown out of the top bar's search line
 * ([state]'s anchor) while [open]. Its input is the draft's chips, then the text field, on one line that scrolls
 * sideways; under it the suggestions (or recent searches), then the filters and Search in a band above the keyboard.
 * No suggestion is highlighted: a tap adds one, and the keyboard's action key adds what's typed (with nothing typed it
 * hides the keyboard); only Search, or a recent search, runs a search through [onSubmit]. Back and the arrow call
 * [onClose] and drop the draft: each opening starts again from [initial].
 */
@Composable
fun FullScreenSearch(
  state: FullScreenSearchState,
  open: Boolean,
  initial: List<String>,
  signedIn: Boolean,
  safeOnly: Boolean,
  recent: RecentSearches,
  suggest: suspend (String) -> List<TagSuggestion>,
  onSubmit: (List<String>) -> Unit,
  onClose: () -> Unit,
  known: Map<String, TagSuggestion> = emptyMap(),
  me: String? = null,
) {
  // A new draft for every opening: the closed one shows while the bar shrinks away, then makes way for a fresh one
  // (at once if the search is opened again before the bar has finished shrinking).
  var drafts by remember { mutableIntStateOf(0) }
  var stale by remember { mutableStateOf(false) }
  LaunchedEffect(open) {
    if (open) {
      if (stale) drafts++
      stale = false
    } else {
      stale = true
      snapshotFlow { state.isCollapsed }.first { it }
      drafts++
      stale = false
    }
  }
  // Also keyed on the search: while closed the screen's route can change (Back, a tag's search, another tab), and the
  // next opening starts from the new one. While open the route doesn't change; Search closes before navigating.
  // No row is highlighted (#73): with no arrow keys to move it, a highlight only confuses.
  val editor = key(drafts, initial) {
    rememberSearchEditor(initial, signedIn, me, safeOnly, recent, suggest, onSubmit, highlightFirst = false)
  }
  val tagInfo = known + editor.picked
  val recentSearches by recent.searches.collectAsStateWithLifecycle()
  val entries = editor.entries(recentSearches)
  val focus = remember { FocusRequester() }
  FullScreenSearchBar(
    state = state,
    open = open,
    onClose = onClose,
    inputField = {
      TouchSearchInput(
        editor = editor,
        known = tagInfo,
        focus = focus,
        // With something typed, act() adds the text (nothing is highlighted here), never searches.
        onAction = { hideKeyboard -> if (editor.typing) editor.act(entries) else hideKeyboard() },
        onClose = onClose,
      )
    },
  ) {
    SuggestionList(
      entries = entries,
      highlighted = editor.highlighted,
      typed = editor.input.text.toString(),
      status = editor.shownStatus,
      known = tagInfo,
      me = editor.draft.me,
      onPick = editor::pick,
      modifier = Modifier.weight(1f).padding(8.dp),
    )
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
      TouchFilterBand(editor = editor, signedIn = signedIn, safeOnly = safeOnly)
    }
  }
}

/** The full-screen search's input: back, then the draft's chips and the text field on one line that scrolls sideways. */
@Composable
private fun TouchSearchInput(
  editor: SearchEditor,
  known: Map<String, TagSuggestion>,
  focus: FocusRequester,
  onAction: (hideKeyboard: () -> Unit) -> Unit,
  onClose: () -> Unit,
) {
  val scroll = rememberScrollState()
  // The end of the line (the text field) stays in view as chips are added and text is typed.
  LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
  Row(verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClick = onClose) { Icon(arrow_back, contentDescription = "Close search") }
    Row(
      Modifier.weight(1f).horizontalScroll(scroll),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      editor.draft.chips.forEach { chip ->
        KnownTagBadge(
          chip,
          known,
          onClick = { editor.remove(chip) },
          trailing = { Icon(close, contentDescription = "Remove $chip", modifier = Modifier.size(16.dp)) },
        )
      }
      BasicTextField(
        state = editor.input,
        lineLimits = TextFieldLineLimits.SingleLine,
        // The action key adds a tag (and stays open for the next); Search runs the search.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        // Done's default action hides the keyboard.
        onKeyboardAction = { performDefaultAction -> onAction(performDefaultAction) },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorator = { field ->
          Box(contentAlignment = Alignment.CenterStart) {
            if (editor.input.text.isEmpty()) {
              Text(
                if (editor.draft.chips.isEmpty()) "Search tags" else "Add a tag",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
              )
            }
            field()
          }
        },
        modifier = Modifier.widthIn(min = 160.dp).padding(end = 16.dp).focusRequester(focus),
      )
    }
    LoadingSlot(editor.loading, Modifier.padding(end = 8.dp))
  }
  LaunchedEffect(Unit) {
    withFrameNanos {}
    focus.requestFocus()
  }
}

/** Fades in while suggestions load; its space stays, so the input doesn't jump. */
@Composable
private fun LoadingSlot(loading: Boolean, modifier: Modifier = Modifier) {
  val shown by animateFloatAsState(if (loading) 1f else 0f, effectsSpec())
  Box(modifier.size(32.dp).graphicsLayer { alpha = shown }, contentAlignment = Alignment.Center) {
    if (shown > 0f) AppLoadingIndicator(size = 32.dp)
  }
}

/**
 * The open search's state, the same for the desktop panel and Android's full-screen search: the draft, what's typed,
 * the suggestions for it and the highlighted row. Typing asks [suggest] only (after a pause); a search runs only
 * through [submit], [act] with nothing typed, or a recent search, each of which records it in [recent] and calls
 * [onSubmit].
 */
@Composable
private fun rememberSearchEditor(
  initial: List<String>,
  signedIn: Boolean,
  me: String?,
  safeOnly: Boolean,
  recent: RecentSearches,
  suggest: suspend (String) -> List<TagSuggestion>,
  onSubmit: (List<String>) -> Unit,
  highlightFirst: Boolean,
): SearchEditor {
  val locked by rememberUpdatedState(ratingLocked(signedIn, safeOnly))
  val gOnly by rememberUpdatedState(safeOnly)
  val latestSubmit by rememberUpdatedState(onSubmit)
  val latestSuggest by rememberUpdatedState(suggest)
  val editor = remember {
    SearchEditor(initial, me, highlightFirst, locked = { locked }, gOnly = { gOnly }) { tags ->
      recent.add(tags)
      latestSubmit(tags)
    }
  }
  LaunchedEffect(editor) { editor.suggestWhileTyping { latestSuggest(it) } }
  // A sign-in or restore finishing while the search is open: the same draft, read again with the user's name.
  LaunchedEffect(editor, me) { editor.withMe(me) }
  return editor
}

@Stable
private class SearchEditor(
  initial: List<String>,
  me: String?,
  /** Whether arriving suggestions highlight the first, like a combobox; only where arrow keys can move it. */
  private val highlightFirst: Boolean,
  private val locked: () -> Boolean,
  /** Whether G only is on (the sidebar switch, or the `safeOnly` debug flag). */
  private val gOnly: () -> Boolean,
  private val run: (List<String>) -> Unit,
) {
  var draft by mutableStateOf(unlocked(SearchDraft.of(initial, me)))
    private set
  val input = TextFieldState()
  private var found by mutableStateOf<List<TagSuggestion>>(emptyList())
  private var status by mutableStateOf(SuggestStatus.Idle)
  var highlighted by mutableIntStateOf(-1)

  /** Tags picked from suggestions, so their chips keep the colour even when the known tags don't have them. */
  val picked = mutableStateMapOf<String, TagSuggestion>()

  val typing: Boolean get() = input.text.isNotBlank()
  val loading: Boolean get() = typing && status == SuggestStatus.Loading
  val shownStatus: SuggestStatus get() = if (typing) status else SuggestStatus.Idle
  val isLocked: Boolean get() = locked()

  // With G only on, R15 and R18 tags aren't offered (#247); typing one in full still adds it.
  private val offered: List<TagSuggestion> get() = offeredSuggestions(found, gOnly())

  /** Suggestions while typing, else [recentSearches]. */
  fun entries(recentSearches: List<List<String>>): List<Entry> =
    if (typing) offered.filterNot { it.tagName in draft.chips }.map { Entry.Tag(it) } else recentSearches.map(Entry::Recent)

  // A locked rating is G whatever the search had, so the draft doesn't keep another one.
  private fun unlocked(draft: SearchDraft) = if (locked()) draft.withRatings(emptySet()) else draft

  /** Runs the draft, with whatever is still typed added first. */
  fun submit() {
    draft = unlocked(draft.add(asKey(input.text.toString())))
    run(draft.tags)
  }

  /** Adds what's typed as a tag, exactly (spaces as underscores), without searching. */
  fun addTyped() {
    draft = unlocked(draft.add(asKey(input.text.toString())))
    input.clearText()
    highlighted = -1
  }

  fun pick(entry: Entry) {
    when (entry) {
      is Entry.Tag -> {
        picked[entry.suggestion.tagName.removePrefix("-")] = entry.suggestion
        draft = draft.add(entry.suggestion.tagName)
        input.clearText()
        highlighted = -1
      }
      is Entry.Recent -> run(entry.tags)
    }
  }

  /** Enter, or the keyboard's action key: takes the highlighted row, else adds what's typed, else searches. */
  fun act(entries: List<Entry>) {
    val target = entries.getOrNull(highlighted)
    when {
      target != null -> pick(target)
      typing -> addTyped()
      else -> submit()
    }
  }

  fun remove(tag: String) {
    draft = draft.remove(tag)
  }

  fun withRatings(ratings: Set<Rating>) {
    draft = draft.withRatings(ratings)
  }

  fun with(filter: Filter, option: FilterOption?) {
    draft = draft.with(filter, option)
  }

  fun withFavourites(favourites: Favourites?) {
    draft = draft.withFavourites(favourites)
  }

  /** Keeps the draft when the user's name changes, so their `fav:` tag becomes (or stops being) the filter. */
  fun withMe(me: String?) {
    if (me != draft.me) draft = unlocked(draft.withMe(me))
  }

  /** Backspace in an empty input: removes the last chip; false when there's nothing to remove that way. */
  fun removeLast(): Boolean {
    if (input.text.isNotEmpty() || draft.chips.isEmpty()) return false
    draft = draft.remove(draft.chips.last())
    return true
  }

  suspend fun suggestWhileTyping(suggest: suspend (String) -> List<TagSuggestion>) {
    var previous = ""
    snapshotFlow { input.text.toString() }.distinctUntilChanged().collectLatest { text ->
      highlighted = -1
      val grew = text.length - previous.length
      previous = text
      // A paste or a comma empties the input; suggestions loading for the text before no longer apply.
      fun cleared() {
        found = emptyList()
        status = SuggestStatus.Idle
      }
      // A paste of a whole search (several characters at once, with spaces) splits into tags, as the site writes
      // searches; a typed space stays, since names are typed as they read ("blue hair").
      if (grew > 1 && text.any(Char::isWhitespace)) {
        draft = draft.add(text)
        input.clearText()
        cleared()
        return@collectLatest
      }
      // A comma ends a tag as typed.
      val end = text.lastIndexOf(',')
      if (end >= 0) {
        text.substring(0, end).split(',').forEach { draft = draft.add(asKey(it)) }
        input.edit { replace(0, end + 1, "") }
        cleared()
        return@collectLatest
      }
      val key = asKey(text)
      if (key.removePrefix("-").length < TagsRepository.MIN_PREFIX) {
        cleared()
        return@collectLatest
      }
      status = SuggestStatus.Loading
      delay(SUGGEST_DELAY_MS)
      status = try {
        found = suggest(key)
        // On desktop the top suggestion is highlighted, so Tab and Enter take it.
        highlighted = if (highlightFirst && offered.any { it.tagName !in draft.chips }) 0 else -1
        if (offered.isEmpty()) SuggestStatus.NoMatch else SuggestStatus.Idle
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        found = emptyList()
        SuggestStatus.Failed
      }
    }
  }
}

private enum class SuggestStatus { Idle, Loading, NoMatch, Failed }

/** The suggestions the panel lists: all of [found], or with [gOnly] on all but the tags rated R15 or R18. */
internal fun offeredSuggestions(found: List<TagSuggestion>, gOnly: Boolean): List<TagSuggestion> =
  if (gOnly) found.filterNot { Rating.ofCode(it.rating) in NOT_G } else found

private val NOT_G = setOf(Rating.R15, Rating.R18)

private val SPACES = Regex("\\s+")

/** What was typed as the tag's search key: trimmed, with spaces as underscores ("blue hair" is `blue_hair`). */
internal fun asKey(typed: String): String = typed.trim().replace(SPACES, "_")


@Composable
private fun DraftChips(
  draft: SearchDraft,
  known: Map<String, TagSuggestion>,
  input: TextFieldState,
  focus: FocusRequester,
  onRemove: (String) -> Unit,
  onSearch: () -> Unit,
  loading: Boolean,
  modifier: Modifier = Modifier,
) {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    itemVerticalAlignment = Alignment.CenterVertically,
    modifier = modifier,
  ) {
    Icon(
      search,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(end = 4.dp),
    )
    draft.chips.forEach { chip ->
      KnownTagBadge(
        chip,
        known,
        onClick = { onRemove(chip) },
        trailing = { Icon(close, contentDescription = "Remove $chip", modifier = Modifier.size(16.dp)) },
      )
    }
    BasicTextField(
      state = input,
      lineLimits = TextFieldLineLimits.SingleLine,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      onKeyboardAction = { onSearch() },
      textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
      cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
      decorator = { field ->
        Box(contentAlignment = Alignment.CenterStart) {
          if (input.text.isEmpty()) {
            Text(
              if (draft.chips.isEmpty()) "Search tags" else "Add a tag",
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          field()
        }
      },
      modifier = Modifier.weight(1f).widthIn(min = 160.dp).height(32.dp).focusRequester(focus),
    )
    // At the end of the input's line.
    LoadingSlot(loading)
  }
}

/** The desktop panel's footer: the [FilterChips] wrapping as the window allows, and the Search button at the end. */
@Composable
private fun FilterRow(
  editor: SearchEditor,
  signedIn: Boolean,
  safeOnly: Boolean,
  onMenuClosed: () -> Unit,
  modifier: Modifier = Modifier,
) {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    itemVerticalAlignment = Alignment.CenterVertically,
    modifier = modifier.fillMaxWidth(),
  ) {
    FilterChips(editor, signedIn, safeOnly, onMenuClosed)
    Spacer(Modifier.weight(1f))
    PrimaryButton("Search", onClick = editor::submit)
  }
}

/**
 * The full-screen search's band above the keyboard (#73): the [FilterChips] on one line that scrolls sideways when
 * they don't fit, then Search across the width. On a phone they wrapped unevenly beside the button.
 */
@Composable
private fun TouchFilterBand(editor: SearchEditor, signedIn: Boolean, safeOnly: Boolean) {
  Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      // The padding scrolls with the chips, so they run to the screen's edge.
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FilterChips(editor, signedIn, safeOnly, onMenuClosed = {})
    }
    PrimaryButton("Search", onClick = editor::submit, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth())
  }
}

/**
 * The Rating menu (fixed to G with the reason when it's locked), a menu chip per [Filter], and after Feed the
 * [Favourites] menu. Signed out, the Feed menu leaves out Following, which the API ignores without a session, and the
 * Favourites menu is left out: there are no own favourites to filter by.
 */
@Composable
private fun FilterChips(editor: SearchEditor, signedIn: Boolean, safeOnly: Boolean, onMenuClosed: () -> Unit) {
  val draft = editor.draft
  val lockedRating = when {
    !editor.isLocked -> null
    // Signed out comes first: the sidebar's switch is only there signed in, and the lock holds either way.
    !signedIn -> "Signed out, searches show G only. Sign in to choose other ratings."
    // The sidebar's switch, or the debug `safeOnly` launch flag, which holds the same lock for a run.
    else -> "G only is on. Turn it off in the sidebar to choose other ratings."
  }
  val onChoose = { filter: Filter, option: FilterOption? ->
    editor.with(filter, option)
    onMenuClosed()
  }
  if (lockedRating != null) {
    LockedRating(why = lockedRating, reason = if (safeOnly && signedIn) "Rating locked to G · G only is on" else null)
  } else {
    RatingMenu(draft.ratings, editor::withRatings, onMenuClosed)
  }
  Filter.entries.forEach { filter ->
    when (filter) {
      Filter.Threshold -> ScoreMenu(draft.filters[filter], onChoose = { onChoose(filter, it) })
      Filter.Feed -> {
        FilterMenu(
          filter,
          draft.filters[filter],
          options = filter.options.filter { signedIn || it.tag != Feed.Following.tag },
          onChoose = { onChoose(filter, it) },
        )
        // The user's name is there only while signed in.
        if (draft.me != null) {
          ChoiceMenu(
            label = Favourites.LABEL,
            anyLabel = Favourites.ANY_LABEL,
            chosen = draft.favourites,
            options = Favourites.entries,
            optionLabel = Favourites::label,
            onChoose = {
              editor.withFavourites(it)
              onMenuClosed()
            },
          )
        }
      }
      else -> FilterMenu(filter, draft.filters[filter], onChoose = { onChoose(filter, it) })
    }
  }
}

/**
 * "Rating" until some are ticked, then their stacked badges. The menu ticks G, R15 and R18 on and off without closing;
 * ticking all three, or "All ratings", means every rating.
 */
@Composable
private fun RatingMenu(ratings: Set<Rating>, onRatings: (Set<Rating>) -> Unit, onClosed: () -> Unit) {
  var open by remember { mutableStateOf(false) }
  fun close() {
    open = false
    onClosed()
  }
  Box {
    MenuChip(
      selected = ratings.isNotEmpty(),
      onClick = { open = true },
      label = {
        if (ratings.isEmpty()) Text("Rating") else RatingStack(ratings)
      },
    )
    GroupedMenu(expanded = open, onDismissRequest = ::close) {
      MenuGroup(0, 2) {
        MenuChoice(
          selected = ratings.isEmpty(),
          onClick = {
            onRatings(emptySet())
            close()
          },
        ) { Text("All ratings") }
      }
      MenuGroup(1, 2) {
        Rating.entries.forEach { rating ->
          MenuCheck(
            checked = rating in ratings,
            onCheckedChange = { onRatings(if (it) ratings + rating else ratings - rating) },
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              RatingBadge(rating)
              Text(rating.label)
            }
          }
        }
      }
    }
  }
}

private val StarColor = Color(0xFFE8A200)

/**
 * Minimum score (`threshold:1`–`5`) as stars: the chip shows the stars chosen, the menu a row of five where hovering
 * previews and a click picks; "Any score" clears it.
 */
@Composable
private fun ScoreMenu(chosen: FilterOption?, onChoose: (FilterOption?) -> Unit) {
  val minimum = chosen?.tag?.substringAfter(':')?.toIntOrNull()
  var open by remember { mutableStateOf(false) }
  Box {
    MenuChip(
      selected = minimum != null,
      onClick = { open = true },
      label = {
        if (chosen == null) Text(Filter.Threshold.label) else Stars(chosen)
      },
      modifier = Modifier.semantics { contentDescription = minimum?.let { "Minimum score $it" } ?: "Minimum score" },
    )
    GroupedMenu(expanded = open, onDismissRequest = { open = false }) {
      MenuGroup(0, 2) {
        MenuChoice(
          selected = minimum == null,
          onClick = {
            open = false
            onChoose(null)
          },
        ) { Text(Filter.Threshold.anyLabel) }
      }
      MenuGroup(1, 2) {
        StarPicker(minimum ?: 0) { stars ->
          open = false
          onChoose(Filter.Threshold.optionFor("threshold:$stars"))
        }
      }
    }
  }
}

private val StarCell = 40.dp

/**
 * Five stars in a row of touching cells: the hovered star and those before it light up, from wherever the pointer is
 * in the row, so moving between stars never drops the preview. A click picks that many.
 */
@Composable
private fun StarPicker(chosen: Int, onPick: (Int) -> Unit) {
  var hovered by remember { mutableIntStateOf(0) }
  val shown = if (hovered > 0) hovered else chosen
  Row(
    Modifier
      .padding(horizontal = 12.dp, vertical = 4.dp)
      .pointerInput(Unit) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent()
            val x = event.changes.firstOrNull()?.position?.x ?: continue
            hovered = if (event.type == PointerEventType.Exit) 0 else (x / StarCell.toPx()).toInt().coerceIn(0, 4) + 1
          }
        }
      },
  ) {
    for (stars in 1..5) {
      Box(
        Modifier.size(StarCell).clip(CircleShape).clickable { onPick(stars) },
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          if (stars <= shown) star_filled else star,
          contentDescription = "At least $stars",
          tint = if (stars <= shown) StarColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** A chip named after [filter] until something is [chosen], then after the choice; a click lists the options. */
@Composable
private fun FilterMenu(
  filter: Filter,
  chosen: FilterOption?,
  onChoose: (FilterOption?) -> Unit,
  options: List<FilterOption> = filter.options,
) {
  ChoiceMenu(filter.label, filter.anyLabel, chosen, options, FilterOption::label, onChoose)
}

/**
 * A chip named [label] until something is [chosen], then after the choice; a click lists [anyLabel] (the default, no
 * choice) in a group of its own, then [options].
 */
@Composable
private fun <T> ChoiceMenu(
  label: String,
  anyLabel: String,
  chosen: T?,
  options: List<T>,
  optionLabel: (T) -> String,
  onChoose: (T?) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    MenuChip(selected = chosen != null, onClick = { open = true }, label = { Text(chosen?.let(optionLabel) ?: label) })
    GroupedMenu(expanded = open, onDismissRequest = { open = false }) {
      // The API's default first, in a group of its own.
      MenuGroup(0, 2) {
        MenuChoice(
          selected = chosen == null,
          onClick = {
            open = false
            onChoose(null)
          },
        ) { Text(anyLabel) }
      }
      MenuGroup(1, 2) {
        options.forEach { option ->
          MenuChoice(
            selected = option == chosen,
            onClick = {
              open = false
              onChoose(option)
            },
          ) { Text(optionLabel(option)) }
        }
      }
    }
  }
}

/**
 * The rating badges as TalkBack says them, rather than their letters: "Safe only" for G alone, with "signed out" when
 * that's why ([signedOut]); else "Ratings R15, R18".
 */
internal fun ratingsDescription(ratings: Set<Rating>, signedOut: Boolean): String = when {
  ratings == setOf(Rating.G) -> if (signedOut) "Safe only, signed out" else "Safe only"
  else -> "Ratings " + Rating.entries.filter { it in ratings }.joinToString(", ") { it.badge }
}

/**
 * The Rating chip fixed to G: selected and disabled, with [why] on hover. [reason] is the short version, written
 * beside it for a touch screen, which has no hover.
 */
@Composable
private fun LockedRating(why: String, reason: String? = null) {
  HoverHint(why) {
    MenuChip(
      selected = true,
      onClick = {},
      enabled = false,
      label = { RatingStack(setOf(Rating.G)) },
    )
  }
  if (reason != null) {
    Text(reason, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun SuggestionList(
  entries: List<Entry>,
  highlighted: Int,
  typed: String,
  status: SuggestStatus,
  known: Map<String, TagSuggestion>,
  me: String?,
  onPick: (Entry) -> Unit,
  modifier: Modifier = Modifier,
) {
  val hint = when {
    status == SuggestStatus.Failed ->
      if (platformIsTouch) "Suggestions didn't load. Search still works." else "Suggestions didn't load. Enter still searches."
    status == SuggestStatus.NoMatch -> "No tags match “$typed”."
    entries.isNotEmpty() -> null
    typed.isBlank() -> "Type 2 or more letters for suggestions. Start with - to exclude a tag."
    status == SuggestStatus.Loading -> null
    else -> "Type 2 or more letters for suggestions."
  }
  Column(modifier.verticalScroll(rememberScrollState())) {
    if (entries.firstOrNull() is Entry.Recent) {
      Text(
        "Recent searches",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
      )
    }
    // With ↑ back on the input while suggestions show, say what Enter does there. A touch screen has no ↑.
    if (!platformIsTouch && typed.isNotBlank() && highlighted < 0 && entries.firstOrNull() is Entry.Tag) {
      Row(
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        KbdBadge("↵")
        Text(
          "adds “${typed.trim()}” as typed",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    entries.forEachIndexed { index, entry ->
      EntryRow(entry, typed, known, highlighted = index == highlighted, me = me, onClick = { onPick(entry) })
    }
    hint?.let {
      Text(
        it,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      )
    }
  }
}

@Composable
private fun EntryRow(
  entry: Entry,
  typed: String,
  known: Map<String, TagSuggestion>,
  highlighted: Boolean,
  me: String?,
  onClick: () -> Unit,
) {
  // Only the highlight is drawn; other rows show the panel (or the full-screen bar) behind them.
  val rowColor = if (highlighted) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent
  Row(
    Modifier
      .fillMaxWidth()
      .clip(MaterialTheme.shapes.medium)
      .background(rowColor)
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    when (entry) {
      is Entry.Recent -> {
        val ratings = Rating.inTags(entry.tags)
        if (ratings.isNotEmpty()) RatingStack(ratings, size = RatingBadgeSize.Tag)
        OverflowRow(
          overflow = { hidden -> SummaryChip("+$hidden", muted = true) },
          modifier = Modifier.weight(1f),
        ) { SearchTags.filtersFirst(entry.tags).filterNot(Rating::isRatingTag).forEach { SearchTag(it, known, me) } }
        if (highlighted && !platformIsTouch) KbdBadge("↵")
      }
      is Entry.Tag -> {
        val tag = entry.suggestion
        Box(Modifier.weight(1f)) {
          val name = (if (tag.tagName.startsWith("-")) "-" else "") + tag.displayName
          TagBadge(matched(name, typed), tag.category, tag.hint())
        }
        if (highlighted && !platformIsTouch) {
          KbdBadge("Tab")
          KbdBadge("↵")
        }
        Rating.ofCode(tag.rating)?.let { RatingBadge(it) }
        tag.posts?.let {
          Text(
            compactCount(it),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 40.dp),
          )
        }
      }
    }
  }
}

/**
 * [name] with the typed prefix in bold and the rest in normal weight. The name is the display name ("Blue Hair") and
 * the typed text a search key ("blue_ha"), so an underscore matches a space.
 */
private fun matched(name: String, typed: String): AnnotatedString = buildAnnotatedString {
  fun loose(text: String) = text.lowercase().replace('_', ' ')
  val length = if (typed.isNotEmpty() && loose(name).startsWith(loose(typed))) typed.length else 0
  withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(name.take(length)) }
  withStyle(SpanStyle(fontWeight = FontWeight.Normal)) { append(name.drop(length)) }
}

/** White, since the hints sit on the scrim, which is black in both themes. They wrap in a narrow window. */
@Composable
private fun KeyHints(typing: Boolean, modifier: Modifier = Modifier) {
  // While typing, Enter adds; with an empty input it searches.
  val hints = if (typing) {
    listOf(
      "↑↓" to "choose",
      "Tab ↵" to "add",
      "⌘↵" to "search",
      "," to "add as typed",
      Shortcuts.Back.label to "close",
    )
  } else {
    listOf("↑↓" to "choose", "↵" to "search", Shortcuts.Back.label to "close")
  }
  FlowRow(
    modifier.padding(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    for ((key, label) in hints) KeyHint(key, label, color = Color.White.copy(alpha = 0.92f))
  }
}
