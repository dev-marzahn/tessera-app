package dev.marzahn.sankaku.features.post

import dev.marzahn.sankaku.api.dto.PostDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class PostHeaderTest {
  private val post = PostDto(id = "1", md5 = "m", rating = "s", status = "active")

  @Test
  fun theAverageHasOneDecimalAndNeedsVotes() {
    assertNull(averageVote(post))
    assertEquals(4.6, averageVote(post.copy(voteCount = 12, totalScore = 55)))
    assertEquals(5.0, averageVote(post.copy(voteCount = 1, totalScore = 5)))
  }

  @Test
  fun anUnvotedPostSaysNoVotes() {
    assertEquals("No votes", averageLabel(averageVote(post)))
    assertEquals("4.6", averageLabel(averageVote(post.copy(voteCount = 12, totalScore = 55))))
  }

  @Test
  fun theVoteCountReadsNaturally() {
    assertNull(votesLabel(0))
    assertEquals("1 vote", votesLabel(1))
    assertEquals("12 votes", votesLabel(12))
    assertEquals("1.2k votes", votesLabel(1_234))
  }

  @Test
  fun theBylineHasWhateverThePostHas() {
    assertEquals("", postByline(post))
    assertEquals("someone", postByline(post.copy(author = PostDto.Author("someone"))))
    val dated = post.copy(author = PostDto.Author("someone"), createdAt = Instant.parse("2026-10-30T12:00:00Z"))
    assertEquals("someone · 2026-10-30", postByline(dated))
  }
}
