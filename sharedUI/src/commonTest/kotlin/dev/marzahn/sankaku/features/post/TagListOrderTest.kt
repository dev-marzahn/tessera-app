package dev.marzahn.sankaku.features.post

import dev.marzahn.sankaku.api.dto.TagType
import kotlin.test.Test
import kotlin.test.assertEquals

class TagListOrderTest {
  @Test
  fun generalFollowsTheGreenTypesAndMediumSitsJustAboveAutomatic() {
    assertEquals(
      listOf(
        TagType.Artist, TagType.Studio, TagType.Copyright, TagType.Character, TagType.Genre, TagType.Meta,
        TagType.Fashion, TagType.Anatomy, TagType.Pose, TagType.Activity, TagType.Role, TagType.Flora, TagType.Fauna,
        TagType.Entity, TagType.Object, TagType.Substance, TagType.Setting, TagType.General, TagType.Language,
        TagType.Medium, TagType.Automatic, TagType.Other,
      ),
      TagListOrder,
    )
  }

  @Test
  fun everyTypeIsListedOnce() {
    assertEquals(TagType.entries.toSet(), TagListOrder.toSet())
    assertEquals(TagType.entries.size, TagListOrder.size)
  }
}
