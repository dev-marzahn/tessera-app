package dev.marzahn.sankaku.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/** The licences list read from AboutLibraries' export (#206): each platform's own libraries, by name, with texts. */
class LicencesTest {
  private val export = """
    {
      "libraries": [
        {"uniqueId": "z:shared", "name": "zeta", "artifactVersion": "2.0", "licenses": ["MIT"], "targets": ["android", "jvm", "metadata"]},
        {"uniqueId": "a:phone", "name": "Alpha", "artifactVersion": "1.0", "licenses": ["Apache-2.0"], "targets": ["android"]},
        {"uniqueId": "k:keyring", "name": "java-keyring", "artifactVersion": "1.0.4", "licenses": ["BSD-3-Clause", "hash"], "targets": ["jvm"]},
        {"uniqueId": "m:meta", "name": "Metadata only", "licenses": ["MIT"], "targets": ["metadata"]},
        {"uniqueId": "t:tool", "name": "Tooling", "licenses": ["MIT"], "targets": []},
        {"uniqueId": "n:none", "name": "", "licenses": ["hash"], "targets": ["jvm"]}
      ],
      "licenses": {
        "MIT": {"name": "MIT License", "content": "MIT text"},
        "Apache-2.0": {"name": "Apache License 2.0", "content": "Apache text", "url": "https://spdx.org"},
        "BSD-3-Clause": {"name": "BSD 3-Clause", "content": "Neither <<var;name=x;original=the copyright holder;match=.+>> nor"},
        "hash": {"name": "BSD-3"}
      },
      "metadata": {"generated": "now"}
    }
  """.trimIndent()

  @Test
  fun eachPlatformListsOnlyTheLibrariesItShipsByName() {
    assertEquals(listOf("Alpha", "zeta"), openSourceLibraries(export, "android").map { it.name })
    // A blank name falls back to the coordinates; metadata-only and tooling libraries are in neither app.
    assertEquals(listOf("java-keyring", "n:none", "zeta"), openSourceLibraries(export, "jvm").map { it.name })
  }

  @Test
  fun aLibraryCarriesItsCoordinatesVersionAndLicenceTexts() {
    val alpha = openSourceLibraries(export, "android").first()
    assertEquals(OpenSourceLibrary("Alpha", "a:phone", "1.0", listOf(Licence("Apache License 2.0", "Apache text"))), alpha)
  }

  @Test
  fun aLicenceWithNoTextIsDroppedOnlyWhenAnotherHasText() {
    val jvm = openSourceLibraries(export, "jvm")
    assertEquals(
      listOf(Licence("BSD 3-Clause", "Neither the copyright holder nor")),
      jvm.single { it.id == "k:keyring" }.licences,
    )
    assertEquals(listOf(Licence("BSD-3", null)), jvm.single { it.id == "n:none" }.licences)
  }

  @Test
  fun spdxTemplateVariablesReadAsTheirOriginalWords() {
    assertEquals(
      "Copyright (c) <year> <owner>. All rights reserved.",
      spdxText("Copyright (c) <<var;name=copyright;original= <year> <owner>;match=.+>>. All rights reserved."),
    )
    assertEquals("No markup", spdxText("No markup"))
  }
}
