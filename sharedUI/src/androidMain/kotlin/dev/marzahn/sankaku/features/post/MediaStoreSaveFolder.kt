package dev.marzahn.sankaku.features.post

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.RawSink
import kotlinx.io.asSink
import java.io.IOException

/**
 * `Download/Tessera` through MediaStore's Downloads collection: an app may add files there without any storage
 * permission (API 29+, and `minSdk` is 31). A file stays pending, so invisible to other apps and to [find], until it's
 * written whole. Only the files this app added are visible to it, so a reinstall saves a post again as `name (1)`.
 */
class MediaStoreSaveFolder(private val context: Context) : SaveFolder {
  private val collection: Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

  override suspend fun find(name: String): SavedFile? = withContext(Dispatchers.IO) {
    context.contentResolver.query(
      collection,
      arrayOf(MediaColumns._ID, MediaColumns.MIME_TYPE),
      "${MediaColumns.DISPLAY_NAME} = ? AND ${MediaColumns.RELATIVE_PATH} = ?",
      arrayOf(name, "$RELATIVE_PATH/"),
      null,
    )?.use { cursor ->
      if (cursor.moveToFirst()) file(ContentUris.withAppendedId(collection, cursor.getLong(0)), cursor.getString(1)) else null
    }
  }

  override suspend fun save(name: String, mimeType: String?, write: suspend (RawSink) -> Unit): SavedFile =
    withContext(Dispatchers.IO) {
      val resolver = context.contentResolver
      dropUnfinished(name)
      val values = ContentValues().apply {
        put(MediaColumns.DISPLAY_NAME, name)
        mimeType?.let { put(MediaColumns.MIME_TYPE, it) }
        put(MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
        put(MediaColumns.IS_PENDING, 1)
      }
      val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused the file")
      try {
        val stream = resolver.openOutputStream(uri) ?: throw IOException("MediaStore gave no stream")
        stream.asSink().use { write(it) }
        val published = resolver.update(uri, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null)
        if (published != 1) throw IOException("MediaStore didn't publish the file")
      } catch (e: Throwable) {
        resolver.delete(uri, null, null)
        throw e
      }
      file(uri, mimeType ?: resolver.getType(uri))
    }

  /**
   * Deletes a pending [name] left by a process that died mid-download (MediaStore keeps it for about a week). Without
   * this, the new file would be renamed `name (1)`, which [find] never matches, so every later Save would download
   * again. `PostSaver` never saves one post twice at once, so a pending file here is never a live one.
   */
  private fun dropUnfinished(name: String) {
    val args = Bundle().apply {
      putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaColumns.DISPLAY_NAME} = ? AND ${MediaColumns.RELATIVE_PATH} = ? AND ${MediaColumns.IS_PENDING} = 1")
      putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(name, "$RELATIVE_PATH/"))
      putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
    }
    context.contentResolver.delete(collection, args)
  }

  private fun file(uri: Uri, mimeType: String?) = SavedFile {
    val view = Intent(Intent.ACTION_VIEW)
      .setDataAndType(uri, mimeType)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      context.startActivity(view)
    } catch (_: ActivityNotFoundException) {
      // No app opens this type; the file is still saved.
    }
  }

  private companion object {
    val RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/Tessera"
  }
}
