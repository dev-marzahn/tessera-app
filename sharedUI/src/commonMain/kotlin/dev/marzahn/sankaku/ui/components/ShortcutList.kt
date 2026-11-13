package dev.marzahn.sankaku.ui.components

/**
 * One line of the keyboard shortcuts sheet (#521): what [action] does, and its keys. Each of [keys] is one way to do it,
 * its badges pressed in turn or together; the ways are joined by [joiner] ("or", or "then" for a key that works once
 * the one before it has opened something).
 */
data class ShortcutRow(val action: String, val keys: List<List<String>>, val joiner: String = "or")

/** A heading of the sheet ("Tabs") and its rows. */
data class ShortcutGroup(val title: String, val rows: List<ShortcutRow>)

private fun row(action: String, vararg ways: List<String>) = ShortcutRow(action, ways.toList())

private fun keys(vararg shortcuts: Shortcut) = shortcuts.map { it.label }

/**
 * Every key the desktop has, by where it works, built from [Shortcuts]' labels, so a key that changes there changes
 * here. A screen that takes a key ([Shortcuts.OpenHere] on the grid, the viewer, the reader) has its own group.
 */
val ShortcutGroups: List<ShortcutGroup> = with(Shortcuts) {
  listOf(
    ShortcutGroup(
      "Anywhere",
      listOf(
        row("Search", keys(Search), keys(Find)),
        row("Sidebar", keys(Sidebar)),
        row("Tab switcher", keys(TabSwitcher)),
        ShortcutRow("Its queue list", listOf(keys(TabSwitcher), keys(SwitcherMode)), joiner = "then"),
        row("Back", keys(Back)),
        row("Refresh", keys(Refresh), listOf("F5")),
        row("Keyboard shortcuts", keys(Help)),
      ),
    ),
    ShortcutGroup(
      "Tabs",
      listOf(
        row("Previous · next tab", keys(PreviousTab, NextTab), keys(PreviousTabCommand, NextTabCommand)),
        row("Close tab", keys(CloseTab), keys(CloseTabCommand)),
        row("Reopen closed tab", keys(ReopenTab), keys(ReopenTabCommand)),
        row("New tab", keys(NewTab)),
        row("Clear viewed", keys(ClearViewed)),
        row("Close a finished block", keys(CloseBlock)),
      ),
    ),
    ShortcutGroup(
      "Grid",
      listOf(
        row("Move the cursor", listOf("W", "A", "S", "D"), keys(Up, Previous, Down, Next)),
        row("Open the post", keys(OpenHere)),
        row("Keep it in a background tab", keys(OpenInTab)),
      ),
    ),
    ShortcutGroup(
      "Viewer",
      listOf(
        row("Previous · next post", keys(Previous, Next), listOf("A", "D")),
        row("Favourite", keys(Favourite)),
        row("Keep the post", keys(OpenInTab)),
        row("Details", keys(Details)),
        row("Tag list", keys(TagList)),
        row("Vote", keys(Vote)),
        row("Close", keys(Back), keys(CloseViewer)),
      ),
    ),
    ShortcutGroup(
      "Tag list",
      listOf(
        row("Move", listOf("W", "S"), keys(Up, Down)),
        row("Include · exclude", keys(Include, Exclude)),
        row("Remove", keys(Remove)),
        row("Search only this tag", keys(OpenHere)),
        row("Tag menu", keys(Menu)),
      ),
    ),
    ShortcutGroup(
      "Video",
      listOf(
        row("Play · pause", keys(PlayPause)),
        row("Mute", keys(Mute)),
        row("Seek and volume keys", keys(Scrub)),
        // Once V is on: A/D seek and W/S set the volume.
        ShortcutRow("Then seek · volume", listOf(listOf("A", "D"), listOf("W", "S")), joiner = "·"),
      ),
    ),
    ShortcutGroup(
      "Book reader",
      listOf(
        row("Scroll", listOf("W", "S"), keys(Up, Down)),
        row("Next · previous screen", keys(OpenHere), listOf("⇧Space")),
        row("Favourite the book", keys(Favourite)),
        row("Menu", keys(Menu)),
        row("Close", keys(Back)),
      ),
    ),
    ShortcutGroup(
      "Tab switcher",
      listOf(
        row("Move", listOf("W", "A", "S", "D")),
        row("Go", keys(OpenHere), keys(OpenInTab)),
        row("Close · close block", keys(CloseTab, CloseBlock)),
        row("Cards · queue list", keys(SwitcherMode)),
        row("Find", keys(Search)),
        row("Menu", keys(Menu)),
        row("Reopen", keys(ReopenTab)),
        row("Close", keys(Back), keys(TabSwitcher)),
      ),
    ),
    ShortcutGroup(
      "Menus",
      listOf(
        row("Move", listOf("W", "S"), keys(Up, Down)),
        row("Choose", keys(OpenHere), listOf("Enter")),
        row("Close", keys(Back)),
      ),
    ),
  )
}

/**
 * The groups with only the rows that match [query]: by what they do or where they work ("tab", "video"), or by a key
 * ("x", "⌘w"), ignoring case. A group whose title matches keeps all its rows; a group left with none goes.
 */
fun List<ShortcutGroup>.matching(query: String): List<ShortcutGroup> {
  val wanted = query.trim()
  if (wanted.isEmpty()) return this
  // One character is a key ("x"), not the inside of a word: "next" doesn't close a tab.
  val byWords = wanted.length > 1
  return mapNotNull { group ->
    if (byWords && group.title.contains(wanted, ignoreCase = true)) return@mapNotNull group
    val rows = group.rows.filter { row ->
      (byWords && row.action.contains(wanted, ignoreCase = true)) || row.keys.flatten().any { it.equals(wanted, ignoreCase = true) }
    }
    if (rows.isEmpty()) null else group.copy(rows = rows)
  }
}
