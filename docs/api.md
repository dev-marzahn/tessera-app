# Sankaku API

Sankaku's API is undocumented. This file records what this client relies on, and why. The values discovery steps
compare against are in [`api-recorded.json`](api-recorded.json), written by `./gradlew :cli:run` (see the README).

## Hosts

| Host | Role | Notes |
| --- | --- | --- |
| `login.sankakucomplex.com` | password login | nginx, no Cloudflare |
| `sankakuapi.com` | everything else | nginx, no Cloudflare; `lang=en` on every call |
| `s.sankakucomplex.com` | full files (`file_url`) | 200/206 with the signed URL, 302 without the query |
| `v.sankakucomplex.com` | previews and samples (BunnyCDN) | 200 with the signed URL, 403 without it |
| `api.github.com` | **not Sankaku:** the update check's latest release (#209) | its own client; see GitHub update check |

Every host rejects curl's default User-Agent (connection reset, or 403 on `v.`). The client always sends the
platform's Chrome User-Agent and client hints (see Browser fingerprint). No Referer or cookie is needed.

## Browser fingerprint (#204)

Decision 3 in #196: each platform claims to be that platform's current stable Chrome, as every Chrome there does since
the User-Agent reduction, and both use one Chrome major version (`CHROME_MAJOR_VERSION` in `BrowserFingerprint.kt`,
bumped on each release). `SankakuHttpClients` sets these on every request of both clients, API and image hosts
alike; endpoint headers stay in `apiRequest`.

| Header | Android | Desktop (Mac) |
| --- | --- | --- |
| `User-Agent` | `Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Mobile Safari/537.36` | `Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36` |
| `sec-ch-ua` | `"Not-A.Brand";v="99", "Google Chrome";v="157", "Chromium";v="157"` | the same |
| `sec-ch-ua-mobile` | `?1` | `?0` |
| `sec-ch-ua-platform` | `"Android"` | `"macOS"` |

- **Where the strings come from:** Chromium's `components/embedder_support/user_agent_utils.cc`. The reduced
  platform parts are `GetUnifiedPlatform()`'s (`Linux; Android 10; K` on an Android phone, `Macintosh; Intel Mac OS X
  10_15_7` on a Mac), and Android adds `Mobile` before `Safari`. `sec-ch-ua`'s order, GREASE brand and GREASE version
  are seeded by the major version (`GenerateBrandVersionList`), so `BrowserFingerprint.brandList` computes them and a
  version bump needs no hand-written list. Only the low-entropy hints are sent, as Chrome sends without an
  `Accept-CH` opt-in.
- **Version:** Chrome 157, the stable milestone from 2026-11-03 (Chromium Dash's schedule; Version History lists
  157.0.8103.26 on Mac's stable channel that day). The constant was already 157.
- **Live check (2026-11-03, `:cli:run`, signed in):** before the change (the Mac User-Agent alone) and after it
  (the desktop set with the hints), every step passed on all four hosts, with the same two skips (the expired-token
  steps).
- **An `Android` User-Agent gets numeric ids** (2026-11-03, anonymous, `rating:s`, Chrome 157 strings). Any
  User-Agent containing `Android` (with or without `Mobile`, with or without the client hints or the POSTS set) makes
  `sankakuapi.com` answer the real numeric ids: `data[].id`, `author.id`, `parent_id`, `tags[].creator.tag_id` and the
  autocomplete's `id` are JSON numbers. The Mac User-Agent, `X11; Linux x86_64` and the hints alone get the
  obfuscated string ids (11 letters and digits, not in id order, so the client can't convert one form into the
  other). It's the same posts (same `md5`s and tag names). With an `Android` User-Agent, `id:<id>` and
  `/posts/{id}/fu` take both forms; with the Mac User-Agent, a numeric id finds nothing (`id:` returns no posts, `/fu`
  is 400). Keyset cursors (`next`) are the same under both and give the same next page (compared by `md5`).
  - Before the DTOs took numbers, a debug build on the emulator sending the Android set got 200 JSON for the grid and
    showed "Unexpected response" (`ApiError.Decoding`). The post, parent, tag and favourite ids now decode from a
    string or a number and stay strings (`LenientStringSerializer`), so each platform keeps the form it's given.
  - `./gradlew :cli:run --args=--android` runs every step with the Android set: on 2026-11-03 all passed as with the
    desktop set (favourite, follow, vote and refresh with numeric ids included). Its cursor replays say "same page as
    when saved: no" for cursors saved by a desktop run only because they compare id digests.
  - **Ids saved before this change (Android only):** tabs saved with string ids still load (the API takes both forms),
    and Seen matches by `md5` too. Until such a tab is closed, a grid doesn't mark its post "In tab" and opening that
    post again opens a second tab; a saved grid place whose post id was a string reopens at the start of its page.
    Before the public release only the owner's phone has such tabs, so there's no migration.
- **Not covered:** video. Android's player (Media3) and desktop's fetch the file with their own media-player
  User-Agent, which the file hosts accept (Quirks → Video and animated posts), as a browser's player is its own client.
- **Known giveaway: the TLS and HTTP/2 fingerprint.** The handshake (JA3/JA4, ALPN, HTTP/2 SETTINGS and header order)
  is OkHttp's, not Chrome's, so a server that checks it can tell the client from Chrome whatever the headers say.
  Sankaku doesn't block on it today. If it starts to, the response is a TLS-impersonating client, which is a new
  design: `zhkl0228/impersonator` (OkHttp and BouncyCastle forks that imitate Chrome's handshake, LGPL-3.0) on both
  platforms, or Cronet (Chrome's own network stack) on Android.

## Header sets

| Set | Headers |
| --- | --- |
| POSTS | `enable-new-tag-type: true`, `expiration-policy: reduced`, `obfuscate-type: tag,wiki` |
| V2 | `accept: application/vnd.sankaku.api+json;v=2`, `api-version: 2`, `platform: web-app` |

Only `apiRequest` (`Request.kt`) sets these, from the table below.

## Endpoints

| Endpoint | Host | Auth | Header set | Request | Response keys used |
| --- | --- | --- | --- | --- | --- |
| `POST /auth/token` (password) | login | none | none | `{login, mfaParams: {login}, password}` | `success`, `access_token` (a login token, only good for the exchange) |
| `POST /sso/token-exchange` | API | none | none | `{access_token, client_id: "sankaku-web-app", url: "https://www.sankakucomplex.com"}` | `access_token`, `refresh_token`, `access_token_ttl`, `refresh_token_ttl`, `current_user.{id,name,avatar_url}` |
| `POST /auth/token` (refresh) | API | none | none | `{refresh_token}` | as the exchange |
| `GET /users/me` | API | Bearer | none (works with V2 too) | — | `user.{id,name,avatar_url}` |
| `GET /v2/posts/keyset` | API | optional | POSTS, **never V2** | `limit`, `tags`, `next`, `hide_posts_in_books=in-larger-tags` | `meta.next`, `data[]` (see Posts) |
| `GET /v2/posts/keyset?tags=id:<id>` | API | optional | POSTS | — | fresh URLs for one post (still only 5 `tags[]`); with `rating:s` added, nothing for a post of another rating (the sheet's Parent strip, `PostsApi.byIdOrNull`, smoke step 13) |
| `GET /v2/posts/keyset?tags=parent:<id>` | API | optional | POSTS | `limit=20`, plus `rating:s` when the rating is locked | the post's children only, not the parent itself, with `file_url` (the sheet's Children strip, `PostsApi.children`, smoke step 13) |
| `GET /posts/{id}` | API | Bearer | POSTS | — | a bare post object (no `data` wrapper) with **every** `tags[]` object, `tag_names`, `total_tags` and `file_url`/`sample_url` (`PostsApi.full`, discovery step 10) |
| `POST` / `DELETE /posts/{id}/favorite` | API | Bearer | none (works without V2) | — | `success`, `post_id` (`score` is the vote score, not the favourite count) |
| `GET /posts/{id}/fu` | API | optional | the web app sends V2 | — | `data.{file_url, sample_url, fallback_url}`: fresh URLs for one post |
| `GET /poolseriesv2` | API | optional | the web app sends V2 | `tags`, `limit`, `page`, `offset=0`, `filledPools=true`, `includes[]=pools`, `exceptStatuses[]=deleted` | `pools.data[]` (books), `pools.meta.next`, `series`, `page` |
| `GET /pools/{id}` | API | optional | the web app sends V2 | `exceptStatuses[]=deleted` | book fields plus `posts[]`, full posts in reading order, **with** `file_url`/`sample_url` even under V2 |
| `PUT /pools/{id}/reading` | API | Bearer | V2 | `{sequence}` | reading progress (not used in v1) |
| `POST` / `DELETE /pools/{id}/favorite` | API | Bearer | V2 | — | book favourite (not used in v1) |
| `PUT` / `DELETE /posts/{id}/vote` | API | Bearer | V2 | `{score: 1..}` | `success`, `post_id`, `vote_count`, `score`. Works for a new account (discovery `d12-vote-unvote`, 2026-10-30): a `PUT` of 4 left `user_vote` 4 on `/posts/{id}`, and `DELETE` took it back. Only a full post carries `user_vote` (null when not voted). The meaning of the response's `score` is unconfirmed, so the app uses only `vote_count` |
| `POST /posts/reaction` | API | Bearer | the web app sends V2 | `{post_id, reaction}` | toggles: the first call returns `data: {id, user_id, name, reaction, …}`, the same call again removes it (`data: null`) |
| `GET /v2/posts?tags=recommended_for_post:<id>` | API | optional | the web app sends V2; this client sends POSTS (`PostsApi.recommended`, smoke step 12) | the web app: `page`, `limit=40`, `default_threshold=2`; this client: `page=1`, `limit=20`, plus `rating:s` in `tags` when the rating is locked | a plain array of posts (not `{meta, data}`); page-based; with V2 only `preview_url`, with POSTS also `file_url` |
| `POST /recommendations/interactions` | API | Bearer | V2 | array of `{event: "auto_view", item_id, item_type, source_page, source_function, client}` | the web app's view tracking; this client doesn't send it |
| `GET /tag-and-wiki/name/{tag}` | API | optional | the web app sends V2 | — | `tag.{name, tagName, type, post_count, alias_tags, implied_tags, related_tags, …}`, `wiki.{title, body, updated_at}` |
| `GET` / `POST` / `PUT` / `DELETE /users/blacklist[/{id}]` | API | Bearer | V2 | GET: `?lang=en&limit=40&page=N&entity=tags`. POST a post rule: `{type: "post", content_id, visibility: "hide"}`; a tag rule (HAR 2026-10-30): `{type: "tag", tags: ["crow_(anthro)"], visibility: "hide"}`. PUT `/{id}` edits one: `{tags, visibility}`, `visibility` `hide` or `blur` | GET: `list[]` (`{id, user_id, tags, type, visibility, content_id}`), `records[]` (the tags, as full tag objects), `inactive_items[]`, `count` (`row_count`, `used_rules`, `total_tags`, `total_posts`, …); a page past the end has empty lists and the same `count`. POST and PUT: `new_blacklist` (the rule), `count`, and POST also `blacklisted_tags` (every tag rule's tags). Deleting a system rule: 400 `snackbar__insufficient_privileges` |
| `GET /privileges` | API | Bearer | V2 | — | reputation rewards with `is_claimed`/`is_active` (see Search limits) |
| `GET /users/autosuggest` | API | Bearer | V2 | `name`, `public_favs`, `include_avatar` | user-name suggestions (not tag autocomplete) |
| `GET /tags/autosuggestCreating` | API | optional | the web app sends V2; this client sends POSTS (`TagsApi`, smoke step 10) | `tag=<prefix>`, `show_meta=1`, `rating=s`, `target=post`, `show_rating=true` | array of `{tagName, name, name_en, type, count, post_count, rating}`; `rating` is the tag's age rating, `s`/`q`/`e` (smoke step 10 checks the codes) |
| `POST` / `DELETE /users/followings` | API | Bearer | V2 (`TagsApi.follow`/`unfollow`, smoke step 11) | `{following_id: <tag id>, following_name: <tagName>, type: "tag", content_type: "tag"}`, the same body for both | `success` (no other keys). Whether a tag is followed is `is_following` on a full post's `tags[]` (discovery step 11) |
| `GET /tags?related=<tags>` | API | optional | the web app sends V2 | `related` (space-separated), `rating` | related tags (not used in v1) |
| `GET /tags?isTrending=true` | API | optional | the web app sends V2 | `page`, `limit`, `rating` | trending tags (not used in v1) |

`/users/check-refresh-token` isn't used: refreshing directly answers the same question in one round trip.

### Posts

Fields the app decodes: `id` (string), `md5`, `author.name`, `rating`, `status`, `file_type` (MIME, e.g. `video/mp4`),
`file_ext`, `preview_url`/`sample_url`/`file_url` with `_width`/`_height` (`width`/`height` for the file),
`is_favorited`, `fav_count`, `vote_count`, `total_score`, `file_size`, `video_duration`, `parent_id`, `has_children`,
`redirect_to_signup`, `tags[].{name,tagName,type,post_count}`, `tag_names`, `created_at`.

A tag's `name` is its display name ("Gurande (G-Size)"); `tagName` is what a search uses ("gurande_(g-size)").

## Quirks

- **The V2 headers strip image URLs.** With them, posts (lists and `/posts/{id}`) carry only `preview_url`. With the
  Bearer token alone, or plus the POSTS headers, `file_url` and `sample_url` come back. Which of the three V2 headers
  causes it is unknown. `/v2/posts/{id}` returns no post.
- **Post lists carry only 5 tag objects.** `/v2/posts/keyset` (the `id:<id>` query included) returns 5 `tags[]`
  per post, while `tag_names` (search names only, no type or count) and `total_tags` cover all of them (47–107 in a
  sample of 10). `GET /posts/{id}` returns every `tags[]` object with `type` and `post_count`, with the POSTS set or
  none; with V2 it does too, but without `file_url`. `/v2/posts/{id}` returns no post (discovery step 10,
  2026-10-29: 5 vs 59 for one post). The viewer shows `tag_names` until the full post arrives.
- **Refresh rotates the refresh token, and the old one is then rejected with 401** (discovery step 6, 2026-10-29;
  Phase 0 saw it stay valid through `check-refresh-token`). So two concurrent refreshes would sign the user out:
  refresh must stay single-flight.
- **Token lifetimes come from the server:** `access_token_ttl=604800` (7 days), `refresh_token_ttl=7776000` (90 days),
  both in seconds.
- **`current_user.id` is a number from the login host and a string from the API.**
- **Signed image URLs expire about an hour after the post was fetched.** The query has `e` (Unix expiry) and `m`
  (signature). Images are cached by md5, never by URL.
- **`created_at` is an object**, `{json_class, s, n}`: seconds and nanoseconds.
- **One anonymous image request to `s.` timed out on connect** in Phase 0, then worked. Image loads retry twice.
- **`mfa_method`** is an unexplained integer on the user object. 2FA login is untested.
- **Previews are AVIF** (`/data/preview/xx/xx/<md5>.avif` on `v.`). All 1,668 previews in the 2026-10-29 HAR were
  AVIF. Skia can't decode them; desktop decodes them through macOS ImageIO (`AvifDecoder`).
- **Signed out, a search has no rating filter**, and most results are hidden: on 2026-10-29 an anonymous page of 30
  had 24 posts with `redirect_to_signup: true` and no `preview_url`, `sample_url` or `file_url`. Refetching them
  doesn't help, so the app shows "Sign in to view" and never requests them. With `rating:s`, 1 of 30 was hidden.
  The site adds `rating:s` for signed-out users, and so does the app (when the search has no `rating:` tag).
- **Coil caches error responses by default** (a 404 or 410 is cacheable by HTTP heuristics), so a refused image would
  come back from the disk cache on the retry. The app's `ImmutableCacheStrategy` writes only 2xx responses.
- **A grey post isn't the API** (#119, 2026-11-01): the owner's grey cell and viewer were a uniform `#D6D6D6`, the
  colour of the whole page averaged into one pixel. The app had decoded the sample at 1 px while the phone's details
  sheet was full (the image's room is 1 px tall then), and that copy stood in for the image under its md5 memory key.
  No host answered with a placeholder and no URL had expired; no request changed, so `:cli` (run before: all steps
  match except `d13 following signed out`, whose `file_url` is now absent) wasn't rerun. A failed image now shows
  "Couldn't load" with Retry, and a post the refetch still gives no URL "No preview".
- **Signed sample URLs from `/fu` carry extra parameters** (`expires`, `token`) besides `e` and `m`; full files from
  `/fu` live under `s.sankakucomplex.com/o/…`.
- **Heavy searches can time out with 408** and `code: snackbar__account_search-query-timeout`. The web app hit it on
  `threshold:4 rating:s`, and `:cli` hit it on `fav:<name>` (limit 2) on 2026-10-29, but not on 2026-10-30 (discovery
  step 4). The favourites page also timed out in the browser on the same (non-premium) account, so it isn't this
  client: it's the search-time limit (see Search limits), and it comes and goes with load. `checkResponse` maps a 408
  with that `code` to `ApiError.SearchTimeout` (any other 408 stays `Http`); the grid shows "Sankaku took too long to
  search" with Retry and never retries on its own. On 2026-10-30 neither search gave the 408 in the desktop app:
  `fav:<name>` loaded, and `threshold:4` sent no answer within the 30 s request timeout, so it showed as a network
  error ("Can't reach Sankaku") with Retry.
- **Signed out, even `rating:s` has hidden posts:** on 2026-10-30 the newest `rating:s` post had
  `redirect_to_signup`, so `:cli` step 1 takes the first two visible posts of 10.
- **Recommended posts** (`recommended_for_post:<id>` on `/v2/posts`) carry `file_url` since 2026-10-30; on 2026-10-29
  they didn't. Smoke step 12 (2026-10-31, signed in, POSTS, `limit=20`): 20 posts, every one with `preview_url`, none
  the post itself; with `rating:s` added to `tags`, 20 posts, all rated `s`, so the search tag narrows the
  recommendations and the signed-out lock works on them. The details' strip asks once, when it comes on screen, and
  sends no `/recommendations/interactions`. They carry the account's `is_favorited`: smoke step 14 (2026-11-03,
  signed in) favourited a recommendation, asked again (still recommended, `is_favorited` true), unfavourited it and
  asked again (false). So the strips' hearts start from the list, with no request per thumbnail. A small `limit`
  answers `[]`: on 2026-11-03 (#213), signed out and signed in, `limit=2` gave an empty array for the newest post while
  `limit=20` gave 11 posts for the same post (step 12), so `:cli`'s d1 probe asks with the app's `limit=20`.
- **Parent and children** (#342): `parent:<id>` returns the children only, not the parent. Smoke step 13 (2026-11-03,
  signed in, `rating:s`, `limit=100`): 3 of 95 posts had a `parent_id` and 4 `has_children`; the first child's parent
  had `has_children`, `parent:<parent>` returned 1 post (the child, with `file_url` and `preview_url`, its `parent_id`
  the parent's), and with `rating:s` added the same 1. `id:<parent> rating:s` returns the parent only when it's rated
  `s`. `child:any` with `rating:s` returned no post with `has_children` in 10, so the step starts from a child.
- **Reactions are emoji shortcodes** (the web app uses the emoji-mart picker with the Apple set): `heart`, `heart_eyes`,
  `+1`, `joy`, `skull`, `cry`, `cupid`, … Each post's `reactions[]` lists every user's reaction with their name and
  avatar.
- **Save downloads `file_url` as it is** (`OriginalFiles`, #31): the signed URL the app holds, or the one a keyset
  `id:<id>` refetch (POSTS, never V2) returns when it's missing or expired, on the main client without a bearer token.
  On 2026-10-31, signed out with `rating:s`, with no Referer or cookie: a 4000 × 4000 JPEG on desktop came back 200
  from `s.sankakucomplex.com/o/{md5}` (request log), and a GIF and a 29.6 MB MP4 on the Android emulator arrived whole
  (they start `GIF89a` and with an `ftyp` box). No endpoint changed, so `:cli` wasn't rerun.
- **`browsing-limit-*` response headers** suggest per-platform browsing limits for posts, books and series.

## The official web app (HAR, 2026-10-29)

How `www.sankakucomplex.com` itself calls the API, from a sanitized HAR (no auth headers or cookies) of a signed-in
session that favourited, voted, filtered and browsed books:

- **It sends V2 and POSTS (with `obfuscate-type: tag,wiki,comment`) plus `client-type: non-premium` on every call.**
  So its post lists have no `sample_url`/`file_url`; it fetches them per post from `/posts/{id}/fu`.
- **Post lists:** `/v2/posts/keyset` with `limit=40`, `page`, `next`, `default_threshold=4`,
  `hide_posts_in_books=in-larger-tags`. Extra flags: `is_recommendation=true` (the recommended feed, with an
  `x-sankaku-capabilities: recommendation-context-v2` header) and `is_following=true`.
- **The home page's sections** (HAR 2026-10-30, signed in, `rating:s`): **For you** is the keyset list with
  `is_recommendation=true`, **Following** the same with `is_following=true`, both with the search's tags as usual.
  **Trending** is `order:popularity`, the same as the app's Popular. Every page is 200 with `data[]` and `meta.next`.
  As the web app scrolls these, it posts `POST /recommendations/interactions` (an array of `auto_view` events per post
  id, with `source_page` and `recommend_method`; returns `success`); the app doesn't need to send them.
- **The feeds with the app's headers** (discovery step 13, 2026-10-30, `rating:s`, `limit=10`): both flags work with
  POSTS alone, with `file_url` and a `meta.next` cursor, and the `x-sankaku-capabilities` header changes nothing the
  step checks, so the app doesn't send it. Each feed's first page differs from the same list without the flag, so the
  flags filter, except **Following signed out**, which returns the plain list (the flag is ignored). **For you signed
  out** still differs from the plain list. The app sends a feed as the flag, from its `feed:` tag. In the app, For you
  with `file_type:video` once answered a page with the same `meta.next` it was asked with; `KeysetPagingSource` ends
  the grid on a cursor that doesn't move.
- **For you pages by `page`, not `next`** (discovery `d13-for-you-paging`, 2026-10-31, #72; signed in and out alike):
  asked with only the `next` it gave, it answers page 1 again with that same `next`, so the app showed every post
  twice and its grid crashed on the repeated keys once Top scrolled both copies into view. With `page=2` (and `next`,
  as the web app sends both) it answers new posts and a new `next`. Its pages still repeat a few posts each (0 to 4 of
  30 in a row of 6 pages), so `KeysetPagingSource` drops a post an earlier page had. Its responses also carry
  recommender fields (`experiment_id`, `experiment_arm`, `variant`, `model_version`, …) the app ignores. It doesn't
  end: page 300 still had 22 posts, and `meta.page_count` is 0. The plain list, a tag search, `order:popularity`,
  `order:quality` and Following return the same first 4 pages with `page` as without it, so `PostsApi.keyset` sends
  `page` on every list.
- **Video and animated posts** (discovery step 14, 2026-10-30, `rating:s`): `file_type:video` posts are `video/mp4`
  with `file_ext` `mp4`, and their `sample_url` is an mp4 too; `animated_gif` posts are `image/gif` with a gif sample.
  The `s.` host answers a ranged request with a media player's User-Agent (AVPlayer's) with 206 `video/mp4`, so the
  player can stream the URL itself. `file_type:flash` timed out (408 search-query-timeout), so it isn't a step.
  On 2026-10-31 a video's `file_url` was on `v.` instead, with the same 206 `video/mp4`.
- **A video's length is in the list** (discovery step 15, 2026-10-31, `rating:s`, 20 posts each, keyset with POSTS):
  every video and every animated GIF has `video_duration`, in seconds with a fraction (3.9 to 127.66 for videos, 0.4
  to 15.36 for GIFs), and `duration` with the same value. Images have it null or 0. The grid's video badge shows
  `video_duration` as the player does (`02:07`), so it needs no extra request.
- **A keyset page can come back empty with a `next` cursor:** on 2026-10-30, signed in, `rating:s` with `limit` 2 and
  10 returned no posts but a cursor (the server filters after the limit: posts in books, or the account's blacklist).
  `KeysetPagingSource` and smoke step 5 skip up to 5 such pages in a row, or a grid would end as "no results".
- **Two ratings at once** are one excluded rating: signed in, `blue_hair smile -rating:q` returned 26 posts, 24 `e`
  and 2 `s`, none `q` (discovery `d1-rating-exclude`, 2026-10-30). So G + R18 is `-rating:q`, and the rating still
  costs one tag at most.
- **Own favourites as a filter** (#90): `fav:<name>` keeps only the account's favourites and `-fav:<name>` leaves them
  out, also first before a content tag. Signed in, `fav:<name> blue_hair` returned posts that were all
  `is_favorited`, and `-fav:<name>` and `-fav:<name> blue_hair` returned posts none of which were (limit 40; discovery
  `d4-favourites-only`, `d4-favourites-hide`, `d4-favourites-hide-tag`, 2026-10-31). The search panel's Favourites
  chip sends these.
- **Popular** is `tags=order:popularity rating:s`; **Top** is `tags=order:quality rating:s`. Filters are plain tags: `threshold:4`, `file_type:video`,
  `duration:0..60`, `long_image`, `fav:<name>`.
- **Tag order doesn't matter** (discovery `d1-opened-tag`, 2026-10-31, #89, signed out, `limit=10`):
  `order:popularity rating:s blue_hair` returns the same first page as `blue_hair order:popularity rating:s`. So the
  app sends filter tokens first, as the search line shows them (`SearchTags.filtersFirst`). A tag opened from a post
  is `order:popularity <tag>`; the API has no other way to sort by popularity.
- **Books:** the list is `/poolseriesv2` (`order:popularity` or `order:date`, plus `english_language`); a book is
  `/pools/{id}`, whose `posts` are the pages.
- **Tag metadata** goes through `POST /graphql` (`TagTranslationsV2`, `tagTypes`) and `/tag-and-wiki/name/{tag}`.
- **Autocomplete** (second HAR) calls `/tags/autosuggestCreating` on every keystroke from the first character. A
  leading `-` (exclude) is passed through: `tag=-te` returns entries whose `name` is `-text` while `tagName` stays
  `text`. The search then sends the display names (`tags=Pokemon -Text`), which the API accepts; this client sends
  `tagName`. `type` is the tag category (3 copyright, 14 character-ish, 16 species, 21 meta in the samples).

## Following's order, keyset cursors and jumping deep (#160)

Discovery steps 16 to 18 (2026-11-01, signed in, `rating:s`, `limit=40`, the app's POSTS headers), for the tab queue's
"keeps its place" and Following marks (#158). The steps read Following's first 5 pages (191 posts, 3 to 204 days old;
there were more). What a step re-checks on every run is in `api-recorded.json`; the rest, marked *(exploratory)*, was
seen in one-off runs of the same calls and isn't a step.

**Following's order.**

- **Newest first by `created_at`, nearly**, the same as the plain list (step 16, every run; it allows 1 post in 50 out
  of place). Following's first 191 posts were in order (`created_at.s`, whole seconds; no two equal *(exploratory)*),
  and so were 464 over 12 pages *(exploratory)*; none twice across pages, a `next` on every page. If the feed were
  ordered by when the followed tag was added to a post, `created_at` would run out of order all the time, and it
  didn't.
- **But not exactly:** in the plain list, 1 post of 457 (12 pages) sat after an older one, 59 minutes newer than the
  post above it *(exploratory; step 16 failed once on it before the allowance)*. So the order is by some key that
  almost always equals `created_at` (probably the site's own upload id, which is hidden). A mark kept as "newer than
  `created_at` T" can be wrong by up to an hour around T; a mark kept as a cursor or as a post is exact.
- **Post ids can't be compared.** They are 11-character alphanumeric strings, not numbers, and did not sort in feed
  order *(exploratory)*. Only `created_at` has an order. The `change` field isn't the order either (62 out-of-order
  pairs among the 191; step 16 prints this).
- **A late-tagged or late-approved post** is expected to sit where its `created_at` puts it, below newer posts, so it
  would not show at the top. The plain list's one out-of-place post is the closest sighting. Not measured directly:
  step 16 saves the top of Following in the ledger (below) and, on a later run, counts the posts that are new in the
  span it covered and sit below its newest post. On the test account that was 0 of 0 over 5 hours, because its
  Following got no new post at all (see the table at the end).

**Keyset cursors** (`meta.next`).

- **A cursor is opaque:** 32 `base64url` characters, 24 bytes that read as neither text nor JSON, and the page's post
  ids appear in neither the encoded nor (if it were readable) the decoded form (steps 17, every run). Nearly any
  change is caught: a made-up or one-character-changed cursor is rejected with 400 `{code: "invalid-parameters"}` (step
  17 changes 4 positions and expects at least 3 rejected; 4 of 4 were; in one full sweep, 29 of 32 positions were
  rejected and 3 ignored *(exploratory)*). A truncated one is a 400 too *(exploratory)*. An empty `next` is ignored and
  gives the first page.
- **It isn't single-use:** the same cursor answers the same page again (step 17). With a smaller `limit` the page was a
  prefix of the larger one, and `page=` made no difference to Following's pages *(exploratory)*.
- **It marks a position in time, not a query.** A Following cursor given to the plain list (`rating:s`, no flag)
  continued there: the first post was about 7 seconds older than the last post before the cursor (step 17, every run;
  it expects the page to be older than the cursor and within 6 hours of it). So a stored cursor can continue another
  search from where a grid stopped.
- **How long it lives:** step 17 replays the cursors earlier runs saved (three kinds: Following 191 posts deep, the
  plain list 120 deep, one artist tag), once per run each, and prints the age, the status, and whether the page is the
  one saved. The results are in the table at the end. **Days are still unknown:** a replay needs a cursor that old.
  The ones saved on 2026-11-01 can be replayed by any `:cli` run on that machine from 2026-11-04 (3 days) and
  2026-11-08 (7 days); the ledger keeps them 30 days.
- **An `order:popularity` cursor** (from a tapped tag) also replays fine after an app restart, per the grid-tab place
  work (#162, PR #171). That is not a `:cli` step and its age isn't recorded here.

**Jumping deep without a cursor.** The ticket's `id:<N` can't work, since ids aren't numbers.

| Search | Result |
| --- | --- |
| `id:<id`, `id:<=id` | 500 `server-error__sankaku-error`, with Following (step 18); `id:<id` also with an artist tag |
| `id_range:..id` | 408 `snackbar__account_search-invalid-structure`, with Following and with an artist tag (step 18) |
| `age:<1year`, `age:>1year` | 200 with no posts, on the plain list (step 18; so it filters nothing useful, as step 4 saw) |
| `date:A..B` | works, see below (step 18) |
| `date:<A`, `date:>A`, `date:..A`, `date:A..`, `date:A`, `date:2026-05`, a Unix time | 400 `invalid-parameters` *(exploratory)* |

`date:A..B` is a closed range of UTC times, written `YYYY-MM-DD` or `YYYY-MM-DDTHH:MM:SS` (a trailing `Z` and `/` in
the date are accepted too *(exploratory)*):

- **The resolution is the UTC hour.** A bound inside an hour acts as that hour's start: `..T` gave the same page as
  `..hour(T)` (step 18, on a post that has an older post in the same hour), so it leaves out posts of T's own hour. The
  range is `[hour(A), hour(B))`: a post in `[hour, hour + 1 h)` is found and nothing outside the range comes back
  (step 18); `date:A..A` is empty *(exploratory)*.
- **It combines with Following** (`is_following=true`) and with an artist tag (`rating:s <artist> date:A..B`, 3 tags,
  accepted), and keyset paging goes on inside the range: page 2 (with `next` and `page=2`) was older than page 1 and
  inside the range (step 18).
- **To open a grid at a stop mark with upload time T:** search `date:<far past>..<hour(T) + 1 h>` (the same flags and
  tags) and skip posts until the mark's id. Posts of the same hour that are newer than the mark come first, so the
  mark is within the first hour's worth of posts. A busy search such as the plain list holds about a page of posts per
  hour (a page of 40 ended within one hour *(exploratory)*), so the mark can be several pages in there; Following and
  one artist hold few.

The ledger: steps 16 and 17 keep state between runs in `~/.sankaku-cli/feed-ledger.json` (or `SANKAKU_FEED_LEDGER`),
outside the repository: up to 8 saved cursors per kind (a new one when the last is 90 minutes old, none kept past 30
days) and the hashed ids of Following's top. A run replays every cursor at least a minute old before saving new ones.

## Tag types

`type` on a tag, with the web app's colours (from its `configs/constants/tag-types.js`; `TagType` and
`ui/theme/TagColors.kt` mirror it). 6 and 7 are unused.

| Id | Type | Colour | Id | Type | Colour |
| --- | --- | --- | --- | --- | --- |
| 0 | General | `#FB8A00` | 13 | Activity | `#E9B800` |
| 1 | Artist | `#FF3620` | 14 | Role | `#B6C8D8` |
| 2 | Studio | `#E61700` | 15 | Flora | `#009E2E` |
| 3 | Copyright (franchise) | `#821B7B` | 16 | Fauna | `#00C53A` |
| 4 | Character | `#A3229A` | 17 | Entity | `#00EC46` |
| 5 | Genre | `#B698FF` | 18 | Object | `#016E4E` |
| 8 | Medium | `#98AEFF` | 19 | Substance | `#018961` |
| 9 | Meta | `#0042BD` | 20 | Setting | `#01A474` |
| 10 | Fashion | `#EE005B` | 21 | Language | `#1C6CFF` |
| 11 | Anatomy | `#9B7A00` | 22 | Automatic | `#0053EC` |
| 12 | Pose | `#C29900` | 23 | Other | `#AAAAAA` |

## Web app constants

From the web app's `env.js` (2026-10-29): `API_VERSION: 2`, `CLIENT_ID: "sankaku-web-app"`, `RECOMMENDED_LIMIT: 40`
(its page size), `DEFAULT_THRESHOLD: 2`, `DEFAULT_THEME: "black"`, `NOT_LOGGED_USER_TAG_LIMIT: 10`,
`SSO_AUTOLOGIN_DAYS: 45`, `IS_PREMIUM_CLIENT: false`.

## Sign-up and terms (browser, 2026-11-03, #211)

Checked in a real browser, without signing in; details in `docs/research/public-release-distribution.md` section 8.

- **Sign-up:** <https://login.sankakucomplex.com/registration> redirects to the "Create account" form with a fresh
  per-visit session, so that bare URL is what "Create an account" opens in the system browser. The app sends no
  registration request.
- **Terms:** <https://legal.sankakucomplex.com/terms-of-service> ("Last Updated: July 11th, 2026"). Its Rules of
  Conduct list, unnumbered, as Prohibited Conduct: "Any act which imposes excessive burden on the servers or
  administration of the Platform, or which could potentially cause such burden directly or indirectly." and "Use of
  the Platform or its constituent API in conjunction with unauthorized User software, or unauthorized access to the
  Platform generally." The remedies are restricting or terminating use of the Platform, including by blocking IP
  addresses.

## GitHub update check (non-Sankaku host, #209)

The only host the app contacts besides Sankaku's (decision 16 in #196). `UpdateCheck` sends
`GET https://api.github.com/repos/<PUBLIC_REPO>/releases/latest` (`PUBLIC_REPO` is `dev-marzahn/tessera-app`,
`PublicRepo.kt`).

- **When:** once the first-launch screen is accepted, at launch, at most once every 24 hours, and never while
  Settings → Updates → "Check for updates" is off. The time of the last answer and the release it named are kept in
  `updates.json`.
- **The client:** `createGitHubClient` shares only the OkHttp engine with Sankaku's clients and none of their plugins.
  So it sends no bearer token, no cookie, no Sankaku header set, and none of the browser fingerprint (no Chrome
  User-Agent, no `sec-ch-ua*`). It sends only `User-Agent: Tessera/<version>`, `Accept: application/vnd.github+json`
  and `X-GitHub-Api-Version: 2022-11-28`. There is no token, so GitHub's unauthenticated limit applies: 60 requests an
  hour per IP, far above once a day. It isn't in the request log or the rate limiter, which are Sankaku's.
- **Answers (curl, 2026-11-03):**
  - **404** `{"message":"Not Found",…}`: the mirror has no release yet (or isn't public yet). This counts as an answer
    with no update. It's silent, and the next check is a day later.
  - **200:** fields used are `tag_name` (`vX.Y.Z`, compared as three numbers with `APP_VERSION`) and `html_url` (the
    release page, used only if it's under `https://github.com/<PUBLIC_REPO>/releases/`, otherwise the mirror's
    `/releases/latest`). `releases/latest` never returns drafts or pre-releases. Checked against `cli/cli`, whose
    latest release answered `"tag_name":"v2.103.3"` and `"html_url":"https://github.com/cli/cli/releases/tag/v2.103.3"`.
  - **Anything else** (no network, 5xx, a 403 rate limit, an unreadable body) is silent and isn't counted as an
    answer, so the next launch tries again.
- **Nothing is downloaded.** "Update available · <version>" opens the release page in the browser.

## Search limits

From the site's search cheatsheet:

- **Tag count:** the cheatsheet says Members can search at most 2 tags, Gold 6, Platinum and above 12, with
  `rating:safe`, `status:deleted`, `-status:deleted` and `limit:200` not counting; signed-out users get 10
  (`NOT_LOGGED_USER_TAG_LIMIT`). **But** the free test account searched `blue_hair smile standing rating:s` (3 tags) and
  got results (discovery `d1-tag-limit`, 2026-10-29). The real limit is unknown.
- **Account tier:** `/users/me` → `user.subscription_level`: 0 free, 10 plus, 30 infinite (the tiers `/privileges` uses
  for its blacklist-limit rewards). `GET /privileges` lists reputation rewards, among them
  `plus_one_tag_to_search_limit` and `-1` to `-5` (+1 search tag each; excluded for `subscription_level` ≥ 10 or user
  level ≥ 33) and `increase_blacklist_limit-{free,plus,infinite}`; each has `is_claimed` and `is_active`.
- **Search time:** 3 s for Members, 6 s for Gold, 9 s for Platinum; a slower search fails with the 408
  `snackbar__account_search-query-timeout`. Narrowing with `age:<1year` or a `date:` range avoids it. This explains
  the `fav:<name>` timeouts.
- **Syntax:** `~a ~b` is OR, `-a` excludes, `*` wildcards; `parent:<id>`, `child:any`, `order:{score,favcount,
  quality,popularity,random,…}`, `id:a,b,c` (`order:custom` keeps that order). `date:A..B` works to the hour; `id:<`,
  `id_range:` and `age:` don't (see Following's order, keyset cursors and jumping deep).

## Rate limits

No published limits are known yet. Discovery step 9 records any rate-limit response headers; the client-side starting
values (`sankakuapi.com` 2/s burst 5, `s.` 4/s burst 8) are in `RateLimiter.DEFAULT_LIMITS`.

## Phase 0 (2026-10-29)

A JVM spike (Ktor on OkHttp, browser User-Agent) ran the full flow from a home IP with a test account without 2FA.
Every step returned 200/206 from nginx with no `cf-ray` header and no challenge: login, token exchange, `/users/me`
(with and without V2), the logged-in posts list, favourite, unfavourite, check-refresh-token and refresh. Decision: no
Cloudflare clearance layer. The spike is now `cli/`.

## Discovery results

Filled in by `:cli` runs; the raw observations are in `api-recorded.json`.

| Step | Question | Result |
| --- | --- | --- |
First live run: 2026-10-29.

| Step | Question | Result |
| --- | --- | --- |
| 1 | author-supplied endpoints; multi-id `id:` query | Endpoints from the HAR (above); Popular, Top, autocomplete, `/fu` (without V2), books list and book pages (with `file_url`, without V2) all work. Several ids work in one query, both as `id:a id:b` and as `id:a,b` (2 posts each). |
| 2 | `/posts/{id}` without V2 returns `file_url`? | Yes. |
| 3 | expired access token: 401 or anonymous 200? | A malformed token gets 401 `snackbar__common_unauthorized` on `/users/me` and keyset. The expired token is replayed after 2026-11-05. |
| 4 | `fav:<name>` returns favourites | **408 `snackbar__account_search-query-timeout`**, posts=0 on 2026-10-29; **200 with posts** on 2026-10-30, also with `rating:s` and on the page-based `/v2/posts`. So the timeout is intermittent (the 3 s search limit), not a refusal: the favourites grid is a keyset `fav:<name>` search that shows the timeout with a retry. `age:<1year` narrows by the post's age, not when it was favourited (0 posts), so it's no fix. On 2026-10-31 `fav:<name> blue_hair` gave only favourited posts and `-fav:<name>` (alone or with `blue_hair`) none, the search panel's favourites filter (#90). |
| 5 | favourite works without V2? `score` is the count? | Works without V2 (200, `success: true`), so the app sends no header set. `score` is not the count. |
| 6 | invalid and rotated refresh token responses | Both 401, `success: false`, `code: snackbar__common_unauthorized`. **The pre-rotation refresh token is rejected**, unlike Phase 0. Only a 401 means rejected, as `AuthApi.isRefreshRejection` already has it. |
| 7 | expired image URLs (`--slow`) | `v.` (preview, sample): 403. `s.` (full file, ranged request): no answer within the 30 s socket timeout (one run, 2026-10-29). If that repeats, a request can't detect expiry on `s.`, so the app checks the URL's `e` itself. |
| 8 | wrong password response | 403 from the login host, `success: false`, `error: "invalid login or password"`. `passwordLogin` maps it to `WrongCredentials`. |
| 9 | rate-limit headers | No rate-limit headers, but a family of `browsing-limit-{post,book,series}[-<platform>][-explicit]` headers (names recorded, values not). The client limiter keeps its starting values. |
| 10 | which single-post request has every tag object? | `GET /posts/{id}` with POSTS: `tags[]`=59 = `total_tags` = `tag_names`, each with `type` and `post_count`, plus `file_url`. Same with no header set; with V2 all tags but no `file_url`. Keyset `id:`: 5 of 59. `/v2/posts/{id}`: no post. (2026-10-29) |
| 11 | how to follow a tag, and which tags are followed? | Signed in, every tag of `GET /posts/{id}` has `id`, `is_following` and `notification_enabled`. The web app's tag chip sends `{following_id, following_name, type: "tag"}` (from its bundle; its paths weren't in the bundle we have). `POST /users/followings` with V2 and that body plus `content_type: "tag"` follows (200 `{success: true}`; `is_following` turns true), `DELETE` with the same body unfollows. No list of follows was found: `GET /users/followings` is 400 "The content type field is required." (also with `content_type=tag`), `/users/me/followings` 200 with no body, `/users/following` and `/tags/followings` 404. Which of the body's fields are required, and whether V2 is, wasn't narrowed down. (2026-10-30) |
| 16 | is Following ordered by upload, or by when the tag was added? do late posts show below newer ones? | By `created_at`, newest first, like the plain list, with rare exceptions (Following: 0 of 464 out of place; plain list: 1 of 457, by 59 min). Ids are opaque 11-character strings that can't be compared. Late arrivals: **not measurable on this account yet**: its Following got no new post in 5 hours (newest 3 days old), so the snapshot comparison found 0 of 0. It needs a followed tag that posts regularly; the run keeps comparing. (2026-11-01) |
| 17 | how long does a keyset cursor stay valid? what does an expired one return? | Opaque, 24 bytes, integrity-checked (a changed one is 400 `invalid-parameters`), reusable, and a position in time that any search accepts. Replays: still valid, identical page, after **5 hours** (Following 191 posts deep, plain list 120 deep) and 3 hours (artist tag). **Days: unknown**, replayable from 2026-11-04 (3 days) and 2026-11-08 (7 days) by any `:cli` run on the machine that saved them. What an expired one returns is **unknown** until one expires; the 400 for a made-up one is the nearest evidence. (2026-11-01) |
| 18 | does an id-bounded search work with an artist tag and Following? | No: ids aren't numbers; `id:<id` is a 500 and `id_range:` a 408. **`date:A..B` does** (UTC, resolution one hour, end exclusive), with Following, with an artist tag and with paging inside the range. `age:` returns nothing. (2026-11-01) |

Replays of saved cursors (step 17) and Following snapshots (step 16), by age:

| Age | Following cursor | Plain cursor | Artist cursor | New posts below Following's top |
| --- | --- | --- | --- | --- |
| 4 minutes | valid, same page | valid, same page | valid, same page | 0 of 0 |
| 1.5 hours | valid, same page | valid, same page | not saved (the artist had under 11 posts; it is now saved with `limit=2`) | 0 of 0 |
| 3 hours | valid, same page | valid, same page | valid, same page | 0 of 0 |
| 5 hours | valid, same page | valid, same page | not yet 5 hours old | 0 of 0 |
| days | **unknown**, replay from 2026-11-04 | **unknown** | **unknown** | **unknown** |
