package dev.marzahn.sankaku.features.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One library the app ships with, as Settings → About → Open-source licences lists it (#206): its [name], Maven
 * coordinates ([id], `group:artifact`), [version] and the [licences] it's under, each with its full text when known.
 */
internal data class OpenSourceLibrary(val name: String, val id: String, val version: String?, val licences: List<Licence>)

/** A licence by its [name] ("Apache License 2.0"), and its full [text] when the build found one. */
internal data class Licence(val name: String, val text: String?)

/** The Kotlin target this app is built for, as the licence export names it: `jvm` or `android`. */
internal expect val licencesTarget: String

/** Where the build writes the list (`exportLibraryDefinitions`, AboutLibraries), as a Compose resource path. */
internal const val LICENCES_RESOURCE = "files/aboutlibraries.json"

/**
 * The libraries in [json] (AboutLibraries' export, built with `includeTargets`) that the Kotlin [target] ships
 * (`jvm` on desktop, `android` on a phone; a library the build saw only in shared metadata or tooling isn't in either),
 * by name. A licence the export names without text is dropped when the library has another with text (java-keyring's
 * POM says "BSD-3", fixed to BSD-3-Clause in `sharedUI/licences`).
 */
internal fun openSourceLibraries(json: String, target: String): List<OpenSourceLibrary> {
  val export = LicencesJson.decodeFromString<Export>(json)
  return export.libraries
    .filter { target in it.targets }
    .map { library ->
      val licences = library.licenses.map { id ->
        val licence = export.licenses[id]
        Licence(name = licence?.name ?: id, text = licence?.content?.let(::spdxText))
      }
      OpenSourceLibrary(
        name = library.name.ifBlank { library.uniqueId },
        id = library.uniqueId,
        version = library.artifactVersion,
        licences = licences.filter { it.text != null }.ifEmpty { licences },
      )
    }
    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, OpenSourceLibrary::name).thenBy(OpenSourceLibrary::id))
}

/**
 * [template] as plain text: SPDX licence templates mark replaceable words as `<<var;name=…;original=WORDS;match=…>>`,
 * which reads as WORDS.
 */
internal fun spdxText(template: String): String =
  SpdxVar.replace(template) { match -> match.groupValues[1].trim() }

private val SpdxVar = Regex("""<<var;[^>]*?original=(.*?);match=.*?>>""")

private val LicencesJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class Export(val libraries: List<ExportLibrary> = emptyList(), val licenses: Map<String, ExportLicence> = emptyMap())

@Serializable
private data class ExportLibrary(
  val uniqueId: String,
  val name: String = "",
  val artifactVersion: String? = null,
  val licenses: List<String> = emptyList(),
  val targets: List<String> = emptyList(),
)

@Serializable
private data class ExportLicence(val name: String, val content: String? = null)
