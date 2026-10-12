package com.cloudsdcard.app.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

 data class DriveItem(val id: String, val name: String, val mimeType: String, val size: Long?, val modifiedTime: String?, val parents: List<String>, val isFolder: Boolean)

class DriveRepository {
    companion object { const val FOLDER_MIME = "application/vnd.google-apps.folder"; private const val BASE = "https://www.googleapis.com/drive/v3" }
    private var accessToken: String? = null
    fun setToken(token: String?) { accessToken = token }
    fun hasToken() = !accessToken.isNullOrBlank()

    private fun connection(url: String, method: String = "GET"): HttpURLConnection {
        val token = accessToken ?: error("Connect Google Drive first.")
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 20000; readTimeout = 60000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
    }
    private fun readResponse(c: HttpURLConnection): String {
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (c.responseCode !in 200..299) error("Google Drive (${c.responseCode}): ${try { JSONObject(body).optJSONObject("error")?.optString("message") ?: body } catch (_: Exception) { body }}")
        return body
    }
    suspend fun list(parentId: String? = null, queryText: String? = null): List<DriveItem> = withContext(Dispatchers.IO) {
        val all = mutableListOf<DriveItem>(); var page: String? = null
        do {
            val q = mutableListOf("trashed = false")
            if (parentId != null) q += "'${parentId.replace("'", "\\'")}' in parents"
            if (!queryText.isNullOrBlank()) q += "name contains '${queryText.replace("'", "\\'")}'"
            val fields = "nextPageToken,files(id,name,mimeType,size,modifiedTime,parents,md5Checksum)"
            val url = "$BASE/files?q=${java.net.URLEncoder.encode(q.joinToString(" and "), "UTF-8")}&pageSize=100&orderBy=folder,name&fields=${java.net.URLEncoder.encode(fields, "UTF-8")}" + (page?.let { "&pageToken=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: "")
            val c = connection(url); val json = JSONObject(readResponse(c)); c.disconnect()
            val files = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) { val f = files.getJSONObject(i); val ps = f.optJSONArray("parents") ?: JSONArray(); all += DriveItem(f.getString("id"), f.optString("name"), f.optString("mimeType"), f.optString("size").toLongOrNull(), f.optString("modifiedTime").takeIf { it.isNotBlank() }, (0 until ps.length()).map { ps.getString(it) }, f.optString("mimeType") == FOLDER_MIME) }
            page = json.optString("nextPageToken").takeIf { it.isNotBlank() && it != "null" }
        } while (page != null)
        all
    }
    suspend fun createFolder(name: String, parentId: String? = null): DriveItem = withContext(Dispatchers.IO) {
        val body = JSONObject().put("name", name).put("mimeType", FOLDER_MIME); if (parentId != null) body.put("parents", JSONArray().put(parentId))
        val c = connection("$BASE/files?fields=id,name,mimeType,parents,modifiedTime", "POST"); c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=UTF-8"); c.outputStream.use { it.write(body.toString().toByteArray()) }
        val f = JSONObject(readResponse(c)); c.disconnect(); DriveItem(f.getString("id"), f.getString("name"), FOLDER_MIME, null, f.optString("modifiedTime"), parentId?.let { listOf(it) } ?: emptyList(), true)
    }
    suspend fun ensureRoot(): DriveItem = withContext(Dispatchers.IO) {
        val existing = list(null, "Cloud SD Card").firstOrNull { it.name == "Cloud SD Card" && it.isFolder }
        existing ?: createFolder("Cloud SD Card")
    }
    suspend fun rename(id: String, name: String) = withContext(Dispatchers.IO) { val c = connection("$BASE/files/$id?fields=id,name", "PATCH"); c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(JSONObject().put("name", name).toString().toByteArray()) }; readResponse(c); c.disconnect() }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) { val c = connection("$BASE/files/$id", "DELETE"); readResponse(c); c.disconnect() }
    suspend fun move(id: String, oldParent: String?, newParent: String) = withContext(Dispatchers.IO) {
        val query = "addParents=${java.net.URLEncoder.encode(newParent, "UTF-8")}" + (oldParent?.let { "&removeParents=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: "")
        val c = connection("$BASE/files/$id?$query&fields=id,parents", "PATCH"); c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write("{}".toByteArray()) }; readResponse(c); c.disconnect()
    }
    suspend fun upload(resolver: ContentResolver, uri: Uri, parentId: String?, displayName: String? = null, progress: (Long, Long) -> Unit = { _, _ -> }): DriveItem = withContext(Dispatchers.IO) {
        val name = displayName ?: resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "upload-${UUID.randomUUID()}"
        val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }.takeIf { it != null && it >= 0 }
        val boundary = "cloudsdcard-${UUID.randomUUID()}"; val metadata = JSONObject().put("name", name); if (parentId != null) metadata.put("parents", JSONArray().put(parentId))
        val c = connection("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,mimeType,size,modifiedTime,parents", "POST")
        c.doOutput = true; c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        c.outputStream.use { raw ->
            val out = BufferedOutputStream(raw)
            out.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${metadata}\r\n--$boundary\r\nContent-Type: ${resolver.getType(uri) ?: "application/octet-stream"}\r\n\r\n".toByteArray())
            resolver.openInputStream(uri).use { input -> requireNotNull(input) { "Unable to open selected file." }; val buf = ByteArray(64 * 1024); var done = 0L; while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; progress(done, size ?: -1L) } }
            out.write("\r\n--$boundary--\r\n".toByteArray()); out.flush()
        }
        val f = JSONObject(readResponse(c)); c.disconnect(); val ps = f.optJSONArray("parents") ?: JSONArray()
        DriveItem(f.getString("id"), f.getString("name"), f.optString("mimeType"), f.optString("size").toLongOrNull(), f.optString("modifiedTime"), (0 until ps.length()).map { ps.getString(it) }, false)
    }
    suspend fun download(id: String, destination: Uri, resolver: ContentResolver, progress: (Long) -> Unit = {}): Long = withContext(Dispatchers.IO) {
        val c = connection("$BASE/files/$id?alt=media"); if (c.responseCode !in 200..299) readResponse(c)
        val outStream = resolver.openOutputStream(destination, "w") ?: error("Cannot write to the selected destination.")
        var total = 0L; BufferedInputStream(c.inputStream).use { input -> BufferedOutputStream(outStream).use { out -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n); total += n; progress(total) } } }; c.disconnect(); total
    }
    suspend fun quota(): Triple<Long?, Long?, Long?> = withContext(Dispatchers.IO) { val c = connection("$BASE/about?fields=storageQuota(user,limit,usage,usageInDrive)"); val j = JSONObject(readResponse(c)).optJSONObject("storageQuota"); c.disconnect(); Triple(j?.optString("limit")?.toLongOrNull(), j?.optString("usage")?.toLongOrNull(), j?.optString("usageInDrive")?.toLongOrNull()) }
}
