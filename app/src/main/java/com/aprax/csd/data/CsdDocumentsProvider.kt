package com.aprax.csd.data

import android.content.ContentProviderClient
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.concurrent.thread

/** Exposes the app's Google Drive folder as a document-provider location in Android's file picker. */
class CsdDocumentsProvider : DocumentsProvider() {
    companion object {
        private const val PREFS = "csd_provider"
        private const val KEY_TOKEN = "access_token"
        private const val KEY_ROOT = "root_id"
        private const val ROOT_DOC = "csd-root"
        private const val ROOT_NAME = "CSD"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val BASE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_MIME_TYPES
        )
        private val DEFAULT_DOC_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )
    }

    override fun onCreate(): Boolean = true

    private fun prefs() = requireNotNull(context).getSharedPreferences(PREFS, 0)
    private fun token(): String = prefs().getString(KEY_TOKEN, null)
        ?: throw FileNotFoundException("Connect Google Drive in the Cloud SD Card app first, then reopen CSD.")
    private fun rootId(): String = prefs().getString(KEY_ROOT, null)
        ?: throw FileNotFoundException("Open Cloud SD Card and connect Google Drive first.")

    private fun request(url: String, method: String = "GET", body: ByteArray? = null, contentType: String? = null): Pair<HttpURLConnection, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 60000
            setRequestProperty("Authorization", "Bearer ${token()}")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType ?: "application/json; charset=UTF-8")
                outputStream.use { it.write(body) }
            }
        }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (c.responseCode !in 200..299) {
            c.disconnect()
            throw FileNotFoundException("Google Drive returned ${c.responseCode}. Reconnect Google Drive in the CSD app if authorization expired.")
        }
        return c to text
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: DEFAULT_ROOT_PROJECTION
        val cursor = MatrixCursor(cols)
        val row = cursor.newRow()
        for (col in cols) when (col) {
            DocumentsContract.Root.COLUMN_ROOT_ID -> row.add(ROOT_DOC)
            DocumentsContract.Root.COLUMN_DOCUMENT_ID -> row.add(ROOT_DOC)
            DocumentsContract.Root.COLUMN_TITLE -> row.add(ROOT_NAME)
            DocumentsContract.Root.COLUMN_FLAGS -> row.add(DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
            DocumentsContract.Root.COLUMN_ICON -> row.add(com.aprax.csd.R.mipmap.ic_launcher)
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES -> row.add(null)
            DocumentsContract.Root.COLUMN_SUMMARY -> row.add("Google Drive cloud storage")
            DocumentsContract.Root.COLUMN_MIME_TYPES -> row.add("*/*")
            else -> row.add(null)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        if (documentId == ROOT_DOC) return documentCursor(projection, ROOT_DOC, ROOT_NAME, DocumentsContract.Document.MIME_TYPE_DIR, null, 0L, true)
        val (_, json) = request("$BASE/files/${enc(documentId)}?fields=id,name,mimeType,size,modifiedTime")
        val f = JSONObject(json)
        val isFolder = f.optString("mimeType") == FOLDER_MIME
        return documentCursor(projection, f.getString("id"), f.optString("name"), if (isFolder) DocumentsContract.Document.MIME_TYPE_DIR else f.optString("mimeType", "application/octet-stream"), f.optString("size").toLongOrNull(), parseTime(f.optString("modifiedTime")), isFolder)
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val parent = if (parentDocumentId == ROOT_DOC) rootId() else parentDocumentId
        val q = "'${parent.replace("'", "\\'")}' in parents and trashed = false"
        val url = "$BASE/files?q=${enc(q)}&pageSize=1000&orderBy=folder,name&fields=files(id,name,mimeType,size,modifiedTime)"
        val (_, json) = request(url)
        val arr = JSONObject(json).optJSONArray("files") ?: JSONArray()
        val cols = projection ?: DEFAULT_DOC_PROJECTION
        val cursor = MatrixCursor(cols)
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            val folder = f.optString("mimeType") == FOLDER_MIME
            val row = cursor.newRow()
            for (col in cols) when (col) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(f.optString("id"))
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(f.optString("name"))
                DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(if (folder) DocumentsContract.Document.MIME_TYPE_DIR else f.optString("mimeType", "application/octet-stream"))
                DocumentsContract.Document.COLUMN_SIZE -> row.add(f.optString("size").toLongOrNull())
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(parseTime(f.optString("modifiedTime")))
                DocumentsContract.Document.COLUMN_FLAGS -> row.add(if (folder) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME or DocumentsContract.Document.FLAG_SUPPORTS_DELETE else DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME or DocumentsContract.Document.FLAG_SUPPORTS_DELETE)
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == ROOT_DOC) return true
        val (_, json) = request("$BASE/files/${enc(documentId)}?fields=parents")
        val parents = JSONObject(json).optJSONArray("parents") ?: JSONArray()
        return (0 until parents.length()).any { parents.optString(it) == parentDocumentId }
    }

    override fun getDocumentType(documentId: String): String {
        if (documentId == ROOT_DOC) return DocumentsContract.Document.MIME_TYPE_DIR
        val (_, json) = request("$BASE/files/${enc(documentId)}?fields=mimeType")
        val mime = JSONObject(json).optString("mimeType", "application/octet-stream")
        return if (mime == FOLDER_MIME) DocumentsContract.Document.MIME_TYPE_DIR else mime
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (documentId == ROOT_DOC) throw FileNotFoundException("CSD is a folder, not a file.")
        val write = mode.contains("w")
        val pipe = ParcelFileDescriptor.createPipe()
        if (write) {
            thread(name = "csd-cloud-upload") {
                ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input ->
                    try { uploadStream(documentId, input) } catch (_: Exception) { /* The receiving app gets a closed pipe; app UI can report API errors on refresh. */ }
                }
            }
            return pipe[1]
        }
        thread(name = "csd-cloud-download") {
            try {
                val c = (URL("$BASE/files/${enc(documentId)}?alt=media").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000; readTimeout = 60000; setRequestProperty("Authorization", "Bearer ${token()}")
                }
                if (c.responseCode !in 200..299) throw FileNotFoundException("Unable to download cloud document (${c.responseCode}). Reconnect CSD if needed.")
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out -> BufferedInputStream(c.inputStream).use { input -> BufferedOutputStream(out).use { output -> input.copyTo(output, 64 * 1024) } } }
                c.disconnect()
            } catch (_: Exception) { try { pipe[1].close() } catch (_: Exception) { } }
        }
        return pipe[0]
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = if (parentDocumentId == ROOT_DOC) rootId() else parentDocumentId
        val actualMime = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) FOLDER_MIME else mimeType
        val body = JSONObject().put("name", displayName).put("mimeType", actualMime).put("parents", JSONArray().put(parent)).toString().toByteArray()
        val (_, json) = request("$BASE/files?fields=id", "POST", body)
        return JSONObject(json).getString("id")
    }

    override fun deleteDocument(documentId: String) {
        if (documentId == ROOT_DOC) throw FileNotFoundException("Cannot delete CSD root.")
        request("$BASE/files/${enc(documentId)}", "DELETE")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        if (documentId == ROOT_DOC) throw FileNotFoundException("Cannot rename CSD root.")
        val body = JSONObject().put("name", displayName).toString().toByteArray()
        val (_, json) = request("$BASE/files/${enc(documentId)}?fields=id", "PATCH", body)
        return JSONObject(json).getString("id")
    }

    private fun uploadStream(id: String, input: java.io.InputStream) {
        val (_, metaJson) = request("$BASE/files/${enc(id)}?fields=name,mimeType,parents")
        val meta = JSONObject(metaJson).apply { remove("id") }
        val boundary = "csd-${UUID.randomUUID()}"
        val c = (URL("$UPLOAD/$id?uploadType=multipart&fields=id").openConnection() as HttpURLConnection).apply {
            requestMethod = "PATCH"; doOutput = true; connectTimeout = 20000; readTimeout = 60000
            setRequestProperty("Authorization", "Bearer ${token()}")
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        }
        c.outputStream.use { out ->
            out.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${meta}\r\n--$boundary\r\nContent-Type: ${meta.optString("mimeType", "application/octet-stream")}\r\n\r\n".toByteArray())
            input.copyTo(out, 64 * 1024)
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val response = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        response?.close()
        if (c.responseCode !in 200..299) throw FileNotFoundException("Cloud upload failed (${c.responseCode}).")
        c.disconnect()
    }

    private fun documentCursor(projection: Array<out String>?, id: String, name: String, mime: String, size: Long?, modified: Long, folder: Boolean): Cursor {
        val cols = projection ?: DEFAULT_DOC_PROJECTION
        val cursor = MatrixCursor(cols)
        val row = cursor.newRow()
        for (col in cols) when (col) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(id)
            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(name)
            DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(mime)
            DocumentsContract.Document.COLUMN_SIZE -> row.add(size)
            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(modified)
            DocumentsContract.Document.COLUMN_FLAGS -> row.add(if (folder) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME)
            else -> row.add(null)
        }
        return cursor
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun parseTime(value: String): Long = try { java.time.Instant.parse(value).toEpochMilli() } catch (_: Exception) { 0L }
}
