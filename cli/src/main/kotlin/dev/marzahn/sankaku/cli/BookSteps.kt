package dev.marzahn.sankaku.cli

import dev.marzahn.sankaku.api.API_BASE
import dev.marzahn.sankaku.api.BookFavouriteResult
import dev.marzahn.sankaku.api.BooksApi
import dev.marzahn.sankaku.api.HeaderSet
import dev.marzahn.sankaku.api.RawResponse
import dev.marzahn.sankaku.api.dto.BookDto
import dev.marzahn.sankaku.api.probe
import dev.marzahn.sankaku.data.BookPlace
import dev.marzahn.sankaku.data.InMemoryTextFile
import dev.marzahn.sankaku.data.ReadingProgress
import dev.marzahn.sankaku.net.ConnectivityState
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The M3 Books gate (#379; b8 #386, b9 #384): one `b` step per [BooksApi] call, on the app's own code, each recorded in
 * `docs/api-recorded.json` by its marker; then discovery steps 19 to 21 through [probe]. Writes are undone in the same step.
 * Notes and markers hold counts and yes/no only: no id, name or URL.
 */
internal class BookSteps(private val smoke: SmokeSteps) {
  private val books = BooksApi(smoke.mainClient)
  private val android = "Android" in smoke.fingerprint.userAgent

  /** b2's pick from the signed-in list, which carries no pages. */
  private var listed: BookDto? = null

  /** The same book with its pages, set only once b3 has them; the steps that write build on it. */
  private var book: BookDto? = null
  private var seriesId: String? = null

  /** Before sign-in: what a signed-out user gets (#377: `rating:s` returns only G books). */
  suspend fun signedOut() {
    booksStep("b1 books signed out (rating:s)", "b1-books-signed-out") {
      val list = books.list(listOf("order:popularity", "rating:s"))
      val pools = list.pools.data
      if (pools.isEmpty()) fail("no books")
      val ratings = pools.mapNotNull { it.rating }.toSortedSet()
      if (ratings != sortedSetOf("s")) fail("rating:s let through $ratings")
      val open = pools.firstOrNull { !it.redirectToSignup && it.status == "active" } ?: fail("every book is hidden")
      val pages = books.pages(open.id).posts
      if (pages.isEmpty()) fail("a G book has no pages signed out")
      val first = books.firstPages(open.id)
      val series = list.series.data.firstOrNull()?.let { books.series(it.id) }
      val marker = "ratings=$ratings, pages=${yesNo(pages.isNotEmpty())}, page ratings=${pages.map { it.rating }.toSortedSet()}, " +
        "first pages=${first.size}, series in the list=${yesNo(list.series.data.isNotEmpty())}, " +
        "their firstPool=${yesNo(list.series.data.any { it.firstPool != null })}, a series' books=${series?.books?.size}"
      val detail = "books=${pools.size}, hidden (redirect_to_signup)=${pools.count { it.redirectToSignup }}, " +
        "pages of the first open book=${pages.size} of ${open.postCount}"
      marker to detail
    }
  }

  /** After sign-in. */
  suspend fun run() {
    val signedIn = smoke.session.isSignedIn.value
    val noSession = "not signed in"
    booksStep("b2 books list (BooksApi.list)", "b2-books-list", requires = Unit.takeIf { signedIn }, noSession) {
      val first = books.list(listOf("order:popularity", "rating:s"))
      val second = books.list(listOf("order:popularity", "rating:s"), page = 2)
      if (first.pools.data.isEmpty() || second.pools.data.isEmpty()) fail("an empty page")
      listed = first.pools.data.firstOrNull { it.status == "active" && it.postCount > 1 }
      seriesId = first.series.data.firstOrNull()?.id
      val repeated = second.pools.data.count { b -> first.pools.data.any { it.id == b.id } }
      val marker = "page 1: books=${yesNo(first.pools.data.isNotEmpty())}, series=${yesNo(first.series.data.isNotEmpty())}, " +
        "next=${yesNo(first.pools.meta.next != null)}; page 2: books=${yesNo(second.pools.data.isNotEmpty())}, " +
        "series=${yesNo(second.series.data.isNotEmpty())}; covers with sample_url=${yesNo(first.pools.data.all { it.coverPost?.sampleUrl != null })}"
      marker to "page 1 books=${first.pools.data.size}, series=${first.series.data.size}; page 2 books=${second.pools.data.size}, " +
        "also on page 1=$repeated"
    }
    booksStep("b3 book pages (BooksApi.pages)", "b3-book-pages", requires = listed, "needs a book from b2") { b ->
      val full = books.pages(b.id)
      val pages = full.posts
      if (pages.isEmpty()) fail("no pages")
      val marker = "pages=${yesNo(pages.isNotEmpty())}, all with sample_url=${yesNo(pages.all { it.sampleUrl != null })}, " +
        "all with file_url=${yesNo(pages.all { it.fileUrl != null })}, fav_count>0: ${yesNo(full.favCount > 0)}"
      book = full
      marker to "pages=${pages.size} of post_count ${full.postCount}, reading saved=${yesNo(full.reading != null)}"
    }
    booksStep("b4 first pages in order (BooksApi.firstPages)", "b4-first-pages", requires = Unit.takeIf { signedIn }, noSession) {
      val check = firstPagesInOrder()
      if (check.inOrder != check.books) fail("first pages out of order for ${check.books - check.inOrder} of ${check.books} books")
      if (check.bySequence != check.books) fail("pages not in sequence order for ${check.books - check.bySequence} of ${check.books} books")
      "every book's first ${BooksApi.FIRST_PAGES} pages in order=yes, pages in sequence order from 0=yes, " +
        "a book with fewer pages than post_count among them=${yesNo(check.short > 0)}" to
        "books=${check.books}, of them with fewer pages than post_count=${check.short}"
    }
    booksStep("b5 series (BooksApi.series)", "b5-series", requires = seriesId, "needs a series from b2") { id ->
      val series = books.series(id)
      val order = series.books.map { it.sequence }
      if (series.books.isEmpty()) fail("no books")
      if (order != order.sortedBy { it }) fail("books out of series order")
      val marker = "books=${yesNo(series.books.isNotEmpty())}, all of pool_count=${yesNo(series.books.size == series.poolCount)}, " +
        "in sequence order from 1=${yesNo(order.firstOrNull() == 1)}, covers with sample_url=${yesNo(series.books.all { it.coverPost?.sampleUrl != null })}"
      marker to "books=${series.books.size}, ratings=${series.books.mapNotNull { it.rating }.toSortedSet()}"
    }
    val readable = book?.takeIf { it.reading == null && it.posts.isNotEmpty() && signedIn }
    booksStep("b6 save reading, then clear it", "b6-reading", requires = readable, "needs a book from b3 the account hasn't opened") { b ->
      val target = minOf(2, b.posts.size - 1)
      // The write is inside, so a save that fails after the server applied it is cleared too.
      val saved = undoing({ books.clearReading(b.id) }) {
        books.saveReading(b.id, target)
        books.pages(b.id).reading
      }
      val cleared = books.pages(b.id).reading
      if (cleared != null) fail("still saved: clear it by hand")
      if (saved?.sequence != target) fail("saved sequence ${saved?.sequence}, not $target")
      "saved: sequence=yes, current_page=sequence+1: ${yesNo(saved.currentPage == target + 1)}; cleared=yes" to ""
    }
    // #384: the app's own ReadingProgress saves the place, and the book as the website reads it says the same page.
    booksStep("b9 reading progress as the app saves it (ReadingProgress)", "b9-reading-progress", requires = readable, "needs a book from b3 the account hasn't opened") { b ->
      appProgress(b)
    }
    val favouritable = book?.takeIf { !it.isFavorited && signedIn && smoke.env["SANKAKU_TRY_FAVOURITE"] != "false" }
    booksStep("b7 favourite a book, then unfavourite it", "b7-favourite", requires = favouritable, "needs a book from b3 that isn't a favourite") { b ->
      var favourited: BookFavouriteResult? = null
      val during = undoing({ books.favourite(b.id, on = false) }) {
        favourited = books.favourite(b.id, on = true)
        books.pages(b.id)
      }
      val after = books.pages(b.id)
      if (after.isFavorited) fail("still a favourite: unfavourite it by hand")
      if (!during.isFavorited) fail("not a favourite after the favourite")
      "is_favorited during=yes, after=no; fav_count in the response=${yesNo(favourited?.favCount != null)}, " +
        "one more=${yesNo(favourited?.favCount == b.favCount + 1)}" to ""
    }
    // A post's book (#386), the web app's way (#475): either form of id.
    val page = book?.posts?.getOrNull(1)
    booksStep("b8 a page's book (BooksApi.booksOf)", "b8-books-of", requires = page, "needs a book's page from b3") { p ->
      val found = books.booksOf(p.id)
      if (book?.id !in found.map { it.id }) fail("the page's book isn't among its books")
      "in_visible_pool on the page=${yesNo(p.inVisiblePool)}, the book among its books=yes, " +
        "every book named=${yesNo(found.all { it.title.isNotBlank() })}" to "books=${found.size}"
    }
    discovery()
  }

  /**
   * b9: [ReadingProgress.save] (signed in, so sent with `PUT /pools/{id}/reading`) for a middle page, then the last,
   * each read back from `/pools/{id}` (what the website's book page reads), and the place read back as the app reads
   * it ([ReadingProgress.savedOf]). Cleared in the step, as b6 is.
   */
  private suspend fun appProgress(b: BookDto): Pair<String, String> = coroutineScope {
    val job = SupervisorJob(coroutineContext[Job])
    // Its own connectivity, which never asks for a retry: the step sends each save once.
    val progress = ReadingProgress(books, smoke.session, ConnectivityState(), InMemoryTextFile(), CoroutineScope(coroutineContext + job))
    // The PUTs answered so far: each save is sent in the background, as the app does, so the step waits for its
    // answer, and the undo waits for every save's, so none can land after the clear.
    fun puts() = smoke.log.entries.value.filter { it.method == "PUT" && it.pathTemplate == "/pools/{id}/reading" }
    // By entry, not by count: the log keeps its last 200 only.
    val earlier = puts().toSet()
    fun answered() = puts().filterNot { it in earlier }
    var saves = 0
    suspend fun awaitAnswers() {
      // Not withTimeout: its cancellation would skip the undo.
      repeat(PUT_WAITS) {
        if (answered().size >= saves) return
        delay(PUT_WAIT_MS)
      }
    }
    try {
      undoing({ awaitAnswers(); books.clearReading(b.id) }) {
        val pages = b.posts.sortedBy { it.sequence ?: Int.MAX_VALUE }
        suspend fun saveAt(index: Int): BookDto {
          val sequence = pages[index].sequence ?: index
          progress.save(b.id, BookPlace(sequence, index + 1, pages.size))
          saves++
          awaitAnswers()
          val put = answered().getOrNull(saves - 1) ?: fail("the PUT got no answer in ${PUT_WAITS * PUT_WAIT_MS / 1000} s")
          if (put.status != 200) fail("the PUT answered ${put.outcome}")
          val read = books.pages(b.id)
          if (read.reading?.sequence != sequence) fail("the book shows sequence ${read.reading?.sequence}, not the one saved")
          return read
        }
        val middle = pages.size / 2
        val atMiddle = saveAt(middle)
        val shownMiddle = progress.savedOf(atMiddle)
        val atLast = saveAt(pages.size - 1)
        val lastPercent = atLast.reading?.percent
        val marker = "middle page: website's current_page=page: ${yesNo(atMiddle.reading?.currentPage == (pages[middle].sequence ?: middle) + 1)}, " +
          "the app reads the same page: ${yesNo(shownMiddle?.page == middle + 1)}; last page: percent=100: ${yesNo(lastPercent == 100.0)}, " +
          "the app marks it Read: ${yesNo(progress.savedOf(atLast)?.finished == true)}"
        marker to "pages=${pages.size} of post_count ${b.postCount}, middle percent=${atMiddle.reading?.percent}, last percent=$lastPercent"
      }.also {
        if (books.pages(b.id).reading != null) fail("still saved: clear it by hand")
      }
    } finally {
      job.cancel()
    }
  }

  /** What b4 counted: books checked, of them in order by md5 and by `sequence`, and with fewer pages than `post_count`. */
  private class FirstPagesCheck(val books: Int, val inOrder: Int, val bySequence: Int, val short: Int)

  /**
   * b4: several books' [BooksApi.firstPages] against their pages' first 3 by md5, books with pages gone included.
   * The pages are read raw, as [BooksApi.pages] doesn't decode `sequence`, to check the array is in `sequence` order.
   */
  private suspend fun firstPagesInOrder(): FirstPagesCheck {
    val bearer = smoke.session.currentBearerTokens()?.accessToken
    val list = books.list(listOf("order:date", "rating:s"), limit = 40).pools.data.filter { it.status == "active" }
    // Larger books more often have fewer pages than their post_count, the nearest to deleted pages the account sees.
    val candidates = (list.filter { it.postCount >= 30 }.take(3) + list.filter { it.postCount in 2..29 }.take(3)).distinctBy { it.id }
    var inOrder = 0
    var bySequence = 0
    var short = 0
    for (b in candidates) {
      val raw = smoke.authClient.probe(
        HttpMethod.Get, API_BASE, "/pools/${b.id}", HeaderSet.POSTS, bearer = bearer,
        query = mapOf("lang" to "en", "exceptStatuses[]" to "deleted"),
      )
      val pages = ((raw.json as? JsonObject)?.get("posts") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
      if (pages.size < b.postCount) short++
      if (pages.map { it.str("sequence")?.toIntOrNull() } == pages.indices.toList()) bySequence++
      if (books.firstPages(b.id).map { it.md5 } == pages.take(BooksApi.FIRST_PAGES).map { it.str("md5") }) inOrder++
    }
    return FirstPagesCheck(candidates.size, inOrder, bySequence, short)
  }

  private suspend fun discovery() {
    val bearer = smoke.session.currentBearerTokens()?.accessToken
    val reason = "not signed in"

    // Paging: `page` against the `pools.meta.next` cursor, on order:date (popularity moves between calls).
    smoke.discover("d19 books paging (page or next)", "d19-books-paging", requires = bearer, reason, showHint = false) { token ->
      suspend fun page(extra: Map<String, String>) = smoke.authClient.probe(
        HttpMethod.Get, API_BASE, "/poolseriesv2", HeaderSet.POSTS, bearer = token,
        query = mapOf(
          "lang" to "en", "limit" to "10", "tags" to "order:date rating:s", "filledPools" to "true",
          "includes[]" to "pools", "exceptStatuses[]" to "deleted",
        ) + extra,
      )
      val first = page(mapOf("page" to "1"))
      val next = (first.pools()?.get("meta") as? JsonObject).str("next")
      val byPage = page(mapOf("page" to "2"))
      val byNext = next?.let { page(mapOf("next" to it)) } ?: fail("no next")
      byPage to "page 2 = next's books: ${yesNo(byPage.bookIds() == byNext.bookIds())}, " +
        "series on page 2: ${yesNo(byPage.seriesCount() > 0)}, with next alone: ${yesNo(byNext.seriesCount() > 0)}, " +
        "next alone answers page: ${byNext.json.str("page")}"
    }

    // Header sets: what V2 strips from the reads (the app sends POSTS).
    val bookId = book?.id
    smoke.discover("d19 first pages with V2", "d19-first-pages-v2", requires = bookId, "needs a book from b3", showHint = false) { id ->
      val raw = smoke.authClient.probe(
        HttpMethod.Get, API_BASE, "/v2/posts/keyset", HeaderSet.V2, bearer = bearer,
        query = mapOf("lang" to "en", "limit" to "3", "tags" to "pool:$id"),
      )
      val data = (raw.json as? JsonObject)?.get("data") as? JsonArray
      raw to "posts>0: ${yesNo(!data.isNullOrEmpty())}, sample_url=${yesNo(data.orEmpty().all { it.str("sample_url") != null })}, " +
        "file_url=${yesNo(data.orEmpty().all { it.str("file_url") != null })}"
    }
    smoke.discover("d19 series with V2", "d19-series-v2", requires = seriesId, "needs a series from b2", showHint = false) { id ->
      val raw = smoke.authClient.probe(
        HttpMethod.Post, API_BASE, "/graphql", HeaderSet.V2, bearer = bearer, query = mapOf("lang" to "en"),
        body = buildJsonObject {
          put("query", "query S(\$id: StringOrInt!) { seriesV2(id: \$id) { pools { coverPost { previewUrl sampleUrl } } } }")
          putJsonObject("variables") { put("id", id) }
        },
      )
      val pools = (((raw.json as? JsonObject)?.get("data") as? JsonObject)?.get("seriesV2") as? JsonObject)?.get("pools") as? JsonArray
      val covers = pools.orEmpty().map { (it as? JsonObject)?.get("coverPost") }
      raw to "books>0: ${yesNo(!pools.isNullOrEmpty())}, preview_url=${yesNo(covers.all { it.str("previewUrl") != null })}, " +
        "sample_url=${yesNo(covers.all { it.str("sampleUrl") != null })}"
    }

    // #475: Sankaku leaves pages out of a book for an Android User-Agent, depending on the account (one owner's book: 6
    // pages as desktop, 5 as Android; the test account saw no difference). The same books, by name, as this run's
    // fingerprint and as Android Chrome, paired by their cover's md5 (the ids differ): how many come back shorter as
    // Android, and how many of the list's books Android doesn't get at all. Counts only.
    smoke.discover("d23 a book's pages as Android Chrome", "d23-android-pages", requires = bearer, reason, showHint = false) { token ->
      suspend fun books(client: io.ktor.client.HttpClient): List<JsonObject> {
        val raw = client.probe(
          HttpMethod.Get, API_BASE, "/poolseriesv2", HeaderSet.POSTS, bearer = token,
          query = mapOf("lang" to "en", "limit" to "40", "page" to "1", "tags" to "order:popularity", "includes[]" to "pools"),
        )
        return (raw.pools()?.get("data") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
      }
      fun JsonObject.cover() = (this["cover_post"] as? JsonObject).str("md5")
      suspend fun pages(client: io.ktor.client.HttpClient, id: String?): Pair<RawResponse, Int?> {
        val raw = client.probe(
          HttpMethod.Get, API_BASE, "/pools/$id", HeaderSet.POSTS, bearer = token,
          query = mapOf("lang" to "en", "exceptStatuses[]" to "deleted"),
        )
        return raw to ((raw.json as? JsonObject)?.get("posts") as? JsonArray)?.size
      }
      val android = smoke.androidClient
      val here = books(smoke.authClient).filter { it.str("status") == "active" && it.cover() != null }
      val asAndroid = books(android).associateBy { it.cover() }
      var last: RawResponse? = null
      var compared = 0
      var shorter = 0
      for (b in here.take(15)) {
        val other = asAndroid[b.cover()] ?: continue
        val (raw, count) = pages(smoke.authClient, b.str("id"))
        val (_, androidCount) = pages(android, other.str("id"))
        last = raw
        if (count == null || androidCount == null) continue
        compared++
        if (androidCount < count) shorter++
      }
      (last ?: fail("no book in both lists")) to "a book shorter as Android: ${yesNo(shorter > 0)}; " +
        "books compared=$compared, shorter as Android=$shorter, of the list's ${here.size} not in Android's=${here.count { it.cover() !in asAndroid }}"
    }

    // The book sheet's series (#383): which of a series book's own fields name its series. Key names and yes/no only.
    smoke.discover("d20 a book's series in /pools/{id}", "d20-book-series", requires = seriesId, "needs a series from b2", showHint = false) { id ->
      val member = books.series(id).books.firstOrNull() ?: fail("the series has no books")
      val raw = smoke.authClient.probe(
        HttpMethod.Get, API_BASE, "/pools/${member.id}", HeaderSet.POSTS, bearer = bearer,
        query = mapOf("lang" to "en", "exceptStatuses[]" to "deleted"),
      )
      val fields = (raw.json as? JsonObject).orEmpty()
      val named = fields.keys.filter { "series" in it.lowercase() || "parent" in it.lowercase() }.sorted()
      val holding = named.filter { key -> fields[key].toString().contains("\"$id\"") || fields[key].toString() == id }
      // The web app's GraphQL book (`poolV2`) has no series field either: a validation error, not data.
      val graphql = smoke.authClient.probe(
        HttpMethod.Post, API_BASE, "/graphql", HeaderSet.POSTS, bearer = bearer, query = mapOf("lang" to "en"),
        body = buildJsonObject {
          put("query", "query S(\$id: StringOrInt!) { poolV2(id: \$id) { series { id } } }")
          putJsonObject("variables") { put("id", member.id) }
        },
      )
      // A row's ♡ starts from the list's own `is_favorited`, so every listed book must carry it.
      val list = smoke.authClient.probe(
        HttpMethod.Get, API_BASE, "/poolseriesv2", HeaderSet.POSTS, bearer = bearer,
        query = mapOf(
          "lang" to "en", "tags" to "order:popularity rating:s", "page" to "1", "filledPools" to "true",
          "includes[]" to "pools", "exceptStatuses[]" to "deleted",
        ),
      )
      val rows = ((list.pools()?.get("data") as? JsonArray).orEmpty()).map { it as? JsonObject }
      val withFlag = rows.isNotEmpty() && rows.all { it?.containsKey("is_favorited") == true }
      raw to "series fields=$named, holding the series' id=$holding; GraphQL poolV2.series: ${graphql.status}; " +
        "list books carry is_favorited: ${yesNo(withFlag)}"
    }

    bookToSeries(bearer)
    continueReading(bearer)

    // A post's book (#386): the post's own flag, and the books-of-a-post query, which takes only numeric ids.
    val page = book?.posts?.getOrNull(1)
    val platform = if (android) "android" else "desktop"
    // How many of the newest G posts are a book's page varies with what was uploaded (none of 97 on 2026-11-06, 1 of
    // 20 later that day), so it's the detail, not the marker.
    var plainInBooks = ""
    smoke.discover(
      "d19 a post's book ($platform ids)", "d19-post-book-$platform", requires = page, "needs a book's page from b3",
      showHint = false, detail = { plainInBooks },
    ) { p ->
      suspend fun keyset(tags: String, limit: Int) = smoke.authClient.probe(
        HttpMethod.Get, API_BASE, "/v2/posts/keyset", HeaderSet.POSTS, bearer = bearer,
        query = mapOf("lang" to "en", "limit" to limit.toString(), "tags" to tags),
      ).posts()
      val inBook = keyset("id:${p.id}", 1).any { it.str("in_visible_pool") == "true" }
      val plain = keyset("rating:s", 20)
      val flagged = plain.filter { it.str("in_visible_pool") == "true" }
      // With numeric ids, whether the flag means a book: the books of the first flagged post and the first unflagged.
      suspend fun booksOf(post: JsonObject?) = post?.str("id")?.takeIf { android }?.let { books.booksOf(it).size }
      plainInBooks = "in_visible_pool on the newest rating:s posts: ${flagged.size} of ${plain.size}" +
        (if (android) ", books of a flagged one=${booksOf(flagged.firstOrNull())}, of an unflagged one=${booksOf(plain.firstOrNull { it !in flagged })}" else "")
      val raw = smoke.authClient.probe(
        HttpMethod.Get, API_BASE, "/pools/keyset", HeaderSet.POSTS, bearer = bearer,
        query = mapOf("lang" to "en", "post_id" to p.id),
      )
      val found = raw.posts().map { it.str("id") }
      raw to "in_visible_pool on a book's page: ${yesNo(inBook)}; " +
        "pools/keyset?post_id: the book among them=${yesNo(book?.id in found)}, code=${raw.json.str("code")}"
    }

    // The blacklist (#377 decision 19): a temporary blur rule on a common tag, then the same posts again; then a hide
    // rule on the same tag as the control that a rule takes effect at once. Each undone in the step; the rule's id is
    // never printed.
    val tryBlur = bearer?.takeIf { smoke.env["SANKAKU_TRY_BLACKLIST"] != "false" }
    smoke.discover("d19 blur rule: any flag on the post?", "d19-blur-flag", requires = tryBlur, "not signed in or SANKAKU_TRY_BLACKLIST=false") { token ->
      suspend fun posts() = smoke.authClient.probe(
        HttpMethod.Get, API_BASE, "/v2/posts/keyset", HeaderSet.POSTS, bearer = token,
        query = mapOf("lang" to "en", "limit" to "20", "tags" to "$BLUR_TAG rating:s"),
      )
      val before = posts()
      val blurred = withRule(token, "blur") { posts() }
      val hidden = withRule(token, "hide") { posts() }
      val diff = comparePosts(before, blurred)
      blurred to "posts the same: ${yesNo(diff.samePosts)}, fields changed besides signed URLs: ${diff.changed.ifEmpty { listOf("none") }.joinToString(",")}, " +
        "new keys: ${diff.newKeys.ifEmpty { listOf("none") }.joinToString(",")}; " +
        "control, a hide rule: posts before>0: ${yesNo(before.posts().isNotEmpty())}, with it none: ${yesNo(hidden.posts().isEmpty())}"
    }
  }

  /**
   * d21 (#400): whether anything leads from a series' book back to its series. Key names, field names and yes/no
   * only. Every candidate the issue names: the book's `reading.series_id` (on a book the account has opened, and after
   * a place saved with the series' id, undone in the step), `parent_id`, `parent_pool` and `has_children`, GraphQL
   * fields on the book and on the root, and the list's own query parameters.
   */
  private suspend fun bookToSeries(bearer: String?) {
    var exploratory = ""
    smoke.discover(
      "d21 a book's way to its series", "d21-book-to-series", requires = seriesId?.takeIf { bearer != null },
      "needs a series from b2, signed in", showHint = false, detail = { exploratory },
    ) { id ->
      val series = books.series(id)
      val member = series.books.firstOrNull() ?: fail("the series has no books")
      suspend fun rawBook(bookId: String) = smoke.authClient.probe(
        HttpMethod.Get, API_BASE, "/pools/$bookId", HeaderSet.POSTS, bearer = bearer,
        query = mapOf("lang" to "en", "exceptStatuses[]" to "deleted"),
      )
      fun JsonElement?.holds(value: String) = this != null && this !is JsonNull && toString().contains("\"$value\"")
      val raw = rawBook(member.id)
      val fields = (raw.json as? JsonObject).orEmpty()
      val holding = fields.keys.filter { fields[it].holds(id) }.sorted()
      val relatives = listOf("parent_id", "parent_pool", "has_children", "child_pools")
        .joinToString(", ") { "$it=${fields[it]?.let { v -> if (v is JsonNull) "null" else v::class.simpleName?.removePrefix("Json") } ?: "absent"}" }

      fun seriesIdNote(value: String?, seriesId: String) = if (value == null) "null" else "the series' id: ${yesNo(value == seriesId)}"

      // Books of the list's series the account has opened (on the website or in the app): their reading's series_id.
      // Whether there are any depends on the account, so this is the detail.
      val listed = books.list(listOf("order:popularity", "rating:s")).series.data.map { it.id }.plus(id).distinct()
      val opened = listed.flatMap { s -> books.series(s).books.filter { it.reading != null }.map { s to it.id } }
      val openedSeriesIds = opened.map { (s, b) -> seriesIdNote((rawBook(b).json as? JsonObject)?.get("reading").str("series_id"), s) }
      val openedNote = "opened books in the list's ${listed.size} series=${opened.size}, their reading.series_id=${openedSeriesIds.distinct()}"

      // On a book of the series without a place: one saved as the app saves it (sequence 1), then the same PUT with the
      // series' id (sequence 0), as a reader of the series might send it. Cleared as the app clears a place (pool_ids
      // only), so no other place is touched. Skipped, not failed, when the account has a place in every book of it.
      val target = series.books.firstOrNull { it.reading == null }?.id
      val saves = if (target == null) "saves: skipped (every book of the series has a place)" else undoing({ books.clearReading(target) }) {
        books.saveReading(target, 1)
        val plain = (rawBook(target).json as? JsonObject)?.get("reading")
        if (plain == null || plain is JsonNull) fail("the app's save left no place")
        val answer = smoke.authClient.probe(
          HttpMethod.Put, API_BASE, "/pools/$target/reading", HeaderSet.NONE, bearer = bearer, query = mapOf("lang" to "en"),
          body = buildJsonObject {
            put("sequence", 0)
            put("series_id", id)
          },
        )
        val after = (rawBook(target).json as? JsonObject)?.get("reading")
        "the app's save: reading.series_id=${seriesIdNote(plain.str("series_id"), id)}; a save with series_id: ${answer.status}, " +
          "its sequence saved=${yesNo(after.str("sequence") == "0")}, the earlier save's kept=${yesNo(after.str("sequence") == "1")}, " +
          "then reading.series_id=${seriesIdNote(after.str("series_id"), id)}"
      }.also { if (books.pages(target).reading != null) fail("a place is still saved: clear it by hand") }

      // GraphQL: which fields the book (`poolV2`) and the root take, and the server's "Did you mean" hints for each.
      suspend fun graphql(query: String, variables: JsonObject) = smoke.authClient.probe(
        HttpMethod.Post, API_BASE, "/graphql", HeaderSet.POSTS, bearer = bearer, query = mapOf("lang" to "en"),
        body = buildJsonObject {
          put("query", query)
          put("variables", variables)
        },
      )
      fun RawResponse.errors() = ((json as? JsonObject)?.get("errors") as? JsonArray).orEmpty().mapNotNull { it.str("message") }
      // Schema names only, never the rest of a message.
      fun RawResponse.hints() = errors().flatMap { m ->
        Regex("\"([A-Za-z0-9_]+)\"").findAll(m.substringAfter("Did you mean", "")).map { it.groupValues[1] }.toList()
      }
      val vars = buildJsonObject { put("id", member.id) }
      val poolHints = sortedSetOf<String>()
      val poolFields = POOL_SERIES_FIELDS.filter { f ->
        val r = graphql("query S(\$id: StringOrInt!) { poolV2(id: \$id) { $f } }", vars)
        poolHints += r.hints()
        r.status == 200 && r.errors().isEmpty()
      }
      // What `poolV2.parent` holds for each of the series' first books.
      val parents = series.books.take(3).map { b ->
        val r = graphql("query S(\$id: StringOrInt!) { poolV2(id: \$id) { parent { __typename id } } }", buildJsonObject { put("id", b.id) })
        val p = r.json.obj("data").obj("poolV2")?.get("parent")
        when {
          r.errors().isNotEmpty() -> "error"
          p == null || p is JsonNull -> "null"
          else -> "${p.str("__typename")}, the series' id=${yesNo(p.str("id") == id)}, the book's own=${yesNo(p.str("id") == b.id)}"
        }
      }
      // A root field that exists but takes no book says "Unknown argument", not "Cannot query field".
      val rootHints = sortedSetOf<String>()
      val rootAnswers = ROOT_SERIES_FIELDS.associateWith { f ->
        graphql("query S(\$id: StringOrInt!) { $f(poolId: \$id) { id } }", vars).also { rootHints += it.hints() }
      }
      val rootExisting = rootAnswers.filterValues { r -> r.errors().none { "Cannot query field" in it } }
      val rootFields = rootAnswers.filterValues { r -> r.status == 200 && r.errors().isEmpty() }.keys.toList()
      // Each existing field's required argument, as `name: Type` from `argument "id" of type "Int!" is required`.
      val rootArgs = rootExisting.mapValues { (_, r) ->
        r.errors().mapNotNull { Regex("argument \"([A-Za-z_]+)\" of type \"([A-Za-z!\\[\\]]+)\" is required").find(it) }
          .map { "${it.groupValues[1]}: ${it.groupValues[2]}" }
      }

      // The list's own parameters: does any narrow its series to the one holding the book?
      val listParams = LIST_POOL_PARAMS.filter { p ->
        val r = smoke.authClient.probe(
          HttpMethod.Get, API_BASE, "/poolseriesv2", HeaderSet.POSTS, bearer = bearer,
          query = mapOf("lang" to "en", "page" to "1", "limit" to "5", "filledPools" to "true", "includes[]" to "pools", p to member.id),
        )
        val ids = (r.json.obj("series")?.get("data") as? JsonArray).orEmpty().map { it.str("id") }
        ids == listOf(id)
      }
      exploratory = "$openedNote; GraphQL hints on poolV2=$poolHints, on the root=$rootHints"
      raw to "a series' book: fields holding the series' id=$holding, $relatives; $saves; " +
        "poolV2 fields that validate=$poolFields, poolV2.parent of the series' first ${parents.size} books=${parents.distinct()}; " +
        "root fields that exist=$rootArgs, taking poolId=$rootFields; list params naming only the series=$listParams"
    }
  }

  private fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

  /** Runs [block] while a [visibility] rule on [BLUR_TAG] exists on the account, and removes the rule after it. */
  private suspend fun <R> withRule(token: String, visibility: String, block: suspend () -> R): R {
    val added = smoke.authClient.probe(
      HttpMethod.Post, API_BASE, "/users/blacklist", HeaderSet.V2, bearer = token, query = mapOf("lang" to "en"),
      body = buildJsonObject {
        put("type", "tag")
        putJsonArray("tags") { add(BLUR_TAG) }
        put("visibility", visibility)
      },
    )
    val ruleId = ((added.json as? JsonObject)?.get("new_blacklist") as? JsonObject).str("id")
      ?: fail("$visibility rule not added (${added.status})")
    return undoing({
      val removed = smoke.authClient.probe(
        HttpMethod.Delete, API_BASE, "/users/blacklist/$ruleId", HeaderSet.V2, bearer = token, query = mapOf("lang" to "en"),
      )
      if (removed.status != 200) fail("the $visibility rule on $BLUR_TAG wasn't removed (${removed.status}): remove it by hand")
    }, block)
  }

  private class PostsDiff(val samePosts: Boolean, val changed: List<String>, val newKeys: List<String>)

  /** By md5: whether the same posts came back, which fields differ apart from the signed URLs, and which keys are new. */
  private fun comparePosts(before: RawResponse, during: RawResponse): PostsDiff {
    val a = before.posts().associateBy { it.str("md5") }
    val b = during.posts().associateBy { it.str("md5") }
    val urls = setOf("file_url", "sample_url", "preview_url", "gif_preview_url")
    val changed = sortedSetOf<String>()
    val newKeys = sortedSetOf<String>()
    for ((md5, post) in b) {
      val old = a[md5] ?: continue
      newKeys += post.keys - old.keys
      changed += post.keys.filter { it !in urls && it in old && post[it] != old[it] }
    }
    return PostsDiff(a.keys == b.keys, changed.toList(), newKeys.toList())
  }

  /** A `b` step: runs [block] on the app's code and checks its marker against `docs/api-recorded.json`. */
  private suspend fun <T : Any> booksStep(
    name: String,
    id: String,
    requires: T?,
    skipNote: String,
    block: suspend (T) -> Pair<String, String>,
  ) = smoke.step(name, requires, skipNote) {
    val (marker, detail) = block(it)
    val status = smoke.log.entries.value.lastOrNull()?.status ?: 0
    val (pass, note) = smoke.recordings.check(id, Recorded(status, emptyList(), marker))
    if (!pass) fail(note)
    listOf(note, detail).filter { s -> s.isNotEmpty() }.joinToString("; ")
  }

  private suspend fun booksStep(name: String, id: String, block: suspend (Unit) -> Pair<String, String>) =
    booksStep(name, id, Unit, "", block)

  private fun RawResponse.pools(): JsonObject? = (json as? JsonObject)?.get("pools") as? JsonObject

  /**
   * d26 (#503): is there a list of the books the account is reading, as the site's "Continue reading"? Each candidate's
   * status, and whether it lists books and they all carry a `reading`. Counts go to the detail only: they change.
   */
  private suspend fun continueReading(bearer: String?) {
    var counts = ""
    smoke.discover(
      "d26 a list of the books being read", "d26-continue-reading", requires = bearer, "not signed in",
      showHint = false, detail = { counts },
    ) { token ->
      var last: RawResponse? = null
      val answers = READING_LISTS.map { (label, request) ->
        val (path, query) = request
        val raw = smoke.authClient.probe(
          HttpMethod.Get, API_BASE, path, HeaderSet.POSTS, bearer = token, query = mapOf("lang" to "en") + query,
        )
        last = raw
        val listed = raw.listedBooks()
        val reading = listed.count { it["reading"].let { r -> r is JsonObject } }
        counts += "$label: ${listed.size} listed, $reading with reading; "
        "$label=${raw.status} books=${yesNo(listed.isNotEmpty())} all reading=${yesNo(listed.isNotEmpty() && reading == listed.size)}"
      }
      (last ?: fail("no candidates")) to answers.joinToString("; ")
    }

    // `order:reading` lists the books with a place: its order, finished books, series, the rating lock and paging. The
    // order is checked by saving the second book's place again, unchanged (the same sequence), and listing again.
    var shape = ""
    smoke.discover(
      "d26 order:reading", "d26-order-reading", requires = bearer, "not signed in", showHint = false, detail = { shape },
    ) { token ->
      suspend fun list(tags: String, page: Int = 1) = smoke.authClient.probe(
        HttpMethod.Get, API_BASE, "/poolseriesv2", HeaderSet.POSTS, bearer = token,
        query = LIST_QUERY + mapOf("lang" to "en", "tags" to tags, "page" to page.toString()),
      )
      val all = list("order:reading")
      val reading = all.listedBooks()
      if (reading.size < 2) fail("fewer than 2 books being read")
      fun JsonObject.percent() = (this["reading"] as? JsonObject).str("percent")?.toDoubleOrNull()
      val finished = reading.count { (it.percent() ?: 0.0) >= 100.0 }
      val locked = list("order:reading rating:s").listedBooks()
      val english = list("order:reading english_language").listedBooks()
      val page2 = list("order:reading", page = 2).listedBooks()
      val second = reading[1]
      val secondId = second.str("id") ?: fail("no id")
      val sequence = (second["reading"] as? JsonObject).str("sequence")?.toIntOrNull() ?: fail("no sequence")
      books.saveReading(secondId, sequence)
      val after = list("order:reading").listedBooks().map { it.str("id") }
      shape = "listed ${reading.size}, finished $finished, rating:s ${locked.size}, english ${english.size}, page 2 ${page2.size}"
      all to "finished listed=${yesNo(finished > 0)}, series=${yesNo(all.seriesCount() > 0)}, " +
        "rating:s narrows=${yesNo(locked.isNotEmpty() && locked.all { it.str("rating") == "s" })}, " +
        "with another tag=${yesNo(english.isNotEmpty())}, page 2 repeats page 1=${yesNo(page2.isNotEmpty() && page2.map { it.str("id") } == reading.map { it.str("id") })}, " +
        "saved again comes first=${yesNo(after.firstOrNull() == secondId)}"
    }
  }

  /** The books a list answer holds: its `data`, `pools.data`, or a bare array. */
  private fun RawResponse.listedBooks(): List<JsonObject> {
    val root = json
    val array = when (root) {
      is JsonArray -> root
      is JsonObject -> (root["data"] as? JsonArray) ?: (pools()?.get("data") as? JsonArray)
      else -> null
    }
    return array.orEmpty().filterIsInstance<JsonObject>()
  }

  private fun RawResponse.posts(): List<JsonObject> =
    ((json as? JsonObject)?.get("data") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

  private fun RawResponse.bookIds(): List<String?> = (pools()?.get("data") as? JsonArray).orEmpty().map { it.str("id") }

  private fun RawResponse.seriesCount(): Int =
    (((json as? JsonObject)?.get("series") as? JsonObject)?.get("data") as? JsonArray)?.size ?: 0

  private companion object {
    /** Common in `rating:s`, as in the other steps. */
    const val BLUR_TAG = "blue_hair"

    /** d21: names a book's series field might have on GraphQL's `poolV2` (#400). */
    val POOL_SERIES_FIELDS = listOf(
      "series { id }", "seriesV2 { id }", "seriesList { id }", "seriesPools { id }", "poolSeries { id }",
      "parentSeries { id }", "seriesId", "series_id", "seriesIds", "parent { id }", "parentPool { id }", "parentId",
      "reading { seriesId }", "reading { series_id }", "reading { series { id } }",
    )

    /** d21: names a root query from a book to its series might have. */
    val ROOT_SERIES_FIELDS = listOf("series", "seriesV2", "seriesByPool", "seriesOfPool", "poolSeries", "seriesList", "seriesListV2", "seriesV2List")

    /** d21: query parameters `/poolseriesv2` might narrow its series by. */
    val LIST_POOL_PARAMS = listOf("pool_id", "poolId", "pool_ids[]", "pools[]", "pool")

    private val LIST_QUERY = mapOf("page" to "1", "limit" to "20", "includes[]" to "pools", "filledPools" to "true")

    /** d26: where the site's "Continue reading" might come from (#503), by a short label. */
    val READING_LISTS = listOf(
      "pools/readings" to ("/pools/readings" to mapOf("page" to "1", "limit" to "20")),
      "pools/reading" to ("/pools/reading" to mapOf("page" to "1", "limit" to "20")),
      "users/me/readings" to ("/users/me/readings" to mapOf("page" to "1", "limit" to "20")),
      "pools/keyset reading" to ("/pools/keyset" to mapOf("limit" to "20", "reading" to "true")),
      "poolseriesv2 reading=true" to ("/poolseriesv2" to LIST_QUERY + mapOf("tags" to "rating:s", "reading" to "true")),
      "poolseriesv2 is_reading" to ("/poolseriesv2" to LIST_QUERY + mapOf("tags" to "rating:s", "is_reading" to "true")),
      "poolseriesv2 order:reading" to ("/poolseriesv2" to LIST_QUERY + mapOf("tags" to "order:reading")),
      "poolseriesv2 reading:true" to ("/poolseriesv2" to LIST_QUERY + mapOf("tags" to "reading:true")),
    )

    /** b9 waits up to a minute for each PUT sent in the background (a 429 pauses the host). */
    const val PUT_WAITS = 120
    const val PUT_WAIT_MS = 500L
  }
}
