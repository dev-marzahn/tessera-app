package dev.marzahn.sankaku.features.books

import dev.marzahn.sankaku.api.dto.BookDto

/**
 * Opens [BookDto] in the reader at its saved page (#503), on its own, with no results to move between: a post's "From the book"
 * (#386). Bound in Koin to [BookReader.open]; the Books list opens its rows with their results instead (#382).
 */
fun interface BookOpener {
  fun open(book: BookDto)
}
