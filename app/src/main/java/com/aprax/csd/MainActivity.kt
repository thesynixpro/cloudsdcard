package com.aprax.csd

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.aprax.csd.data.CloudDatabase
import com.aprax.csd.data.CloudFileEntity
import com.aprax.csd.data.DriveItem
import com.aprax.csd.data.DriveRepository
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Navy = Color(0xFF0B1020)
private val Panel = Color(0xFF151D31)
private val Cyan = Color(0xFF55D6E8)
private val Muted = Color(0xFF9BA8C2)

class MainActivity : ComponentActivity() {
    private val drive = DriveRepository()
    private lateinit var db: CloudDatabase
    private var token by mutableStateOf<String?>(null)
    private var accountLabel by mutableStateOf("Not connected")
    private var notice by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var files by mutableStateOf<List<DriveItem>>(emptyList())
    private var rootId by mutableStateOf<String?>(null)
    private var currentFolder by mutableStateOf<String?>(null)
    private var currentFolderName by mutableStateOf("Cloud SD Card")
    private var breadcrumbs by mutableStateOf(listOf<Pair<String?, String>>(null to "Cloud SD Card"))
    private var quota by mutableStateOf<Triple<Long?, Long?, Long?>>(Triple(null, null, null))
    private var transferLog by mutableStateOf(listOf<String>())
    private var searchText by mutableStateOf("")
    private var queryText by mutableStateOf("")
    private var selectedTab by mutableStateOf("Home")

    private val uploadPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) uploadUri(uri) }
    private var pendingDownload by mutableStateOf<DriveItem?>(null)
    private val savePicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> val item = pendingDownload; if (uri != null && item != null) downloadItem(item, uri); pendingDownload = null }
    private val authResolution = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) handleAuthorizationIntent(result.data) else { busy = false; notice = "Google authorization was cancelled." }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = CloudDatabase.get(applicationContext)
        setContent { CloudApp() }
    }

    private fun connectDrive() {
        busy = true; notice = null
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.file"))).build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { result: AuthorizationResult ->
                if (result.hasResolution()) {
                    try { authResolution.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build()) }
                    catch (e: Exception) { busy = false; notice = "Could not open Google authorization: ${e.message}" }
                } else { token = result.accessToken; finishConnect(result.accessToken) }
            }
            .addOnFailureListener { busy = false; notice = "Google connection failed: ${it.localizedMessage ?: "Please try again."}" }
    }
    private fun handleAuthorizationIntent(data: Intent?) {
        try {
            val result = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)
            token = result.accessToken; finishConnect(result.accessToken)
        } catch (e: Exception) { busy = false; notice = "Authorization did not complete: ${e.localizedMessage}" }
    }
    private fun finishConnect(accessToken: String?) {
        if (accessToken.isNullOrBlank()) { busy = false; notice = "Google did not return an access token. Please reconnect."; return }
        token = accessToken; drive.setToken(accessToken); accountLabel = "Google Drive connected"
        lifecycleScope.launch {
            try {
                val root = withContext(Dispatchers.IO) { drive.ensureRoot() }
                rootId = root.id; currentFolder = root.id; currentFolderName = root.name; breadcrumbs = listOf(root.id to root.name)
                refreshFiles(); quota = drive.quota(); selectedTab = "Home"
                notice = "Connected. Access is limited to files authorized for Cloud SD Card (drive.file)."
            } catch (e: Exception) { notice = e.localizedMessage ?: "Connected, but Drive could not be loaded." }
            finally { busy = false }
        }
    }
    private fun refreshFiles() {
        val t = token ?: return
        drive.setToken(t)
        lifecycleScope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) { drive.list(currentFolder, queryText.takeIf { it.isNotBlank() }) }
                files = result
                db.cloudFileDao().upsertAll(result.map { CloudFileEntity(it.id, it.name, it.mimeType, it.parents.firstOrNull(), it.size, it.modifiedTime, it.isFolder) })
                notice = null
            } catch (e: Exception) { notice = e.localizedMessage ?: "Unable to load files." }
            finally { busy = false }
        }
    }
    private fun openFolder(item: DriveItem) { currentFolder = item.id; currentFolderName = item.name; breadcrumbs = breadcrumbs + (item.id to item.name); queryText = ""; refreshFiles() }
    private fun goBreadcrumb(index: Int) { breadcrumbs = breadcrumbs.take(index + 1); currentFolder = breadcrumbs.last().first; currentFolderName = breadcrumbs.last().second; refreshFiles() }
    private fun uploadUri(uri: Uri) {
        lifecycleScope.launch {
            busy = true; notice = "Uploading…"
            try {
                val item = drive.upload(contentResolver, uri, currentFolder) { done, total -> runOnUiThread { notice = "Uploading ${itemSafeName(uri)} · ${if (total > 0) (done * 100 / total).toInt().toString() + "%" else formatBytes(done)}" } }
                transferLog = listOf("Uploaded · ${item.name}") + transferLog; notice = "Upload complete: ${item.name}"; refreshFiles()
            } catch (e: Exception) { notice = "Upload failed: ${e.localizedMessage ?: "Unknown error"}" }
            finally { busy = false }
        }
    }
    private fun itemSafeName(uri: Uri): String = try { contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else "file" } ?: "file" } catch (_: Exception) { "file" }
    private fun downloadItem(item: DriveItem, uri: Uri) {
        lifecycleScope.launch {
            busy = true; notice = "Downloading ${item.name}…"
            try { val bytes = drive.download(item.id, uri, contentResolver); transferLog = listOf("Downloaded · ${item.name} · ${formatBytes(bytes)}") + transferLog; notice = "Download complete: ${item.name}" }
            catch (e: Exception) { notice = "Download failed: ${e.localizedMessage ?: "Unknown error"}" }
            finally { busy = false }
        }
    }
    private fun createFolder() {
        lifecycleScope.launch {
            busy = true
            try { drive.createFolder("New Folder", currentFolder); notice = "Folder created."; refreshFiles() }
            catch (e: Exception) { notice = e.localizedMessage ?: "Could not create folder." }
            finally { busy = false }
        }
    }
    private fun renameItem(item: DriveItem) {
        lifecycleScope.launch { try { drive.rename(item.id, "${item.name} (renamed)"); notice = "Renamed file."; refreshFiles() } catch (e: Exception) { notice = e.localizedMessage } }
    }
    private fun deleteItem(item: DriveItem) {
        lifecycleScope.launch { try { drive.delete(item.id); notice = "Deleted ${item.name}"; refreshFiles() } catch (e: Exception) { notice = e.localizedMessage } }
    }

    @Composable private fun CloudApp() {
        MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, secondary = Color(0xFF9D8CFF), background = Navy, surface = Panel, onSurface = Color(0xFFF3F6FF))) {
            Scaffold(containerColor = Navy, bottomBar = { NavigationBar(containerColor = Panel) {
                listOf("Home" to Icons.Default.Home, "Cloud Drive" to Icons.Default.Folder, "Game Storage" to Icons.Default.SportsEsports, "Transfers" to Icons.Default.SyncAlt, "Settings" to Icons.Default.Settings).forEach { (label, icon) ->
                    NavigationBarItem(selected = selectedTab == label, onClick = { selectedTab = label; if (label == "Cloud Drive" && token != null) refreshFiles() }, icon = { Icon(icon, null) }, label = { Text(label, fontSize = 10.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Cyan, selectedTextColor = Cyan, indicatorColor = Color(0xFF24364A), unselectedIconColor = Muted, unselectedTextColor = Muted))
                }
            } }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).background(Navy)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).background(Cyan.copy(alpha = .15f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Storage, null, tint = Cyan) }
                        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("CLOUD SD CARD", fontSize = 12.sp, color = Cyan, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp); Text(selectedTab, fontSize = 23.sp, fontWeight = FontWeight.Bold) }
                        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Cyan)
                    }
                    notice?.let { msg -> Surface(color = if (msg.contains("failed", true) || msg.contains("could not", true)) Color(0xFF482832) else Color(0xFF1B3040), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Info, null, tint = Cyan); Spacer(Modifier.width(8.dp)); Text(msg, fontSize = 12.sp, color = Color.White, modifier = Modifier.weight(1f)); IconButton(onClick = { notice = null }) { Icon(Icons.Default.Close, null, tint = Muted) } } } }
                    AnimatedContent(targetState = selectedTab, label = "tab") { tab -> when (tab) {
                        "Home" -> HomeScreen()
                        "Cloud Drive" -> DriveScreen()
                        "Game Storage" -> GameScreen()
                        "Transfers" -> TransfersScreen()
                        else -> SettingsScreen()
                    } }
                }
            }
        }
    }

    @Composable private fun HomeScreen() {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Surface(color = Panel, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Your cloud space", color = Muted, fontSize = 13.sp); Text(if (token == null) "Connect your Drive" else "Virtual SD Card", fontSize = 23.sp, fontWeight = FontWeight.Bold) }; Icon(Icons.Default.CloudQueue, null, tint = Cyan, modifier = Modifier.size(42.dp)) }
                Spacer(Modifier.height(18.dp)); val used = quota.second; val limit = quota.first
                LinearProgressIndicator(progress = { if (used != null && limit != null && limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(8.dp), color = Cyan, trackColor = Color(0xFF29344A))
                Spacer(Modifier.height(10.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Used ${used?.let { formatBytes(it) } ?: "—"}", color = Muted, fontSize = 12.sp); Text("Total ${limit?.let { formatBytes(it) } ?: "Unavailable"}", color = Muted, fontSize = 12.sp) }
                Spacer(Modifier.height(18.dp)); Button(onClick = { if (token == null) connectDrive() else { selectedTab = "Cloud Drive"; refreshFiles() } }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Icon(if (token == null) Icons.Default.Link else Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(if (token == null) "Connect Google Drive" else "Browse Virtual SD Card") }
            } } }
            item { Text("Quick actions", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { ActionCard("Upload file", Icons.Default.UploadFile, Modifier.weight(1f)) { if (token != null) uploadPicker.launch(arrayOf("*/*")) else connectDrive() }; ActionCard("New folder", Icons.Default.CreateNewFolder, Modifier.weight(1f)) { if (token != null) createFolder() else connectDrive() } } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { ActionCard("Game backup", Icons.Default.SportsEsports, Modifier.weight(1f)) { selectedTab = "Game Storage" }; ActionCard("Transfers", Icons.Default.SyncAlt, Modifier.weight(1f)) { selectedTab = "Transfers" } } }
            item { Text("Recent files", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            if (files.isEmpty()) item { EmptyState("Nothing here yet", "Connect Google Drive and browse your cloud files.") }
            items(files.take(5), key = { it.id }) { item -> FileRow(item, onClick = { if (item.isFolder) openFolder(item) else { pendingDownload = item; savePicker.launch(item.name) } }, onMenu = { selectedTab = "Cloud Drive" }) }
        }
    }

    @Composable private fun DriveScreen() {
        Column(Modifier.fillMaxSize()) {
            if (token == null) { EmptyState("Connect Google Drive", "Cloud files appear here after you authorize access."); Button(onClick = { connectDrive() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Text("Connect Google Drive") } }
            else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(value = searchText, onValueChange = { searchText = it }, modifier = Modifier.weight(1f), placeholder = { Text("Search accessible files") }, singleLine = true, shape = RoundedCornerShape(14.dp)); IconButton(onClick = { queryText = searchText; refreshFiles() }) { Icon(Icons.Default.Search, null, tint = Cyan) }; IconButton(onClick = { queryText = ""; searchText = ""; refreshFiles() }) { Icon(Icons.Default.Refresh, null, tint = Cyan) } }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text("Virtual SD Card", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); TextButton(onClick = { uploadPicker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Upload, null); Text("Upload") }; TextButton(onClick = { createFolder() }) { Icon(Icons.Default.CreateNewFolder, null); Text("Folder") } }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { breadcrumbs.forEachIndexed { index, pair -> if (index > 0) Text("  /  ", color = Muted); Text(pair.second, color = if (index == breadcrumbs.lastIndex) Cyan else Muted, fontSize = 12.sp, modifier = Modifier.clickable { goBreadcrumb(index) }) } }
                if (files.isEmpty() && !busy) EmptyState("This folder is empty", "Upload a file or create a folder to get started.")
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { items(files, key = { it.id }) { item -> FileRow(item, onClick = { if (item.isFolder) openFolder(item) else { pendingDownload = item; savePicker.launch(item.name) } }, onMenu = { /* row menu handled via actions */ }) } }
            }
        }
    }

    @Composable private fun GameScreen() {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Surface(color = Panel, shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(18.dp)) { Icon(Icons.Default.SportsEsports, null, tint = Cyan, modifier = Modifier.size(34.dp)); Spacer(Modifier.height(8.dp)); Text("Game Resource Manager", fontSize = 21.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text("Back up resource files and folders that you can explicitly access. Android generally blocks apps from reading another game's private data.", color = Muted, fontSize = 13.sp) } } }
            item { Button(onClick = { if (token != null) uploadPicker.launch(arrayOf("*/*")) else connectDrive() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Import a resource file") } }
            item { OutlinedButton(onClick = { selectedTab = "Cloud Drive"; queryText = ""; refreshFiles() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Browse Game Backups in Drive") } }
            item { Text("Important compatibility note", fontWeight = FontWeight.Bold) }
            item { Text("This starter currently uploads selected files to the current Drive folder. Versioned manifests, multi-file folder backup/restore, and checksum verification are not implemented yet. Restoring a file does not guarantee that a game will recognize it.", color = Muted, fontSize = 13.sp) }
        }
    }

    @Composable private fun TransfersScreen() {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Recent activity", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
            if (transferLog.isEmpty()) item { EmptyState("No transfers yet", "Uploads and downloads completed in this session will appear here.") }
            items(transferLog) { label -> Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = Cyan); Spacer(Modifier.width(10.dp)); Text(label, fontSize = 13.sp) } } }
            item { Text("Persistent queued/background transfer workers are not wired into this initial build; do not close the app during an active transfer.", color = Muted, fontSize = 12.sp) }
        }
    }

    @Composable private fun SettingsScreen() {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SettingCard("Google Drive", accountLabel, Icons.Default.Cloud) }
            item { Button(onClick = { connectDrive() }, modifier = Modifier.fillMaxWidth()) { Text(if (token == null) "Connect account" else "Reauthorize / refresh connection") } }
            item { OutlinedButton(onClick = { token = null; drive.setToken(null); accountLabel = "Not connected"; rootId = null; files = emptyList(); notice = "Disconnected locally. Your Drive files were not deleted." }, modifier = Modifier.fillMaxWidth()) { Text("Disconnect account") } }
            item { SettingCard("Authorization scope", "drive.file — access to files created by this app or explicitly opened/shared with it. This is not unrestricted access to every Drive file.", Icons.Default.Security) }
            item { SettingCard("Local cache", "Cache controls and pinned offline files are planned. Cloud-only files are not local files.", Icons.Default.Storage) }
            item { SettingCard("Privacy", "No app server is used. Files are sent directly to Google Drive over HTTPS. Access tokens are kept in memory in this starter and are not written to the Room database.", Icons.Default.PrivacyTip) }
            item { SettingCard("Known limitations", "Transfers currently run while the app is open. Folder upload, resumable uploads, persistent WorkManager transfers, cache eviction, account email display, and complete game backup manifests need further implementation.", Icons.Default.Info) }
        }
    }

    @Composable private fun ActionCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, action: () -> Unit) {
        Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = modifier.clickable(onClick = action)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { Icon(icon, null, tint = Cyan, modifier = Modifier.size(26.dp)); Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) } }
    }
    @Composable private fun EmptyState(title: String, body: String) { Column(Modifier.fillMaxWidth().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(58.dp).background(Panel, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.CloudOff, null, tint = Cyan, modifier = Modifier.size(28.dp)) }; Spacer(Modifier.height(12.dp)); Text(title, fontWeight = FontWeight.Bold); Spacer(Modifier.height(5.dp)); Text(body, color = Muted, fontSize = 12.sp) } }
    @Composable private fun FileRow(item: DriveItem, onClick: () -> Unit, onMenu: () -> Unit) {
        var expanded by remember(item.id) { mutableStateOf(false) }; var confirmDelete by remember(item.id) { mutableStateOf(false) }
        Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(if (item.isFolder) Color(0xFF24354D) else Color(0xFF23343A), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(if (item.isFolder) Icons.Default.Folder else fileIcon(item.mimeType), null, tint = Cyan) }
                Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text(if (item.isFolder) "Folder · Cloud" else "${item.size?.let { formatBytes(it) } ?: "Size unavailable"} · Cloud only", color = Muted, fontSize = 11.sp) }
                Box { IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, null, tint = Muted) }; DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    if (!item.isFolder) DropdownMenuItem(text = { Text("Download") }, leadingIcon = { Icon(Icons.Default.Download, null) }, onClick = { expanded = false; pendingDownload = item; savePicker.launch(item.name) })
                    DropdownMenuItem(text = { Text("Rename (append label)") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { expanded = false; renameItem(item) })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { expanded = false; confirmDelete = true })
                } }
            }
        }
        if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete ${item.name}?") }, text = { Text("This permanently deletes this item from Google Drive. The action cannot be undone here.") }, confirmButton = { TextButton(onClick = { confirmDelete = false; deleteItem(item) }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    }
    @Composable private fun SettingCard(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector) { Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) { Icon(icon, null, tint = Cyan); Spacer(Modifier.width(12.dp)); Column { Text(title, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(body, color = Muted, fontSize = 12.sp) } } } }
    private fun fileIcon(mime: String) = when { mime.startsWith("image/") -> Icons.Default.Image; mime.startsWith("video/") -> Icons.Default.Movie; mime.startsWith("audio/") -> Icons.Default.AudioFile; mime.contains("pdf") -> Icons.Default.PictureAsPdf; mime.contains("zip") || mime.contains("compressed") -> Icons.Default.FolderZip; mime.contains("apk") -> Icons.Default.Android; else -> Icons.Default.InsertDriveFile }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB"); var value = bytes.toDouble(); var index = -1
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    return "${DecimalFormat("#.##").format(value)} ${units[index]}"
}
