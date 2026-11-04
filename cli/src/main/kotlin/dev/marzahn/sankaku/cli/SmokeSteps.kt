package dev.marzahn.sankaku.cli

import dev.marzahn.sankaku.api.API_BASE
import dev.marzahn.sankaku.data.KeysetPagingSource
import dev.marzahn.sankaku.api.ApiError
import dev.marzahn.sankaku.api.AuthApi
import dev.marzahn.sankaku.api.BrowserFingerprint
import dev.marzahn.sankaku.api.HeaderSet
import dev.marzahn.sankaku.api.ImageRefusedException
import dev.marzahn.sankaku.api.LOGIN_BASE
import dev.marzahn.sankaku.api.PostsApi
import dev.marzahn.sankaku.api.RateLimiter
import dev.marzahn.sankaku.api.RawResponse
import dev.marzahn.sankaku.api.TagsApi
import dev.marzahn.sankaku.api.UsersApi
import dev.marzahn.sankaku.api.createAuthClient
import dev.marzahn.sankaku.api.createEngine
import dev.marzahn.sankaku.api.createMainClient
import dev.marzahn.sankaku.api.dto.PostDto
import dev.marzahn.sankaku.api.probe
import dev.marzahn.sankaku.auth.AuthSession
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.auth.SignInResult
import dev.marzahn.sankaku.log.RequestLog
import dev.marzahn.sankaku.net.ConnectivityState
import io.ktor.client.request.get
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private class StepFailure(message: String) : Exception(message)

/**
 * The Phase 0 flow on the app's own `:sharedLogic` code, then the discovery steps through [probe].
 * Steps after a failed prerequisite are skipped, not failed.
 */
class SmokeSteps(
  private val env: Map<String, String>,
  private val recordings: Recordings,
  private val report: Report,
  private val ledger: FeedLedger,
  private val slow: Boolean,
  /** The browser the clients claim to be: desktop's by default, Android's with `--android` (#204). */
  fingerprint: BrowserFingerprint = BrowserFingerprint.Current,
) {
  private val log = RequestLog()
  private val connectivity = ConnectivityState()
  private val limiter = RateLimiter()
  private val engine = createEngine()
  private val authClient = createAuthClient(engine, log, limiter, connectivity, fingerprint)
  private val store = InMemoryTokenStore()
  private val session = AuthSession(AuthApi(authClient), store)
  private val mainClient = createMainClient(engine, log, limiter, connectivity, session, fingerprint)
  private val posts = PostsApi(mainClient)
  private val users = UsersApi(mainClient)
  private val tags = TagsApi(mainClient)

  private val login = env["SANKAKU_LOGIN"].orEmpty()
  private val password = env["SANKAKU_PASSWORD"].orEmpty()

  private var anonPosts: List<PostDto> = emptyList()
  private var post: PostDto? = null
  private var userName: String? = null
  private var preRefreshToken: String? = null

  suspend fun run() {
    session.restore()

    step("1 anonymous keyset") {
      // Signed out, some posts are hidden (`redirect_to_signup`, no URLs), as the app's grid shows; skip those.
      anonPosts = posts.keyset(listOf("rating:s"), limit = 10).data.filterNot { it.redirectToSignup }.take(2)
      val first = anonPosts.firstOrNull() ?: fail("no posts")
      urlNote(first)
    }
    step("2 anonymous preview download", requires = anonPosts.firstOrNull()?.previewUrl) { url ->
      val status = mainClient.get(url).status.value
      if (status != 200) fail("status $status")
      ""
    }

    val signedIn = login.isNotBlank() && password.isNotBlank()
    val noLogin = "SANKAKU_LOGIN / SANKAKU_PASSWORD not set"
    step("3 password login, then exchange", requires = if (signedIn) Unit else null, skipNote = noLogin) {
      when (val result = session.signIn(login, password)) {
        SignInResult.Ok -> {
          val tokens = store.load() ?: fail("nothing stored")
          val now = Clock.System.now()
          "access ttl ${(tokens.accessExpiresAt - now + 1.minutes).inWholeDays} d, refresh ttl ${(tokens.refreshExpiresAt - now + 1.minutes).inWholeDays} d"
        }
        is SignInResult.Failed -> throw result.error
        else -> fail("$result")
      }
    }
    val hasSession = session.isSignedIn.value
    val noSession = "not signed in"
    step("4 users/me", requires = if (hasSession) Unit else null, skipNote = noSession) {
      userName = users.me().name
      ""
    }
    step("5 signed-in keyset (POSTS)", requires = if (hasSession) Unit else null, skipNote = noSession) {
      // The server can filter a whole page away and still give `next` (seen 2026-10-30 on the test account), so this
      // follows the cursor and the page number as KeysetPagingSource does.
      var page = posts.keyset(listOf("rating:s"), limit = 10)
      var skipped = 0
      while (page.data.isEmpty() && page.meta.next != null && skipped < KeysetPagingSource.MAX_EMPTY_PAGES) {
        page = posts.keyset(listOf("rating:s"), next = page.meta.next, page = skipped + 2, limit = 10)
        skipped++
      }
      post = page.data.firstOrNull() ?: fail("no posts after $skipped empty pages")
      urlNote(post!!)
    }
    step("6 byId (keyset id:)", requires = post) { p ->
      val fresh = posts.byId(p.id)
      if (fresh.id != p.id) fail("returned another post")
      post = fresh
      urlNote(fresh)
    }
    val favouriteSkip = when {
      env["SANKAKU_TRY_FAVOURITE"] == "false" -> "SANKAKU_TRY_FAVOURITE=false"
      post?.isFavorited == true -> "post is already a favourite"
      else -> noSession
    }
    val favouritePost = post?.takeIf { env["SANKAKU_TRY_FAVOURITE"] != "false" && !it.isFavorited }
    step("7 favourite, then unfavourite", requires = favouritePost, skipNote = favouriteSkip) { p ->
      posts.favourite(p.id)
      posts.unfavourite(p.id)
      ""
    }
    step("8 refresh", requires = if (hasSession) Unit else null, skipNote = noSession) {
      val before = session.currentBearerTokens() ?: fail("no tokens")
      preRefreshToken = before.refreshToken
      val after = session.refresh(before) ?: fail("refresh failed")
      if (after.refreshToken == before.refreshToken) fail("refresh token not rotated")
      // Drop the main client's cached token, so step 9 sends the new one.
      session.onTokensChanged()
      "refresh token rotated"
    }
    step("9 users/me with the new token", requires = if (hasSession) Unit else null, skipNote = noSession) {
      users.me()
      ""
    }
    step("10 tag autocomplete") {
      val found = tags.autocomplete("blue_ha")
      if (found.none { it.tagName == "blue_hair" }) fail("blue_hair not suggested")
      if (tags.autocomplete("-blue_ha").any { !it.tagName.startsWith("-") }) fail("exclusion dropped")
      // The search bar badges each suggestion's age rating, so the codes must be ones it knows.
      val ratings = found.mapNotNull { it.rating }.toSortedSet()
      if (ratings.any { it !in setOf("s", "q", "e") }) fail("unexpected rating codes $ratings")
      "suggestions=${found.size}, ratings=$ratings"
    }
    // The app's follow and unfollow, checked through `is_following` on the full post; undone in the same step.
    val followPost = anonPosts.firstOrNull()?.takeIf { session.isSignedIn.value && env["SANKAKU_TRY_FOLLOW"] != "false" }
    step("11 follow, then unfollow a tag", requires = followPost, skipNote = "not signed in or SANKAKU_TRY_FOLLOW=false") { p ->
      val tag = posts.full(p.id).tags.filter { !it.isFollowing && it.id != null }.maxByOrNull { it.postCount ?: 0 }
        ?: fail("no tag to follow")
      tags.follow(tag.id!!, tag.searchName)
      try {
        if (posts.full(p.id).tags.none { it.id == tag.id && it.isFollowing }) fail("not followed after the follow")
      } finally {
        tags.unfollow(tag.id!!, tag.searchName)
      }
      if (posts.full(p.id).tags.any { it.id == tag.id && it.isFollowing }) fail("still followed: unfollow it by hand")
      ""
    }
    // The post details' Recommended strip: the app's own call, as is and with the signed-out lock's `rating:s`.
    step("12 recommended posts", requires = anonPosts.firstOrNull()) { p ->
      val plain = posts.recommended(p.id)
      if (plain.isEmpty()) fail("no recommendations")
      if (plain.any { it.id == p.id }) fail("recommends the post itself")
      val withPreview = plain.count { it.previewUrl != null }
      val safe = posts.recommended(p.id, tags = listOf("rating:s"))
      val ratings = safe.map { it.rating }.toSortedSet()
      if (safe.isNotEmpty() && ratings != sortedSetOf("s")) fail("rating:s let through $ratings")
      "posts=${plain.size}, preview_url=$withPreview, file_url=${yesNo(plain.any { it.fileUrl != null })}; with rating:s posts=${safe.size}, ratings=$ratings"
    }
    // The details' Parent and Children strips (#342): the app's own calls, `parent:<id>` for the children and `id:`
    // for the parent, from the newest post with a parent.
    step("13 parent and children (parent:<id>)") {
      val list = posts.keyset(listOf("rating:s"), limit = 100).data
      val child = list.firstOrNull { it.parentId != null && !it.redirectToSignup } ?: fail("no post with a parent among ${list.size}")
      val parentId = child.parentId!!
      val parent = posts.byIdOrNull(parentId) ?: fail("the parent is gone")
      val parentWithLock = posts.byIdOrNull(parentId, listOf("rating:s"))
      if ((parentWithLock != null) != (parent.rating == "s")) fail("id: with rating:s doesn't follow the parent's rating")
      val family = posts.children(parentId)
      val children = family.filter { it.id != parentId }
      if (children.none { it.id == child.id }) fail("parent: left out the child")
      if (children.any { it.parentId != parentId }) fail("parent: returned a post of another parent")
      val safe = posts.children(parentId, listOf("rating:s"))
      val ratings = safe.map { it.rating }.toSortedSet()
      if (safe.isNotEmpty() && ratings != sortedSetOf("s")) fail("rating:s let through $ratings")
      "with a parent: ${list.count { it.parentId != null }} of ${list.size}, has_children in the list: ${list.count { it.hasChildren }}; " +
        "the parent's has_children=${yesNo(parent.hasChildren)}; parent: posts=${family.size}, " +
        "the parent itself among them=${yesNo(family.any { it.id == parentId })}, " +
        "file_url=${yesNo(children.all { it.fileUrl != null })}, preview_url=${yesNo(children.all { it.previewUrl != null })}; " +
        "with rating:s posts=${safe.size}"
    }
    // The heart on the sheet's thumbnails (#342) starts from the list's own `is_favorited`: this checks a
    // recommendation carries it, by favouriting one and asking again. Undone in the same step.
    val recommendedFavourite = anonPosts.firstOrNull()?.takeIf { hasSession && env["SANKAKU_TRY_FAVOURITE"] != "false" }
    step("14 is_favorited on recommendations", requires = recommendedFavourite, skipNote = "not signed in or SANKAKU_TRY_FAVOURITE=false") { p ->
      val target = posts.recommended(p.id).firstOrNull { !it.isFavorited && !it.redirectToSignup } ?: fail("no unfavourited recommendation")
      posts.favourite(target.id)
      val after = try {
        posts.recommended(p.id).firstOrNull { it.id == target.id }
      } finally {
        posts.unfavourite(target.id)
      }
      val undone = posts.recommended(p.id).firstOrNull { it.id == target.id }
      "favourited: still recommended=${yesNo(after != null)}, is_favorited=${yesNo(after?.isFavorited == true)}; " +
        "unfavourited: is_favorited=${yesNo(undone?.isFavorited == true)}"
    }

    discoveryStep1()
    discoveryStep2()
    discoveryStep3()
    discoveryStep4()
    discoveryStep5()
    discoveryStep6()
    discoveryStep8()
    discoveryStep9()
    discoveryStep10()
    discoveryStep11()
    discoveryStep12()
    discoveryStep13()
    discoveryStep14()
    discoveryStep15()
    discoveryStep16()
    discoveryStep17()
    discoveryStep18()
    if (slow) discoveryStep7()
  }

  private suspend fun keysetProbe(
    tags: String,
    bearer: String?,
    flag: String? = null,
    next: String? = null,
    page: Int? = null,
    limit: Int = 40,
  ) = authClient.probe(
    HttpMethod.Get, API_BASE, "/v2/posts/keyset", HeaderSet.POSTS, bearer = bearer,
    query = buildMap {
      putAll(lang)
      put("limit", limit.toString())
      put("tags", tags)
      put("hide_posts_in_books", "in-larger-tags")
      flag?.let { put(it, "true") }
      next?.let { put("next", it) }
      page?.let { put("page", it.toString()) }
    },
  )

  private fun RawResponse.page(): RawPage = RawPage(
    ((json as? JsonObject)?.get("data") as? JsonArray).orEmpty().filterIsInstance<JsonObject>(),
    nextCursor(),
  )


  /** Following's first pages as step 16 read them, with the cursor after each; steps 17 and 18 build on them. */
  private class FollowFeed(
    val posts: List<JsonObject>,
    val cursors: List<String>,
    val lastPage: RawPage,
    val lastRaw: RawResponse,
  )

  private var followFeed: FollowFeed? = null
  private val nowSeconds get() = Clock.System.now().epochSeconds

  /**
   * Following's order (stage 3's "N new / Continue / caught up" marks): by `created_at`, newest first, or by when the
   * followed tag was added? Reads [FEED_PAGES] pages of 40, and compares the top with the one an earlier run saved to
   * see whether posts turn up below the newest one later (late-tagged or late-approved posts).
   */
  private suspend fun discoveryStep16() {
    var detail = ""
    discover("d16 Following's order", "d16-following-order", requires = session.currentBearerTokens()?.accessToken, "not signed in", detail = { detail }) { token ->
      val posts = mutableListOf<JsonObject>()
      val cursors = mutableListOf<String>()
      var next: String? = null
      var lastRaw: RawResponse? = null
      var lastPage = RawPage(emptyList(), null)
      var cursorOnEveryPage = true
      for (n in 1..FEED_PAGES) {
        val raw = keysetProbe("rating:s", token, flag = "is_following", next = next, page = n, limit = 40)
        lastRaw = raw
        lastPage = raw.page()
        posts += lastPage.posts
        next = lastPage.next
        if (next == null) {
          cursorOnEveryPage = false
          break
        }
        cursors += next
      }
      if (posts.size < 3) fail("only ${posts.size} posts: the test account needs followed tags")
      followFeed = FollowFeed(posts, cursors, lastPage, lastRaw!!)
      val created = posts.mapNotNull { it.createdAt() }
      val ids = posts.mapNotNull { it.str("id") }
      val change = posts.mapNotNull { it.str("change")?.toLongOrNull() }
      // The same walk over the plain list, to see whether Following sorts differently from it.
      var plainNext: String? = null
      val plainCreated = mutableListOf<Long>()
      for (n in 1..FEED_PAGES) {
        val page = keysetProbe("rating:s", token, next = plainNext, page = n, limit = 40).page()
        plainCreated += page.created
        plainNext = page.next ?: break
      }
      val now = nowSeconds
      // Not strictly: one plain-list post in about 450 sat a few minutes to an hour below an older one (2026-11-01).
      fun nearlyNewestFirst(list: List<Long>) = list.isNotEmpty() && list.descendingBreaks() * 50 <= list.size
      detail = listOf(
        // Shown, not recorded: how many pages Following has depends on the account.
        "${posts.size} posts over ${cursors.size + if (cursorOnEveryPage) 0 else 1} pages, a next cursor on every page: ${yesNo(cursorOnEveryPage)}",
        "created_at out of order: Following ${created.descendingBreaks()} adjacent, ${created.inversions()} pairs; " +
          "plain list ${plainCreated.descendingBreaks()} adjacent of ${plainCreated.size}",
        "change out of order: ${change.inversions()} pairs",
        "ages ${formatAge(now - created.first())} to ${formatAge(now - created.last())}",
        snapshotNote(posts, now),
      ).joinToString("; ")
      lastRaw to listOf(
        "posts>0: yes",
        "created_at nearly newest first: ${yesNo(created.size == posts.size && nearlyNewestFirst(created))}",
        "no post twice: ${yesNo(ids.size == ids.toSet().size)}",
        "plain list the same: ${yesNo(nearlyNewestFirst(plainCreated))}",
      ).joinToString(", ")
    }
  }

  /**
   * Compares Following's top with the snapshot an earlier run saved: posts that are new in the span it covered are
   * either at the top (newer than its newest post) or late arrivals, which land below it. A snapshot older than
   * [SNAPSHOT_REFRESH_SECONDS] is replaced by this run's.
   */
  private fun snapshotNote(posts: List<JsonObject>, now: Long): String {
    val created = posts.mapNotNull { it.createdAt() }
    val hashes = posts.mapNotNull { it.str("id") }.map(::digest)
    val head = ledger.data.head
    val note = if (head == null) "no snapshot to compare with yet; saved one" else {
      val seen = head.seen.toSet()
      val fresh = posts.filter { (it.createdAt() ?: 0) >= head.oldest && digest(it.str("id").orEmpty()) !in seen }
      val late = fresh.count { (it.createdAt() ?: 0) < head.newest }
      "since the snapshot ${formatAge(now - head.savedAt)} ago: ${fresh.size} new posts in the span it covered, $late of them below its newest post"
    }
    if (head == null || now - head.savedAt >= SNAPSHOT_REFRESH_SECONDS) {
      ledger.setHead(HeadSnapshot(now, created.max(), created.min(), hashes))
    }
    return note
  }

  /**
   * How a keyset cursor behaves: what it is made of, whether a changed or made-up one is rejected, whether it holds a
   * position in time rather than in one query, and (replaying cursors saved by earlier runs) how long it stays valid.
   */
  private suspend fun discoveryStep17() {
    val bearer = session.currentBearerTokens()?.accessToken
    val feed = followFeed?.takeIf { it.cursors.isNotEmpty() }
    val reason = "needs Following's pages from d16"
    discover("d17 cursor shape", "d17-cursor-shape", requires = feed, reason) { f ->
      f.lastRaw to cursorShape(f.cursors.last(), f.lastPage.ids)
    }
    var tampered = ""
    discover("d17 changed and made-up cursors", "d17-cursor-tamper", requires = feed, reason, detail = { tampered }) { f ->
      val cursor = f.cursors.last()
      val page = f.cursors.size + 1
      suspend fun ask(next: String?) = keysetProbe("rating:s", bearer, flag = "is_following", next = next, page = page, limit = 40)
      val real = ask(cursor).page()
      val again = ask(cursor).page()
      val positions = listOf(0, 12, 20, 31).filter { it < cursor.length }
      val rejected = positions.count { i -> ask(cursor.replaceRange(i, i + 1, if (cursor[i] == 'A') "B" else "A")).status == 400 }
      tampered = "a changed character was rejected $rejected of ${positions.size} times"
      val madeUp = ask("not-a-cursor")
      val empty = ask("").page()
      val first = keysetProbe("rating:s", bearer, flag = "is_following", limit = 40).page()
      madeUp to listOf(
        "same cursor twice: same page=${yesNo(real.ids.isNotEmpty() && real.ids == again.ids)}",
        "changed characters rejected: ${yesNo(positions.isNotEmpty() && rejected * 4 >= positions.size * 3)}",
        "made-up cursor: ${madeUp.status} ${madeUp.json.str("code")}",
        "empty cursor: first page=${yesNo(empty.ids.isNotEmpty() && empty.ids == first.ids)}",
      ).joinToString(", ")
    }
    var offset = ""
    discover("d17 a Following cursor on the plain list", "d17-cursor-other-query", requires = feed, reason, detail = { offset }) { f ->
      val position = f.lastPage.created.min()
      val raw = keysetProbe("rating:s", bearer, next = f.cursors.last(), page = f.cursors.size + 1, limit = 40)
      val created = raw.page().created
      if (created.isNotEmpty()) offset = "the plain list's first post is ${position - created.first()} s older than Following's last"
      val continues = created.isNotEmpty() && created.all { it <= position } && created.first() >= position - 6 * 3600
      raw to "plain list continues from the cursor's time: ${yesNo(continues)}"
    }
    replayCursors(bearer)
  }

  /** Replays the cursors earlier runs saved, then saves a new one per kind that has none in the last 90 minutes. */
  private suspend fun replayCursors(bearer: String?) {
    if (bearer == null) return
    val now = nowSeconds
    val older = ledger.data.cursors.filter { now - it.savedAt >= 60 }.sortedBy { it.savedAt }
    val firstPages = mutableMapOf<String, List<String>>()
    for (saved in older) {
      step("d17 replay of a ${saved.kind} cursor, ${formatAge(now - saved.savedAt)} old", saved) { e ->
        val raw = keysetProbe(e.tags, bearer, e.flag, next = e.cursor, page = e.page, limit = e.limit)
        val page = raw.page()
        val first = firstPages.getOrPut(e.kind) { keysetProbe(e.tags, bearer, e.flag, limit = e.limit).page().ids }
        val code = if (raw.status == 200) "" else " ${raw.json.str("code")}"
        "status ${raw.status}$code, ${page.posts.size} posts, same page as when saved: ${yesNo(digestOf(page.ids) == e.digest)}, " +
          "the first page again: ${yesNo(page.ids.isNotEmpty() && page.ids == first)}"
      }
    }
    if (older.isEmpty()) {
      report.add("d17 cursor replay", StepResult(true, "", "", "no cursor saved by an earlier run yet; one is saved now", skipped = true))
    }
    // In a step, so a network error here is a failed row and not the end of the run.
    step("d17 save cursors for a later replay") {
      val saved = listOfNotNull(
        followFeed?.takeIf { it.cursors.isNotEmpty() }?.let {
          capture("following", "rating:s", "is_following", 40, bearer, cursor = it.cursors.last(), page = it.cursors.size + 1)
        },
        capture("plain", "rating:s", null, 40, bearer, walk = 3),
        artistTarget(bearer)?.let { capture("tag", "rating:s ${it.tag}", null, 2, bearer, walk = 1) },
      ).count { it }
      "saved $saved new"
    }
  }

  /** Keeps a cursor for a later run: [cursor] as given, or the one after [walk] pages. Returns whether one was saved. */
  private suspend fun capture(
    kind: String,
    tags: String,
    flag: String?,
    limit: Int,
    bearer: String,
    walk: Int = 0,
    cursor: String? = null,
    page: Int = 1,
  ): Boolean {
    val now = nowSeconds
    if (!ledger.due(kind, now)) return false
    var next = cursor
    var nextPage = page
    if (next == null) {
      for (n in 1..walk) {
        next = keysetProbe(tags, bearer, flag, next = next, page = n, limit = limit).page().next ?: return false
        nextPage = n + 1
      }
    }
    val saved = next ?: return false
    val ids = keysetProbe(tags, bearer, flag, next = saved, page = nextPage, limit = limit).page().ids
    ledger.add(SavedCursor(kind, now, tags, flag, limit, nextPage, saved, digestOf(ids), ids.size))
    return true
  }

  /** An artist tag, and a Following post that has it: the tag with the most posts among the next few posts' tags. */
  private class ArtistTarget(val tag: String, val postId: String, val created: Long)

  private var artist: ArtistTarget? = null
  private var artistLooked = false

  /** The first of a few Following posts, from the one [anchorIndex] picks, that has an artist tag. */
  private suspend fun artistTarget(bearer: String?): ArtistTarget? {
    if (artistLooked || bearer == null) return artist
    artistLooked = true
    val feed = followFeed ?: return null
    for (post in feed.posts.drop(anchorIndex(feed.posts)).take(ARTIST_CANDIDATES)) {
      val id = post.str("id") ?: continue
      val created = post.createdAt() ?: continue
      // A failed lookup is a post without an artist, not the end of the run.
      val tag = try {
        posts.full(id).tags.filter { it.type == 1 }.maxByOrNull { it.postCount ?: 0 }?.searchName
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      }
      if (tag != null) return ArtistTarget(tag, id, created).also { artist = it }
    }
    return null
  }

  /**
   * A post deep in Following that has an older post in the same UTC hour, so a `date:` bound inside the hour can be
   * told from one at the hour's start.
   */
  private fun anchorIndex(posts: List<JsonObject>): Int {
    val created = posts.map { it.createdAt() ?: 0 }
    val first = minOf(20, posts.size - 2)
    return (first until posts.size - 1).firstOrNull {
      hourStart(created[it]) == hourStart(created[it + 1]) && created[it] != hourStart(created[it])
    } ?: minOf(100, posts.size - 1)
  }

  /**
   * Jumping deep into a grid without a cursor: `id:<` and `id_range:` bounds, and `date:` ranges with Following and
   * an artist tag. The anchor is a post deep in Following; the steps check that a range around it finds it.
   */
  private suspend fun discoveryStep18() {
    val bearer = session.currentBearerTokens()?.accessToken
    val feed = followFeed?.takeIf { it.posts.size >= 3 }
    val reason = "needs Following's posts from d16"
    val anchor = feed?.posts?.get(anchorIndex(feed.posts))
    val anchorId = anchor?.str("id")
    val t = anchor?.createdAt()
    val h0 = t?.let(::hourStart)
    val ninetyDays = 90 * 86400L
    suspend fun range(tags: String, flag: String?, from: Long, to: Long, limit: Int = 40, page: Int? = null, next: String? = null) =
      keysetProbe("$tags date:${dateBound(from)}..${dateBound(to)}".trim(), bearer, flag, next = next, page = page, limit = limit)

    // Post ids are 11-character strings, not numbers, so there's no number to put after `id:<`.
    val target = artistTarget(bearer)
    val bounds = listOf(
      Triple("id:<id", "d18-id-less-than", "id:<%s"),
      Triple("id:<=id", "d18-id-less-or-equal", "id:<=%s"),
      Triple("id_range:..id", "d18-id-range", "id_range:..%s"),
    )
    for ((label, id, query) in bounds) {
      discover("d18 $label with Following", id, requires = anchorId, reason, showHint = false) { postId ->
        val raw = keysetProbe("rating:s ${query.format(postId)}", bearer, flag = "is_following", limit = 10)
        raw to "status ${raw.status}, code=${raw.json.str("code")}"
      }
    }
    for ((label, id, query) in bounds.filterNot { it.second == "d18-id-less-or-equal" }) {
      discover("d18 $label with an artist tag", "$id-artist", requires = target, "needs a Following post with an artist tag", showHint = false) { t ->
        val raw = keysetProbe("rating:s ${t.tag} ${query.format(t.postId)}", bearer, limit = 10)
        raw to "status ${raw.status}, code=${raw.json.str("code")}"
      }
    }
    var rounding = ""
    discover("d18 date range with Following", "d18-date-range-following", requires = anchor?.takeIf { h0 != null }, reason, detail = { rounding }) {
      val inHour = range("rating:s", "is_following", h0!!, h0 + 3600).page()
      val before = range("rating:s", "is_following", h0 - 3600, h0)
      // A bound inside the hour acts as the hour's start if `..T` and `..hour(T)` give the same page.
      val atT = range("rating:s", "is_following", h0 - ninetyDays, t).page().ids
      val atHour = range("rating:s", "is_following", h0 - ninetyDays, h0).page().ids
      rounding = "a bound inside an hour acts as the hour's start: ${yesNo(atT.isNotEmpty() && atT == atHour)}"
      before to listOf(
        "anchor found in its hour's range: ${yesNo(anchorId in inHour.ids)}",
        "nothing outside [start, end): ${yesNo(inHour.created.all { it >= h0 && it < h0 + 3600 } && before.page().created.all { it < h0 })}",
      ).joinToString(", ")
    }
    discover("d18 date range with an artist tag", "d18-date-range-artist", requires = artistTarget(bearer), "needs a Following post with an artist tag") { target ->
      val start = hourStart(target.created)
      val raw = range("rating:s ${target.tag}", null, start, start + 3600)
      val page = raw.page()
      raw to "post found: ${yesNo(target.postId in page.ids)}, inside the range: ${yesNo(page.created.all { it >= start && it < start + 3600 })}"
    }
    discover("d18 paging inside a date range", "d18-date-range-paging", requires = anchor?.takeIf { h0 != null }, reason) {
      val from = h0!! - 60 * 86400L
      val first = range("rating:s", "is_following", from, h0 + 3600, limit = 10, page = 1)
      val firstPage = first.page()
      val cursor = firstPage.next ?: return@discover first to "no next cursor"
      val second = range("rating:s", "is_following", from, h0 + 3600, limit = 10, page = 2, next = cursor)
      val page = second.page()
      second to "page 2 is older than page 1 and inside the range: " +
        yesNo(page.created.all { it >= from && it <= (firstPage.created.minOrNull() ?: Long.MAX_VALUE) } && page.ids.none { it in firstPage.ids })
    }
    // Only the status is recorded; the counts are shown (both were 0 on 2026-11-01, which is the finding).
    var ageCounts = ""
    discover("d18 age filters", "d18-age", requires = Unit, "", detail = { ageCounts }) {
      val newer = keysetProbe("rating:s age:<1year", bearer, limit = 10)
      val older = keysetProbe("rating:s age:>1year", bearer, limit = 10)
      ageCounts = "age:<1year posts=${newer.page().posts.size}, age:>1year posts=${older.page().posts.size}"
      older to null
    }
  }

  /**
   * Video, Flash and animated posts: their `file_type`, `file_ext` and URLs in the keyset list, and whether the video
   * host serves a file to a media player (its own User-Agent, a range request, as AVPlayer and ExoPlayer send).
   */
  private suspend fun discoveryStep14() {
    val bearer = session.currentBearerTokens()?.accessToken
    var video: JsonObject? = null
    // No Flash step: `file_type:flash` timed out (408 search-query-timeout) on 2026-10-30, as slow searches do.
    val kinds = listOf("video" to "file_type:video", "animated" to "animated_gif")
    for ((kind, tag) in kinds) {
      discover("d14 $kind posts", "d14-$kind", requires = Unit, "") {
        val raw = authClient.probe(
          HttpMethod.Get, API_BASE, "/v2/posts/keyset", HeaderSet.POSTS, bearer = bearer,
          query = lang + mapOf("limit" to "10", "tags" to "$tag rating:s"),
        )
        val posts = ((raw.json as? JsonObject)?.get("data") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
          .filter { it.str("file_url") != null }
        if (kind == "video") video = posts.firstOrNull()
        val types = posts.mapNotNull { it.str("file_type") }.toSortedSet()
        val exts = posts.mapNotNull { it.str("file_ext") }.toSortedSet()
        // The sample's extension from its path (never the URL itself: it's signed).
        val sample = posts.firstOrNull()?.str("sample_url")?.let { java.net.URI(it).path.substringAfterLast('.', "-") }
        raw to "posts>0: ${yesNo(posts.isNotEmpty())}, file_type=$types, file_ext=$exts, sample=${sample ?: "none"}"
      }
    }
    // The host is shown, not recorded: a video's `file_url` is on `s.` on some days and on `v.` on others (2026-10-31).
    var videoHost = ""
    discover("d14 video file to a media player", "d14-video-player-fetch", requires = video?.str("file_url"), "needs a video post", detail = { "host=$videoHost" }) { url ->
      val request = java.net.http.HttpRequest.newBuilder(java.net.URI(url))
        .header("User-Agent", "AppleCoreMedia/1.0.0 (Macintosh; U; Intel Mac OS X 15_0)")
        .header("Range", "bytes=0-1")
        .timeout(java.time.Duration.ofSeconds(20))
        .build()
      val response = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build()
        .send(request, java.net.http.HttpResponse.BodyHandlers.discarding())
      videoHost = java.net.URI(url).host
      val type = response.headers().firstValue("content-type").orElse("-")
      RawResponse(response.statusCode(), null, "", "", emptySet()) to "status=${response.statusCode()} content-type=$type"
    }
  }

  /**
   * A video's length for the grid's video badge (#80): whether a keyset list carries `video_duration` and `duration`,
   * whether they agree, whether they're whole seconds, and what images and GIFs carry there. The range goes to the
   * detail only, since it changes from run to run.
   */
  private suspend fun discoveryStep15() {
    val bearer = session.currentBearerTokens()?.accessToken
    val kinds = listOf("video" to "file_type:video", "animated" to "animated_gif", "image" to "file_type:image")
    for ((kind, tag) in kinds) {
      var range = ""
      discover("d15 $kind durations", "d15-$kind-duration", requires = Unit, "", detail = { range }) {
        val raw = authClient.probe(
          HttpMethod.Get, API_BASE, "/v2/posts/keyset", HeaderSet.POSTS, bearer = bearer,
          query = lang + mapOf("limit" to "20", "tags" to "$tag rating:s"),
        )
        val posts = ((raw.json as? JsonObject)?.get("data") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        if (posts.isEmpty()) fail("no posts")
        fun values(key: String) = posts.map { it.str(key)?.toDoubleOrNull() }
        val video = values("video_duration")
        val plain = values("duration")
        val present = video.filterNotNull().filter { it > 0 }
        // Over the durations there are (with none there's nothing to say), and in the detail only: which of 20 videos
        // happen to be whole seconds changes from run to run.
        val whole = present.count { it % 1.0 == 0.0 }
        range = if (present.isEmpty()) "" else "video_duration ${present.min()}..${present.max()}, whole seconds: $whole of ${present.size}"
        raw to listOf(
          "video_duration>0: ${share(present.size, posts.size)}",
          "null or 0: ${share(video.count { it == null || it == 0.0 }, posts.size)}",
          "same as duration: ${share(video.zip(plain).count { (a, b) -> a == b }, posts.size)}",
        ).joinToString(", ")
      }
    }
  }

  /** "all", "none" or "some": stable from run to run, unlike a count. */
  private fun share(count: Int, of: Int) = when (count) {
    of -> "all"
    0 -> "none"
    else -> "some"
  }

  /**
   * The home page's For you and Following feeds: the keyset list with `is_recommendation=true` or `is_following=true`.
   * The web app sends V2 with them; the app needs POSTS alone, so `file_url` stays. For you is also tried with the web
   * app's `x-sankaku-capabilities` header, and both signed out.
   */
  private suspend fun discoveryStep13() {
    val bearer = session.currentBearerTokens()?.accessToken
    // The same list without a flag, to tell a feed from a flag the server ignores. Shown, not recorded: the lists move.
    val plain = mapOf(true to feedProbe(null, bearer).postIds(), false to feedProbe(null, null).postIds())
    val feeds = listOf("for-you" to "is_recommendation", "following" to "is_following")
    for ((feed, flag) in feeds) {
      var ids = emptyList<String>()
      discover("d13 $feed (POSTS)", "d13-$feed", requires = bearer, "not signed in", { sameAsPlain(ids, plain[true]) }) {
        feedProbe(flag, it).also { raw -> ids = raw.postIds() }.let(::feedMarker)
      }
      discover("d13 $feed signed out", "d13-$feed-anonymous", requires = Unit, "", { sameAsPlain(ids, plain[false]) }) {
        feedProbe(flag, null).also { raw -> ids = raw.postIds() }.let(::feedMarker)
      }
    }
    discover("d13 for-you with the capabilities header", "d13-for-you-capabilities", requires = bearer, "not signed in") {
      feedMarker(feedProbe("is_recommendation", it, mapOf("x-sankaku-capabilities" to "recommendation-context-v2")))
    }
    // For you's second page, asked as the plain list pages (its `next` alone) and as the web app does (`page` too).
    // `next` alone answered page 1 again, with the same `next` (2026-10-31, #72), so the app sends both.
    discover("d13 for-you second page", "d13-for-you-paging", requires = Unit, "") {
      val first = feedProbe("is_recommendation", bearer)
      // Without a cursor there's no second page to ask for, and nothing below would be tested.
      val next = first.nextCursor() ?: return@discover first to "no next cursor"
      val byCursor = feedProbe("is_recommendation", bearer, next = next)
      val byPage = feedProbe("is_recommendation", bearer, next = next, page = 2)
      val repeats = byPage.postIds().count { it in first.postIds() }
      byPage to "by next alone: page 1 again=${yesNo(byCursor.postIds() == first.postIds())}, " +
        "same next=${yesNo(byCursor.nextCursor() == next)}; with page=2: new posts=${yesNo(repeats < byPage.postIds().size)}"
    }
  }

  private suspend fun feedProbe(
    flag: String?,
    bearer: String?,
    extraHeaders: Map<String, String> = emptyMap(),
    next: String? = null,
    page: Int? = null,
  ) = authClient.probe(
    HttpMethod.Get, API_BASE, "/v2/posts/keyset", HeaderSet.POSTS, extraHeaders, bearer = bearer,
    query = buildMap {
      putAll(lang)
      put("limit", "10")
      put("tags", "rating:s")
      put("hide_posts_in_books", "in-larger-tags")
      flag?.let { put(it, "true") }
      next?.let { put("next", it) }
      page?.let { put("page", it.toString()) }
    },
  )

  private fun RawResponse.nextCursor(): String? = ((json as? JsonObject)?.get("meta") as? JsonObject).str("next")

  private fun sameAsPlain(ids: List<String>, plain: List<String>?) =
    "same posts as without the flag: ${yesNo(ids.isNotEmpty() && ids == plain)}"

  /** Only what stays put from run to run: whether posts came back with `file_url`, and whether there's a next page. */
  private fun feedMarker(raw: RawResponse): Pair<RawResponse, String> {
    val first = raw.firstPost()
    val next = raw.nextCursor()
    return raw to "posts>0: ${yesNo(first != null)}, file_url=${yesNo(first.str("file_url") != null)}, next=${yesNo(next != null)}"
  }

  /**
   * Tag following, as the web app's tag chip does it: `{following_id, following_name, type: "tag"}` to
   * `/users/followings`, POST to follow and DELETE to unfollow, and `is_following` on a full post's tags. The follow
   * is undone in the same step.
   */
  private suspend fun discoveryStep11() {
    val bearer = session.currentBearerTokens()?.accessToken
    val reason = "needs a signed-in session and a post from step 1"
    var tag: JsonObject? = null
    discover("d11 is_following on a full post's tags", "d11-tags-is-following", requires = bearer?.let { anonPosts.firstOrNull() }, reason) { p ->
      val raw = authClient.probe(HttpMethod.Get, API_BASE, "/posts/${p.id}", HeaderSet.POSTS, bearer = bearer, query = lang)
      val tags = ((raw.json as? JsonObject)?.get("tags") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
      tag = tags.filter { it.str("is_following") == "false" }.maxByOrNull { it.str("post_count")?.toIntOrNull() ?: 0 }
      raw to "is_following on every tag=${yesNo(tags.isNotEmpty() && tags.all { it["is_following"] != null })}"
    }
    // No list of the user's follows was found: this answers 400 "The content type field is required." (also with
    // `content_type=tag`), and `/users/me/followings` answers 200 with no body. The app reads `is_following` instead.
    discover("d11 GET /users/followings", "d11-list-users-followings", requires = bearer, reason) {
      val raw = authClient.probe(HttpMethod.Get, API_BASE, "/users/followings", HeaderSet.V2, bearer = it, query = lang)
      raw to null
    }
    val body = tag?.let {
      buildJsonObject {
        put("following_id", it.str("id"))
        put("following_name", it.str("tagName"))
        put("type", "tag")
        put("content_type", "tag")
      }
    }
    discover("d11 follow, then unfollow a tag", "d11-follow-unfollow", requires = body, "needs a tag that isn't followed") {
      val follow = authClient.probe(HttpMethod.Post, API_BASE, "/users/followings", HeaderSet.V2, bearer = bearer, query = lang, body = it)
      val unfollow = authClient.probe(HttpMethod.Delete, API_BASE, "/users/followings", HeaderSet.V2, bearer = bearer, query = lang, body = it)
      if (unfollow.status !in 200..299) fail("unfollow answered ${unfollow.status}: unfollow the tag by hand")
      unfollow to "follow=${follow.status} success=${follow.json.str("success")}, unfollow success=${unfollow.json.str("success")}"
    }
  }

  /**
   * Voting: `user_vote` on a full post, then `PUT /posts/{id}/vote {score}` with V2, the post read again, and the
   * vote taken back with `DELETE` in the same step. Only on a post the account hasn't voted on, so nothing of the
   * user's is lost.
   */
  private suspend fun discoveryStep12() {
    val bearer = session.currentBearerTokens()?.accessToken
    var target: PostDto? = null
    discover("d12 user_vote on a full post", "d12-user-vote", requires = bearer?.let { anonPosts.firstOrNull() }, "needs a signed-in session and a post from step 1") { p ->
      val raw = authClient.probe(HttpMethod.Get, API_BASE, "/posts/${p.id}", HeaderSet.POSTS, bearer = bearer, query = lang)
      val vote = (raw.json as? JsonObject)?.get("user_vote")
      val unvoted = vote == null || vote.toString() == "null" || vote.toString() == "0"
      if (unvoted && env["SANKAKU_TRY_VOTE"] != "false") target = p
      raw to "user_vote present=${yesNo(vote != null)}"
    }
    discover("d12 vote, then take it back", "d12-vote-unvote", requires = target, "needs a post the account hasn't voted on") { p ->
      val vote = authClient.probe(
        HttpMethod.Put, API_BASE, "/posts/${p.id}/vote", HeaderSet.V2, bearer = bearer, query = lang,
        body = buildJsonObject { put("score", 4) },
      )
      val after = authClient.probe(HttpMethod.Get, API_BASE, "/posts/${p.id}", HeaderSet.POSTS, bearer = bearer, query = lang)
      val unvote = authClient.probe(HttpMethod.Delete, API_BASE, "/posts/${p.id}/vote", HeaderSet.V2, bearer = bearer, query = lang)
      if (unvote.status !in 200..299) fail("taking the vote back answered ${unvote.status}: remove it by hand")
      val userVote = (after.json as? JsonObject)?.str("user_vote")
      unvote to "vote=${vote.status} success=${vote.json.str("success")}, user_vote after=$userVote, unvote success=${unvote.json.str("success")}"
    }
  }

  /** Whether the keyset `id:` query takes several ids. The author-supplied endpoints are added here when known. */
  private suspend fun discoveryStep1() {
    val ids = anonPosts.map { it.id }
    val reason = "needs two posts from step 1"
    discover("d1 keyset multi-id (space)", "d1-multi-id-space", requires = ids.takeIf { it.size == 2 }, reason) {
      val raw = authClient.probe(
        HttpMethod.Get, API_BASE, "/v2/posts/keyset", HeaderSet.POSTS,
        query = mapOf("lang" to "en", "tags" to it.joinToString(" ") { id -> "id:$id" }),
      )
      raw to "posts=${raw.dataSize()}"
    }
    discover("d1 keyset multi-id (comma)", "d1-multi-id-comma", requires = ids.takeIf { it.size == 2 }, reason) {
      val raw = authClient.probe(
        HttpMethod.Get, API_BASE, "/v2/posts/keyset", HeaderSet.POSTS,
        query = mapOf("lang" to "en", "tags" to "id:${it.joinToString(",")}"),
      )
      raw to "posts=${raw.dataSize()}"
    }
    discover("d1 popular (order:popularity)", "d1-popular", requires = Unit, "") {
      val raw = authClient.probe(
        HttpMethod.Get, API_BASE, "/v2/posts/keyset", HeaderSet.POSTS,
        query = lang + mapOf("limit" to "2", "tags" to "order:popularity rating:s"),
      )
      raw to "posts>0: ${yesNo(raw.dataSize() > 0)}"
    }
    // A tag opened from a post searches by popularity, and the app sends filter tokens before the content tags (#89):
    // the first page must be the same as with the tag first.
    discover("d1 opened tag (order:popularity first)", "d1-opened-tag", requires = Unit, "") {
      suspend fun page(tags: String) = authClient.probe(
        HttpMethod.Get, API_BASE, "/v2/posts/keyset", HeaderSet.POSTS,
        query = lang + mapOf("limit" to "10", "tags" to tags),
      )
      val filtersFirst = page("order:popularity rating:s blue_hair")
      val tagFirst = page("blue_hair order:popularity rating:s")
      val ids = filtersFirst.postIds()
      filtersFirst to "posts>0: ${yesNo(ids.isNotEmpty())}, same as tag first: ${yesNo(ids == tagFirst.postIds())}"
    }
    discover("d1 /posts/{id}/fu (fresh URLs)", "d1-posts-fu", requires = anonPosts.firstOrNull(), reason) { p ->
      val raw = authClient.probe(HttpMethod.Get, API_BASE, "/posts/${p.id}/fu", HeaderSet.POSTS, query = lang)
      val data = (raw.json as? JsonObject)?.get("data")
      raw to "file_url=${yesNo(data.str("file_url") != null)}, sample_url=${yesNo(data.str("sample_url") != null)}"
    }
    var bookId: String? = null
    discover("d1 books list (poolseriesv2)", "d1-books-list", requires = Unit, "") {
      val raw = authClient.probe(
        HttpMethod.Get, API_BASE, "/poolseriesv2", HeaderSet.POSTS,
        query = lang + mapOf(
          "filledPools" to "true",
          "limit" to "2",
          "page" to "1",
          "tags" to "order:popularity rating:s",
          "includes[]" to "pools",
          "exceptStatuses[]" to "deleted",
        ),
      )
      val pools = ((raw.json as? JsonObject)?.get("pools") as? JsonObject)?.get("data") as? JsonArray
      bookId = (pools?.firstOrNull() as? JsonObject).str("id")
      raw to "pools>0: ${yesNo(!pools.isNullOrEmpty())}"
    }
    discover("d1 book pages (/pools/{id})", "d1-book-pages", requires = bookId, "needs a book from the list") { id ->
      val raw = authClient.probe(
        HttpMethod.Get, API_BASE, "/pools/$id", HeaderSet.POSTS,
        query = lang + mapOf("exceptStatuses[]" to "deleted"),
      )
      val first = ((raw.json as? JsonObject)?.get("posts") as? JsonArray)?.firstOrNull() as? JsonObject
      raw to "file_url=${yesNo(first.str("file_url") != null)}"
    }
    discover("d1 top (order:quality)", "d1-top", requires = Unit, "") {
      val raw = authClient.probe(
        HttpMethod.Get, API_BASE, "/v2/posts/keyset", HeaderSet.POSTS,
        query = lang + mapOf("limit" to "2", "tags" to "order:quality rating:s"),
      )
      raw to "posts>0: ${yesNo(raw.dataSize() > 0)}"
    }
    discover("d1 tag autocomplete", "d1-autocomplete", requires = Unit, "") {
      val raw = authClient.probe(
        HttpMethod.Get, API_BASE, "/tags/autosuggestCreating", HeaderSet.POSTS,
        query = lang + mapOf("tag" to "pok", "show_meta" to "1", "target" to "post", "show_rating" to "true"),
      )
      val first = (raw.json as? JsonArray)?.firstOrNull() as? JsonObject
      raw to "results>0: ${yesNo(first != null)}, tagName=${yesNo(first.str("tagName") != null)}"
    }
    discover("d1 over the tag limit (3 tags)", "d1-tag-limit", requires = session.currentBearerTokens(), "not signed in") {
      val raw = authClient.probe(
        HttpMethod.Get, API_BASE, "/v2/posts/keyset", HeaderSet.POSTS, bearer = it.accessToken,
        query = lang + mapOf("limit" to "1", "tags" to "blue_hair smile standing rating:s"),
      )
      raw to "posts=${raw.dataSize()}"
    }
    // Two ratings at once (G + R18) as one excluded rating; the cheatsheet exempts only `rating:safe` from the limit.
    discover("d1 two ratings (-rating:q)", "d1-rating-exclude", requires = session.currentBearerTokens(), "not signed in") {
      val raw = authClient.probe(
        HttpMethod.Get, API_BASE, "/v2/posts/keyset", HeaderSet.POSTS, bearer = it.accessToken,
        query = lang + mapOf("limit" to "40", "tags" to "blue_hair smile -rating:q"),
      )
      val ratings = ((raw.json as? JsonObject)?.get("data") as? JsonArray).orEmpty()
        .mapNotNull { post -> (post as? JsonObject).str("rating") }
      // Only what doesn't change as posts are uploaded: some posts came back, and none of them is rated q.
      raw to "posts>0: ${yesNo(ratings.isNotEmpty())}, rating q: ${yesNo("q" in ratings)}"
    }
    // With the app's limit: as of 2026-11-03 a small one (`limit=2`) answers `[]` for a post that has recommendations.
    discover("d1 recommended posts", "d1-recommended", requires = anonPosts.firstOrNull(), reason) { p ->
      val raw = authClient.probe(
        HttpMethod.Get, API_BASE, "/v2/posts", HeaderSet.POSTS,
        query = lang + mapOf(
          "tags" to "recommended_for_post:${p.id}",
          "limit" to PostsApi.RECOMMENDED_LIMIT.toString(),
          "page" to "1",
        ),
      )
      val first = (raw.json as? JsonArray)?.firstOrNull() as? JsonObject
      raw to (if (first == null) "posts=0" else "file_url=${yesNo(first.str("file_url") != null)}")
    }
  }

  private suspend fun discoveryStep2() {
    val bearer = session.currentBearerTokens()?.accessToken
    discover("d2 /posts/{id} without V2", "d2-posts-id", requires = post, "needs a post from step 6") { p ->
      val raw = authClient.probe(HttpMethod.Get, API_BASE, "/posts/${p.id}", HeaderSet.POSTS, bearer = bearer, query = lang)
      raw to "file_url=${yesNo(raw.json.str("file_url") != null)}"
    }
  }

  private suspend fun discoveryStep3() {
    val tokens = listOf(
      "malformed" to "not-a-token",
      "expired" to env["SANKAKU_EXPIRED_ACCESS_TOKEN"]?.takeIf { it.isNotBlank() },
    )
    for ((kind, token) in tokens) {
      val reason = "SANKAKU_EXPIRED_ACCESS_TOKEN not set"
      discover("d3 $kind token: users/me", "d3-$kind-users-me", requires = token, reason) {
        authClient.probe(HttpMethod.Get, API_BASE, "/users/me", bearer = it, query = lang) to null
      }
      discover("d3 $kind token: keyset", "d3-$kind-keyset", requires = token, reason) {
        val raw = authClient.probe(
          HttpMethod.Get, API_BASE, "/v2/posts/keyset", HeaderSet.POSTS, bearer = it,
          query = lang + mapOf("limit" to "1"),
        )
        raw to null
      }
    }
  }

  private suspend fun discoveryStep4() {
    val bearer = session.currentBearerTokens()?.accessToken
    discover("d4 favourites (fav:<name>)", "d4-favourites", requires = userName, "needs users/me from step 4") { name ->
      val raw = authClient.probe(
        HttpMethod.Get, API_BASE, "/v2/posts/keyset", HeaderSet.POSTS, bearer = bearer,
        query = lang + mapOf("limit" to "2", "tags" to "fav:$name"),
      )
      val first = raw.firstPost()
      raw to (if (first == null) "posts=0" else "file_url=${yesNo(first.str("file_url") != null)}")
    }
    // `fav:` alone timed out (408) on 2026-10-29. Variants for the M2 favourites grid: narrowed the way the site's
    // cheatsheet suggests, with the rating the app adds, and on the page-based endpoint.
    val variants = listOf(
      Triple("d4 favourites, narrowed (age:<1year)", "d4-favourites-age", "/v2/posts/keyset" to "age:<1year"),
      Triple("d4 favourites, with rating:s", "d4-favourites-rating", "/v2/posts/keyset" to "rating:s"),
      Triple("d4 favourites, page-based (/v2/posts)", "d4-favourites-paged", "/v2/posts" to ""),
    )
    for ((label, id, request) in variants) {
      val (path, extra) = request
      discover(label, id, requires = userName, "needs users/me from step 4") { name ->
        val query = lang + mapOf("limit" to "2", "tags" to "fav:$name $extra".trim()) +
          (if (path == "/v2/posts") mapOf("page" to "1") else emptyMap())
        val raw = authClient.probe(HttpMethod.Get, API_BASE, path, HeaderSet.POSTS, bearer = bearer, query = query)
        val first = raw.firstPost()
        raw to (if (first == null) "posts=0" else "file_url=${yesNo(first.str("file_url") != null)}")
      }
    }
    // The search panel's favourites filter (#90): its token goes first, before the content tags. Only what doesn't
    // change as the account favourites: some posts came back, and whether every one (Only) or none (Hide) is favourited.
    val filters = listOf(
      Triple("d4 only my favourites (fav:<name> first)", "d4-favourites-only", "fav:%s blue_hair"),
      Triple("d4 hide my favourites (-fav:<name>)", "d4-favourites-hide", "-fav:%s"),
      Triple("d4 hide my favourites with a tag", "d4-favourites-hide-tag", "-fav:%s blue_hair"),
    )
    for ((label, id, tags) in filters) {
      discover(label, id, requires = userName, "needs users/me from step 4") { name ->
        val raw = authClient.probe(
          HttpMethod.Get, API_BASE, "/v2/posts/keyset", HeaderSet.POSTS, bearer = bearer,
          query = lang + mapOf("limit" to "40", "tags" to tags.format(name)),
        )
        val favourited = ((raw.json as? JsonObject)?.get("data") as? JsonArray).orEmpty()
          .map { post -> (post as? JsonObject).str("is_favorited") == "true" }
        raw to "posts>0: ${yesNo(favourited.isNotEmpty())}, all favourited: ${yesNo(favourited.all { it })}, " +
          "any favourited: ${yesNo(favourited.any { it })}"
      }
    }
  }

  /** Favourite and unfavourite without V2. Restores the original state through the V2 path if needed. */
  private suspend fun discoveryStep5() {
    val bearer = session.currentBearerTokens()?.accessToken
    val target = post?.takeIf { env["SANKAKU_TRY_FAVOURITE"] != "false" && !it.isFavorited }
    val reason = "needs a non-favourite post and SANKAKU_TRY_FAVOURITE"
    var added = false
    discover("d5 favourite without V2", "d5-favourite", requires = target, reason) { p ->
      val raw = authClient.probe(HttpMethod.Post, API_BASE, "/posts/${p.id}/favorite", bearer = bearer, query = lang)
      added = raw.status in 200..299
      raw to "success=${raw.json.str("success")}"
    }
    discover("d5 unfavourite without V2", "d5-unfavourite", requires = target, reason) { p ->
      val raw = authClient.probe(HttpMethod.Delete, API_BASE, "/posts/${p.id}/favorite", bearer = bearer, query = lang)
      if (added && raw.status !in 200..299) runCatching { posts.unfavourite(p.id) }
      raw to "success=${raw.json.str("success")}"
    }
  }

  private suspend fun discoveryStep6() {
    val cases = listOf("made-up" to "not-a-real-refresh-token", "rotated" to preRefreshToken)
    for ((kind, token) in cases) {
      discover("d6 refresh with a $kind token", "d6-refresh-$kind", requires = token, "needs step 8") {
        val raw = authClient.probe(
          HttpMethod.Post, API_BASE, "/auth/token",
          body = buildJsonObject { put("refresh_token", it) },
        )
        raw to bodyMarker(raw)
      }
    }
  }

  private suspend fun discoveryStep8() {
    discover("d8 wrong password", "d8-wrong-password", requires = login.takeIf { it.isNotBlank() }, "SANKAKU_LOGIN not set") {
      val raw = authClient.probe(
        HttpMethod.Post, LOGIN_BASE, "/auth/token",
        body = buildJsonObject {
          put("login", it)
          putJsonObject("mfaParams") { put("login", it) }
          put("password", "wrong-password-from-the-smoke-test")
        },
      )
      raw to bodyMarker(raw)
    }
  }

  private suspend fun discoveryStep9() {
    discover("d9 rate-limit headers", "d9-rate-limit-headers", requires = Unit, "") {
      val raw = authClient.probe(
        HttpMethod.Get, API_BASE, "/v2/posts/keyset", HeaderSet.POSTS,
        query = lang + mapOf("limit" to "1"),
      )
      val names = raw.headerNames.filter { name -> RATE_HEADER.containsMatchIn(name) }.sorted()
      raw to "headers=${names.joinToString(",").ifEmpty { "none" }}"
    }
  }

  /**
   * Which single-post request returns every tag object: the keyset list carries only 5 `tags[]` but all
   * `tag_names`. The marker says whether `tags[]` covers `total_tags` and carries `type` and `post_count`; the
   * counts themselves vary by post, so they go only into the note.
   */
  private suspend fun discoveryStep10() {
    val bearer = session.currentBearerTokens()?.accessToken
    val reason = "needs a post from step 6"
    val candidates = listOf(
      Triple("keyset id:", "d10-tags-keyset-id", "/v2/posts/keyset") to HeaderSet.POSTS,
      Triple("/posts/{id} POSTS", "d10-tags-posts-id", "/posts/{id}") to HeaderSet.POSTS,
      Triple("/posts/{id} no set", "d10-tags-posts-id-plain", "/posts/{id}") to HeaderSet.NONE,
      Triple("/posts/{id} V2", "d10-tags-posts-id-v2", "/posts/{id}") to HeaderSet.V2,
      Triple("/v2/posts/{id} POSTS", "d10-tags-v2-posts-id", "/v2/posts/{id}") to HeaderSet.POSTS,
    )
    for ((names, headerSet) in candidates) {
      val (label, id, path) = names
      var counts = ""
      discover("d10 full tags: $label", id, requires = post, reason, detail = { counts }) { p ->
        val query = if (path.contains("keyset")) lang + mapOf("limit" to "1", "tags" to "id:${p.id}") else lang
        val raw = authClient.probe(
          HttpMethod.Get, API_BASE, path.replace("{id}", p.id), headerSet, bearer = bearer, query = query,
        )
        val obj = raw.firstPost() ?: (raw.json as? JsonObject)?.takeIf { it["id"] != null }
        val tags = (obj?.get("tags") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val total = obj.str("total_tags")?.toIntOrNull()
        val tagNames = (obj?.get("tag_names") as? JsonArray)?.size
        counts = "tags[]=${tags.size}, total_tags=${total ?: "-"}, tag_names=${tagNames ?: "-"}"
        val typed = tags.isNotEmpty() && tags.all { it["type"] != null && it["post_count"] != null }
        val marker = if (obj == null) "post=no" else
          "full=${yesNo(total != null && tags.size >= total)}, type+post_count=${yesNo(typed)}, file_url=${yesNo(obj.str("file_url") != null)}"
        raw to marker
      }
    }
  }

  /** Waits until a post's signed URLs have expired, then requests them on both image hosts. */
  private suspend fun discoveryStep7() {
    val first = anonPosts.firstOrNull()
    val urls = listOfNotNull(first?.previewUrl, first?.fileUrl)
    val expiry = urls.mapNotNull { Url(it).parameters["e"]?.toLongOrNull() }.maxOrNull()
    if (expiry != null) {
      val wait = Instant.fromEpochSeconds(expiry) + 1.minutes - Clock.System.now()
      if (wait.isPositive()) {
        println("Discovery step 7: waiting ${wait.inWholeMinutes} min for the signed URLs to expire…")
        delay(wait)
      }
    }
    for (url in urls) {
      val host = Url(url).host
      discover("d7 expired URL on $host", "d7-expired-${host.substringBefore('.')}", requires = url, "needs step 1") {
        val raw = authClient.probe(HttpMethod.Get, "", it, extraHeaders = mapOf("range" to "bytes=0-1023"))
        raw to "finalUrlSigned=${yesNo(Url(raw.finalUrl).parameters["e"] != null)}"
      }
    }
  }

  private suspend fun <T : Any> step(
    name: String,
    requires: T?,
    skipNote: String = "prerequisite failed",
    block: suspend (T) -> String,
  ) {
    if (requires == null) {
      report.add(name, StepResult(pass = true, host = "", status = "", note = skipNote, skipped = true))
      return
    }
    val before = log.entries.value.size
    val result = try {
      val note = block(requires)
      lastCall(before).let { (host, status) -> StepResult(true, host, status, note) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      val note = when (e) {
        is StepFailure, is ApiError -> e.message.orEmpty()
        is ImageRefusedException -> "image refused"
        else -> e::class.simpleName.orEmpty()
      }
      lastCall(before).let { (host, status) -> StepResult(false, host, status, note) }
    }
    report.add(name, result)
  }

  private suspend fun step(name: String, block: suspend (Unit) -> String) = step(name, Unit, block = block)

  /** A discovery step: probes, writes the shape, and compares the observation with `docs/api-recorded.json`. */
  private suspend fun <T : Any> discover(
    name: String,
    id: String,
    requires: T?,
    skipNote: String,
    detail: () -> String = { "" },
    showHint: Boolean = true,
    probe: suspend (T) -> Pair<RawResponse, String?>,
  ) = step(name, requires, skipNote) {
    val (raw, marker) = probe(it)
    report.writeShape(name, raw.json)
    val (pass, note) = recordings.check(id, Recorded(raw.status, raw.json.topKeys(), marker))
    if (!pass) fail(note)
    // [detail] is shown but not recorded: values that differ from run to run. [showHint] is off for a request that
    // carries a post id, in case an error body ever echoes the query.
    listOf(note, detail(), if (showHint) raw.hint else "").filter { it.isNotEmpty() }.joinToString("; ")
  }

  /** Host and status of the last request since [before], from the [RequestLog]. */
  private fun lastCall(before: Int): Pair<String, String> {
    val entry = log.entries.value.drop(before).lastOrNull() ?: return "" to ""
    return entry.host to (entry.status?.toString() ?: entry.errorClass ?: "error")
  }

  private fun urlNote(post: PostDto): String {
    if (post.fileUrl == null) fail("file_url missing")
    return "file_url=yes, sample_url=${yesNo(post.sampleUrl != null)}"
  }

  private fun bodyMarker(raw: RawResponse): String =
    listOf("success=${raw.json.str("success")}", raw.hint).filter { it.isNotEmpty() }.joinToString(", ")

  fun close() {
    mainClient.close()
    authClient.close()
  }

  private companion object {
    val lang = mapOf("lang" to "en")
    val RATE_HEADER = Regex("rate|limit|retry")
    const val FEED_PAGES = 5
    const val ARTIST_CANDIDATES = 10
    const val SNAPSHOT_REFRESH_SECONDS = 6 * 3600L
  }
}

private fun fail(note: String): Nothing = throw StepFailure(note)

private fun JsonElement?.str(key: String): String? =
  ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonElement?.topKeys(): List<String> = when (this) {
  is JsonObject -> keys.sorted()
  is JsonArray -> listOf("[]")
  else -> emptyList()
}

/** The first post of a keyset page (`{data: [...]}`) or of a page-based list (a bare array). */
private fun RawResponse.firstPost(): JsonObject? =
  (((json as? JsonObject)?.get("data") ?: json) as? JsonArray)?.firstOrNull() as? JsonObject

private fun RawResponse.postIds(): List<String> =
  ((json as? JsonObject)?.get("data") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject).str("id") }

private fun RawResponse.dataSize(): Int = ((json as? JsonObject)?.get("data") as? JsonArray)?.size ?: -1
