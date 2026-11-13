package dev.marzahn.sankaku.api

import dev.marzahn.sankaku.Harness
import dev.marzahn.sankaku.auth.InMemoryTokenStore
import dev.marzahn.sankaku.json
import dev.marzahn.sankaku.storedTokens
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlacklistApiTest {
  private val harness = Harness(InMemoryTokenStore(storedTokens())) { request ->
    when (request.url.encodedPath to request.method) {
      "/users/blacklist" to HttpMethod.Get -> json("""{ "list": [], "records": [], "inactive_items": [], "count": { "row_count": 3, "used_rules": 3 } }""")
      "/users/blacklist" to HttpMethod.Post -> json(
        """{ "new_blacklist": { "id": 912, "type": "tag", "tags": ["blue_hair"], "visibility": "hide" }, "blacklisted_tags": ["blue_hair"], "inactive_items": [], "count": { "used_rules": 4 } }""",
      )
      "/users/blacklist/912" to HttpMethod.Delete -> json(
        """{ "blacklist_id": "912", "deleted_content_id": null, "blacklisted_tags": [], "inactive_items": [], "count": { "used_rules": 3 } }""",
      )
      else -> error("unexpected ${request.method.value} ${request.url.encodedPath}")
    }
  }
  private val blacklist = BlacklistApi(harness.mainClient)

  private fun HttpRequestData.carries(set: HeaderSet) = set.headers.all { (key, value) -> headers.getAll(key).orEmpty().contains(value) }

  @Test
  fun aTagRuleIsAddedCountedAndRemovedWithV2() = runTest {
    harness.session.restore()
    assertEquals(3, blacklist.usedRules())
    val rule = blacklist.hideTag("blue_hair")
    assertEquals(BlacklistedTag("912", 4), rule)
    assertEquals(3, blacklist.remove(rule.ruleId))

    val requests = harness.requestsTo("/users/blacklist") + harness.requestsTo("/users/blacklist/912")
    assertEquals(3, requests.size)
    for (request in requests) {
      assertTrue(request.carries(HeaderSet.V2), "${request.method.value} lacks V2")
      assertEquals("en", request.url.parameters["lang"])
    }
    val list = harness.requestsTo("/users/blacklist").first { it.method == HttpMethod.Get }.url.parameters
    assertEquals("tags", list["entity"])
    val body = Json.parseToJsonElement((harness.requestsTo("/users/blacklist").first { it.method == HttpMethod.Post }.body as TextContent).text).jsonObject
    assertEquals("tag", body["type"]?.jsonPrimitive?.content)
    assertEquals(listOf("blue_hair"), body["tags"]?.jsonArray?.map { it.jsonPrimitive.content })
    assertEquals("hide", body["visibility"]?.jsonPrimitive?.content)
  }
}
