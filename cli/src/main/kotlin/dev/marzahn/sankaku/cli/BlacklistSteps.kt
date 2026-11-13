package dev.marzahn.sankaku.cli

import dev.marzahn.sankaku.api.API_BASE
import dev.marzahn.sankaku.api.HeaderSet
import dev.marzahn.sankaku.api.RawResponse
import dev.marzahn.sankaku.api.probe
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Blacklisting a tag (#6): discovery step 22 through [probe], the gate the issue names. It reads the account's tag
 * rules, adds a `hide` rule on [BLACKLIST_TAG], reads them again, deletes the rule, deletes it a second time, and reads them a
 * last time, checking the account's rules are as they were. Every rule it adds it removes in the same step. Notes and
 * markers hold counts, keys and yes/no only: no rule id, user id or other tag.
 */
internal class BlacklistSteps(private val smoke: SmokeSteps) {
  suspend fun run() {
    val bearer = smoke.session.currentBearerTokens()?.accessToken
    val reason = "not signed in or SANKAKU_TRY_BLACKLIST=false"
    val token = bearer?.takeIf { smoke.env["SANKAKU_TRY_BLACKLIST"] != "false" }
    var before: Rules? = null
    smoke.discover("d22 GET /users/blacklist (tags)", "d22-blacklist-list", requires = token, reason, detail = { before?.detail.orEmpty() }) {
      val raw = list(it)
      val rules = Rules(raw)
      before = rules
      raw to "list[]=${yesNo(raw.obj()?.get("list") is JsonArray)}, records[]=${yesNo(raw.obj()?.get("records") is JsonArray)}, " +
        "inactive_items[]=${yesNo(raw.obj()?.get("inactive_items") is JsonArray)}, count keys=${rules.countKeys}"
    }
    val start = before?.takeIf { BLACKLIST_TAG !in it.tags }
    val skip = if (before != null) "the account already has a rule on $BLACKLIST_TAG" else "needs d22's list"
    var detail = ""
    smoke.discover("d22 tag rule: POST, then DELETE", "d22-blacklist-tag-delete", requires = start, skip, detail = { detail }) { was ->
      val added = smoke.authClient.probe(
        HttpMethod.Post, API_BASE, "/users/blacklist", HeaderSet.V2, bearer = token, query = LANG,
        body = buildJsonObject {
          put("type", "tag")
          putJsonArray("tags") { add(BLACKLIST_TAG) }
          put("visibility", "hide")
        },
      )
      val rule = added.obj()?.get("new_blacklist") as? JsonObject
      val ruleId = rule.str("id") ?: fail("tag rule not added (${added.status}, ${added.hint})")
      var removed: RawResponse? = null
      val during = undoing({
        removed = smoke.authClient.probe(HttpMethod.Delete, API_BASE, "/users/blacklist/$ruleId", HeaderSet.V2, bearer = token, query = LANG)
        if (removed!!.status !in 200..299) fail("DELETE answered ${removed!!.status}: remove the $BLACKLIST_TAG rule by hand")
      }) { Rules(list(token!!)) }
      val again = smoke.authClient.probe(HttpMethod.Delete, API_BASE, "/users/blacklist/$ruleId", HeaderSet.V2, bearer = token, query = LANG)
      val after = Rules(list(token!!))
      if (after.ids != was.ids) fail("the account's rules changed: compare them by hand")
      val delete = removed!!
      detail = "used_rules before=${was.used}, with the rule=${during.used}, after=${after.used}"
      delete to "POST=${added.status} keys=${added.keys()}, rule type=${rule.str("type")} visibility=${rule.str("visibility")} " +
        "tags=${(rule?.get("tags") as? JsonArray)?.size}; listed=${yesNo(ruleId in during.ids)}, " +
        "used_rules +1=${yesNo(during.used != null && was.used != null && during.used == was.used + 1)}; " +
        "DELETE=${delete.status} keys=${delete.keys()} success=${delete.json.str("success")}; gone=${yesNo(ruleId !in after.ids)}, " +
        "used_rules back=${yesNo(after.used == was.used)}; DELETE again=${again.status} code=${again.json.str("code")}"
    }
  }

  private suspend fun list(token: String) = smoke.authClient.probe(
    HttpMethod.Get, API_BASE, "/users/blacklist", HeaderSet.V2, bearer = token,
    query = LANG + mapOf("limit" to "40", "page" to "1", "entity" to "tags"),
  )

  /** The account's tag rules on the first page: their ids (never printed), tags and `count`. */
  private class Rules(raw: RawResponse) {
    private val root = raw.obj()
    private val rules = (root?.get("list") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    private val count = root?.get("count") as? JsonObject
    val ids = rules.mapNotNull { it.str("id") }.toSet()
    val tags = rules.flatMap { (it["tags"] as? JsonArray).orEmpty().mapNotNull { t -> (t as? JsonPrimitive)?.content } }.toSet()
    val used = count.str("used_rules")?.toIntOrNull()
    val countKeys = count?.keys?.sorted()?.joinToString(",")
    val detail get() = "rules on page 1=${rules.size}, used_rules=$used, count=" +
      count?.entries?.joinToString(",") { (k, v) -> "$k:$v" }
  }

  private companion object {
    val LANG = mapOf("lang" to "en")
  }
}

private fun RawResponse.obj() = json as? JsonObject

private fun RawResponse.keys() = obj()?.keys?.sorted()?.joinToString(",").orEmpty()

/** The tag steps 15 and d22 blacklist and remove again: common in `rating:s`, as in step d19. */
internal const val BLACKLIST_TAG = "blue_hair"
