package com.example

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("VideoWallpaperPrefs", Context.MODE_PRIVATE) }
    
    var selectedUri by remember { mutableStateOf<Uri?>(prefs.getString("video_uri", null)?.let { Uri.parse(it) }) }
    var scale by remember { mutableStateOf(prefs.getFloat("video_scale", 1.0f)) }
    var offsetX by remember { mutableStateOf(prefs.getFloat("video_offset_x", 0.0f)) }
    var offsetY by remember { mutableStateOf(prefs.getFloat("video_offset_y", 0.0f)) }
    var rotationAngle by remember { mutableStateOf(prefs.getFloat("video_rotation", 0.0f)) }
    var isMuted by remember { mutableStateOf(prefs.getBoolean("video_muted", true)) }
    var resetOnLock by remember { mutableStateOf(prefs.getBoolean("video_reset_on_lock", false)) }
    var autoStart by remember { mutableStateOf(prefs.getBoolean("video_auto_start", true)) }

    var videoUrlInput by remember { mutableStateOf("") }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var downloadStatusText by remember { mutableStateOf("") }

    var isImportingLocalVideo by remember { mutableStateOf(false) }
    var localImportStatusText by remember { mutableStateOf("") }
    
    var appUpdateProgress by remember { mutableStateOf<Float?>(null) }
    var appUpdateStatusText by remember { mutableStateOf("") }
    var showWifiWarningDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        try {
            val packageName = context.packageName
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent().apply {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:$packageName")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { sourceUri ->
            isImportingLocalVideo = true
            localImportStatusText = "Đang sao chép file video vào hệ thống..."
            scope.launch {
                val success = withContext(Dispatchers.IO) {
                    try {
                        val destFile = File(context.filesDir, "selected_wallpaper.mp4")
                        context.contentResolver.openInputStream(sourceUri)?.use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        destFile.exists() && destFile.length() > 0
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }
                
                if (success) {
                    val destFile = File(context.filesDir, "selected_wallpaper.mp4")
                    val fileUri = Uri.fromFile(destFile)
                    prefs.edit().putString("video_uri", fileUri.toString()).apply()
                    selectedUri = fileUri
                    localImportStatusText = "Chọn file thành công!"
                } else {
                    // Fallback to direct URI usage if file copying fails
                    try {
                        context.contentResolver.takePersistableUriPermission(sourceUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        prefs.edit().putString("video_uri", sourceUri.toString()).apply()
                        selectedUri = sourceUri
                        localImportStatusText = "Chọn thành công (Sử dụng liên kết trực tiếp)!"
                    } catch (pe: Exception) {
                        pe.printStackTrace()
                        localImportStatusText = "Lỗi: Không thể mở hoặc phân tích tệp video."
                    }
                }
                isImportingLocalVideo = false
            }
        }
    }

    val scrollState = rememberScrollState()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // App Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Live Video Wallpaper",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Phone Mockup Preview Area
            Box(
                modifier = Modifier
                    .width(220.dp)
                    .height(400.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(6.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(32.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (selectedUri != null) {
                    VideoPreview(
                        videoUri = selectedUri!!,
                        scale = scale,
                        offsetX = offsetX,
                        offsetY = offsetY,
                        rotation = rotationAngle,
                        isMuted = isMuted,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(26.dp))
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Chưa chọn video",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                
                // Smartphone status notch overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                        .width(70.dp)
                        .height(18.dp)
                        .background(Color.Black, RoundedCornerShape(9.dp))
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Row: Select & Sound Mute Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { launcher.launch(arrayOf("video/*")) },
                    enabled = !isImportingLocalVideo,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    if (isImportingLocalVideo) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).padding(end = 6.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            strokeWidth = 2.dp
                        )
                        Text(text = "Đang xử lý...", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(text = if (selectedUri != null) "📁 Chọn File Video" else "📁 Chọn File Video")
                    }
                }

                FilledIconToggleButton(
                    checked = !isMuted,
                    onCheckedChange = {
                        isMuted = !it
                        prefs.edit().putBoolean("video_muted", isMuted).apply()
                    },
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconToggleButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        checkedContainerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = if (isMuted) "🔇" else "🔊",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            if (localImportStatusText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = localImportStatusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (localImportStatusText.startsWith("Lỗi")) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Downloader Card - Online Wallpaper without USB
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "📥 Đồng Bộ Video Qua Liên Kết Chia Sẻ (YouTube, TikTok, Drive...)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Hỗ trợ dán liên kết tải video trực tiếp (.mp4) hoặc dán link chia sẻ từ YouTube, TikTok, Facebook, Google Drive, Dropbox... Hệ thống sẽ tự động đồng bộ và tải xuống làm hình nền động.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    TextField(
                        value = videoUrlInput,
                        onValueChange = { videoUrlInput = it },
                        placeholder = { Text("Dán link chia sẻ YouTube, TikTok, Drive, Dropbox hoặc link .mp4 trực tiếp...") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2,
                        shape = RoundedCornerShape(8.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                if (videoUrlInput.isNotBlank()) {
                                    scope.launch {
                                        downloadProgress = 0f
                                        val inputUrl = videoUrlInput.trim()
                                        val preprocessedUrl = preprocessShareLink(inputUrl)
                                        
                                        var targetDownloadUrl = preprocessedUrl
                                        var isResolved = false
                                        
                                        if (isSocialShareLink(preprocessedUrl)) {
                                            downloadStatusText = "Đang nhận diện liên kết chia sẻ từ mạng xã hội..."
                                            val resolved = resolveVideoShareLink(preprocessedUrl)
                                            if (resolved != null) {
                                                targetDownloadUrl = resolved
                                                isResolved = true
                                                downloadStatusText = "Đã nhận được luồng video! Đang kết nối tải về..."
                                            } else {
                                                downloadProgress = null
                                                downloadStatusText = "Lỗi: Không tìm thấy luồng video. Hãy kiểm tra lại link mở hoặc thử link trực tiếp."
                                                return@launch
                                            }
                                        }

                                        val destFile = File(context.filesDir, "downloaded_wallpaper.mp4")
                                        var success = downloadVideo(targetDownloadUrl, destFile) { progress ->
                                            downloadProgress = progress
                                            downloadStatusText = if (progress >= 0f) {
                                                "Đang tải xuống: ${(progress * 100).toInt()}%"
                                            } else {
                                                "Đang tải dữ liệu nâng cao..."
                                            }
                                        }
                                        
                                        // If not resolved initially and direct download failed, try resolving via Cobalt as fallback
                                        if (!success && !isResolved) {
                                            downloadStatusText = "Thử giải mã chất lượng qua máy chủ đám mây..."
                                            val resolvedFallback = resolveVideoShareLink(preprocessedUrl)
                                            if (resolvedFallback != null) {
                                                downloadStatusText = "Giải mã thành công! Đang tải xuống..."
                                                success = downloadVideo(resolvedFallback, destFile) { progress ->
                                                    downloadProgress = progress
                                                    downloadStatusText = "Đang tải xuống: ${(progress * 100).toInt()}%"
                                                }
                                            }
                                        }
                                        
                                        if (success && destFile.exists()) {
                                            downloadProgress = null
                                            downloadStatusText = "Tải thành công! Đã áp dụng video làm hình nền."
                                            val fileUri = Uri.fromFile(destFile)
                                            prefs.edit().putString("video_uri", fileUri.toString()).apply()
                                            selectedUri = fileUri
                                        } else {
                                            downloadProgress = null
                                            downloadStatusText = "Lỗi: Không thể tải hoặc lưu video. Hãy thử liên kết chia sẻ khác hoặc định dạng mp4."
                                        }
                                    }
                                } else {
                                    downloadStatusText = "Vui lòng nhập đường dẫn liên kết video trước!"
                                }
                            },
                            enabled = downloadProgress == null && videoUrlInput.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = if (downloadProgress != null) "Đang tải..." else "Tải & Áp Dụng")
                        }

                        if (videoUrlInput.isNotEmpty()) {
                            IconButton(onClick = { videoUrlInput = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Xóa đường dẫn")
                            }
                        }
                    }

                    // Download Progress feedback
                    if (downloadProgress != null) {
                        LinearProgressIndicator(
                            progress = { if (downloadProgress == -1f) 0.5f else (downloadProgress ?: 0f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    if (downloadStatusText.isNotEmpty()) {
                        Text(
                            text = downloadStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (downloadStatusText.startsWith("Lỗi")) MaterialTheme.colorScheme.error 
                                    else if (downloadStatusText.startsWith("Tải thành công")) Color(0xFF2E7D32)
                                    else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    
                    Text(
                        text = "💡 Hoặc nhấp chọn nhanh mẫu vòng lặp cực đẹp dưới đây:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val presets = listOf(
                            "Cyber Wave" to "https://assets.mixkit.co/videos/preview/mixkit-futuristic-retro-grid-with-laser-lines-and-bright-dots-42998-large.mp4",
                            "Thác Nước" to "https://assets.mixkit.co/videos/preview/mixkit-relaxing-waterfall-in-a-forest-44160-large.mp4",
                            "Vũ Trụ" to "https://assets.mixkit.co/videos/preview/mixkit-mysterious-cosmic-shining-substance-in-space-43023-large.mp4"
                        )
                        presets.forEach { (name, url) ->
                            SuggestionChip(
                                onClick = {
                                    videoUrlInput = url
                                    downloadStatusText = "Đã chọn \"$name\". Nhấp \"Tải & Áp Dụng\" để lưu video!"
                                },
                                label = { Text(name, style = MaterialTheme.typography.bodySmall) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // OTA App Update Card
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "📲 Cập Nhật Ứng Dụng Không Dây (OTA)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "Nạp nhanh trực tiếp file ứng dụng (.apk) mới nhất từ liên kết mà không cần cắm cáp USB phiền hà.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Wifi Status Indicator Block
                    val isWifi = remember { mutableStateOf(false) }
                    // Update Wi-Fi status dynamically when resumed or periodically
                    val currentConnectivity = remember(context) {
                        try {
                            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                            val network = cm.activeNetwork
                            val caps = cm.getNetworkCapabilities(network)
                            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
                        } catch (e: Exception) {
                            false
                        }
                    }
                    LaunchedEffect(Unit) {
                        isWifi.value = currentConnectivity
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isWifi.value) Color(0xFFE8F5E9) else Color(0xFFFFF3E0))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isWifi.value) Icons.Default.Info else Icons.Default.Warning,
                            contentDescription = "Wifi Status",
                            tint = if (isWifi.value) Color(0xFF2E7D32) else Color(0xFFE65100)
                        )
                        Column {
                            Text(
                                text = if (isWifi.value) "Wifi đang bật (Khuyên dùng)" else "Không dùng Wifi (Có thể tốn lưu lượng di động)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isWifi.value) Color(0xFF2E7D32) else Color(0xFFE65100)
                            )
                            Text(
                                text = if (isWifi.value) "Sẵn sàng tải bản cập nhật mượt mà, tốc độ cao." else "Kết nối Wifi giúp tải bản cập nhật nhanh chóng và tiết kiệm dung lượng di động.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isWifi.value) Color(0xFF1B5E20) else Color(0xFF5D4037)
                            )
                        }
                    }

                    val startUpdateDownload = {
                        scope.launch {
                            appUpdateProgress = 0f
                            appUpdateStatusText = "Đang kiểm tra danh sách bản cập nhật từ máy chủ..."
                            
                            val systemInfo = fetchGitHubApkInfo()
                            if (systemInfo == null || systemInfo.latestApk == null) {
                                appUpdateProgress = null
                                appUpdateStatusText = "Lỗi: Không tìm thấy file cài (.apk) trên GitHub API."
                                return@launch
                            }
                            
                            val (apkName, downloadUrl) = systemInfo.latestApk
                            val allApks = systemInfo.allApkNames
                            
                            // Bước 4: Kiểm tra và dọn dẹp các file cũ không có trên Server
                            val updatesDir = File(context.cacheDir, "updates")
                            if (!updatesDir.exists()) {
                                updatesDir.mkdirs()
                            }
                            updatesDir.listFiles()?.forEach { localFile ->
                                if (localFile.isFile && localFile.name.endsWith(".apk") && !allApks.contains(localFile.name)) {
                                    localFile.delete()
                                }
                            }
                            
                            val destFile = File(updatesDir, apkName)
                            appUpdateStatusText = "Đang tải xuống: $apkName..."
                            
                            val success = downloadVideo(downloadUrl, destFile) { progress ->
                                appUpdateProgress = progress
                                appUpdateStatusText = "Đang tải xuống $apkName: ${(progress * 100).toInt()}%"
                            }
                            
                            if (success && destFile.exists()) {
                                appUpdateProgress = null
                                appUpdateStatusText = "Tải thành công! Đang kích hoạt cài đặt ứng dụng: $apkName..."
                                installApk(context, destFile)
                            } else {
                                appUpdateProgress = null
                                appUpdateStatusText = "Lỗi: Không thể tải xuống tệp APK từ máy chủ."
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (!isWifi.value) {
                                showWifiWarningDialog = true
                            } else {
                                startUpdateDownload()
                            }
                        },
                        enabled = appUpdateProgress == null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = if (appUpdateProgress != null) "Đang tải bản cập nhật..." else "Cài Đặt & Cập Nhật Lập Tức 🚀")
                    }

                    if (appUpdateProgress != null) {
                        LinearProgressIndicator(
                            progress = { appUpdateProgress ?: 0f },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    if (appUpdateStatusText.isNotEmpty()) {
                        Text(
                            text = appUpdateStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (appUpdateStatusText.startsWith("Lỗi")) MaterialTheme.colorScheme.error 
                                    else if (appUpdateStatusText.contains("thành công")) Color(0xFF2E7D32)
                                    else MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Wifi Warning Dialog
                    if (showWifiWarningDialog) {
                        AlertDialog(
                            onDismissRequest = { showWifiWarningDialog = false },
                            title = { Text("⚠️ Cảnh Báo Sử Dụng Mạng Di Động") },
                            text = { Text("Bạn hiện KHÔNG kết nối Wifi. Việc tải ứng dụng (.apk) qua mạng di động có thể gây tốn dung lượng của bạn. Bạn vẫn muốn tiếp tục chứ?") },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showWifiWarningDialog = false
                                        startUpdateDownload()
                                    }
                                ) {
                                    Text("Vẫn Cập Nhật", color = MaterialTheme.colorScheme.error)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showWifiWarningDialog = false }) {
                                    Text("Bỏ qua")
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Customization Console Card
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Title section with Reset button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Tùy Chỉnh Khung Video",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        TextButton(
                            onClick = {
                                scale = 1.0f
                                offsetX = 0.0f
                                offsetY = 0.0f
                                rotationAngle = 0.0f
                                prefs.edit()
                                    .putFloat("video_scale", 1.0f)
                                    .putFloat("video_offset_x", 0.0f)
                                    .putFloat("video_offset_y", 0.0f)
                                    .putFloat("video_rotation", 0.0f)
                                    .apply()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(text = "🔄 Đặt Lại", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Zoom / Scale Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Thu Phóng (Zoom)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${String.format("%.1f", scale)}x",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = scale,
                            onValueChange = {
                                scale = it
                                prefs.edit().putFloat("video_scale", it).apply()
                            },
                            valueRange = 1.0f..4.0f,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    // X Offset Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Di Chuyển Ngang (X)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (offsetX > 0) "+${String.format("%.2f", offsetX)}" else String.format("%.2f", offsetX),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = offsetX,
                            onValueChange = {
                                offsetX = it
                                prefs.edit().putFloat("video_offset_x", it).apply()
                            },
                            valueRange = -1.0f..1.0f,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    // Y Offset Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Di Chuyển Dọc (Y)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (offsetY > 0) "+${String.format("%.2f", offsetY)}" else String.format("%.2f", offsetY),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = offsetY,
                            onValueChange = {
                                offsetY = it
                                prefs.edit().putFloat("video_offset_y", it).apply()
                            },
                            valueRange = -1.0f..1.0f,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    // Y Tilt/Rotation Slider (Góc Nghiêng)
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Góc Nghiêng (Xoay)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${rotationAngle.toInt()}°",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = rotationAngle,
                            onValueChange = {
                                rotationAngle = it
                                prefs.edit().putFloat("video_rotation", it).apply()
                            },
                            valueRange = -180.0f..180.0f,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Other Settings Card
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Cài Đặt Khác",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                text = "Phát lại từ đầu lúc mở màn hình",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Khi tắt máy, video thiết lập lại từ đầu thay vì tạm dừng tại chỗ.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = resetOnLock,
                            onCheckedChange = {
                                resetOnLock = it
                                prefs.edit().putBoolean("video_reset_on_lock", it).apply()
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                text = "Tự khởi chạy cùng điện thoại 🚀",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Tự động kích hoạt lại ứng dụng khi khởi động hoặc bật lại nguồn điện thoại. (Lưu ý: Một số dòng máy cần được cấp quyền 'Tự khởi chạy / Auto-start' trong cài đặt thông tin ứng dụng của hệ thống).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoStart,
                            onCheckedChange = {
                                autoStart = it
                                prefs.edit().putBoolean("video_auto_start", it).apply()
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Thả xích tối ưu hóa pin",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isIgnoringBatteryOptimizations) Color(0xFFE8F5E9) else Color(0xFFFFEBEE))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isIgnoringBatteryOptimizations) "Đã thả xích" else "Chưa tối ưu",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isIgnoringBatteryOptimizations) Color(0xFF2E7D32) else Color(0xFFC62828)
                                    )
                                }
                            }
                            Text(
                                text = "Giúp chạy mượt hơn, tránh bị hệ điều hành tắt tiến trình vẽ hình nền để tiết kiệm pin.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = {
                                try {
                                    val packageName = context.packageName
                                    val intent = Intent().apply {
                                        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                        data = Uri.parse("package:$packageName")
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            },
                            enabled = !isIgnoringBatteryOptimizations,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(text = if (isIgnoringBatteryOptimizations) "OK" else "Mở")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Primary Apply Button
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = {
                        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                            putExtra(
                                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                ComponentName(context, VideoWallpaperService::class.java)
                            )
                        }
                        context.startActivity(intent)
                    },
                    enabled = selectedUri != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "🖼️ Đặt Hình Nền (Chính & Khóa)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                if (selectedUri != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Text(
                            text = "💡 Mẹo đặt riêng video cho Màn Hình Khóa:\nCơ chế của hệ điều hành Android mặc định không có nút 'Chỉ màn hình khóa' cho hình nền động (video). Để thiết lập riêng cho màn hình khóa, bạn hãy chọn Màn hình chính và khóa, sau đó ra ngoài thư viện ảnh đổi lại một bức ảnh tĩnh cho màn hình chính.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun VideoPreview(
    videoUri: Uri,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    rotation: Float,
    isMuted: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mediaPlayer = remember { mutableStateOf<MediaPlayer?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentSurface = remember { mutableStateOf<android.view.Surface?>(null) }
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(videoUri, lifecycleOwner) {
        var isPrepared = false
        var mp: MediaPlayer? = null
        
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val newMp = MediaPlayer().apply {
                        setOnErrorListener { _, what, extra ->
                            android.util.Log.e("VideoPreview", "MediaPlayer error: what=$what, extra=$extra")
                            true // error handled gracefully, do not crash app
                        }
                        setDataSource(context, videoUri)
                        isLooping = true
                        val vol = if (isMuted) 0f else 1f
                        setVolume(vol, vol)
                        setOnPreparedListener {
                            isPrepared = true
                            coroutineScope.launch(Dispatchers.Main) {
                                withContext(Dispatchers.IO) {
                                    try {
                                        it.start()
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            }
                        }
                        
                        // If a surface is already created and valid, associate it immediately
                        val surface = currentSurface.value
                        if (surface != null && surface.isValid) {
                            setSurface(surface)
                        }
                        
                        prepareAsync()
                    }
                    mp = newMp
                    withContext(Dispatchers.Main) {
                        mediaPlayer.value = newMp
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            val activeMp = mp ?: return@LifecycleEventObserver
            coroutineScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        if (event == Lifecycle.Event.ON_PAUSE) {
                            if (isPrepared) {
                                activeMp.pause()
                            }
                        } else if (event == Lifecycle.Event.ON_RESUME) {
                            if (isPrepared) {
                                activeMp.start()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            try {
                lifecycleOwner.lifecycle.removeObserver(observer)
            } catch (e: Exception) {
                // Ignore
            }
            val activeMp = mp
            if (activeMp != null) {
                coroutineScope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            activeMp.stop()
                        } catch (e: Exception) {
                            // Ignore
                        }
                        try {
                            activeMp.release()
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }
            }
            if (mediaPlayer.value == activeMp) {
                mediaPlayer.value = null
            }
        }
    }

    // Reactively update surface association only when currentSurface changes
    LaunchedEffect(currentSurface.value) {
        val surface = currentSurface.value
        val activeMp = mediaPlayer.value
        if (activeMp != null) {
            withContext(Dispatchers.IO) {
                try {
                    if (surface != null && surface.isValid) {
                        activeMp.setSurface(surface)
                    } else {
                        activeMp.setSurface(null)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // Dynamic mute update
    LaunchedEffect(isMuted) {
        val activeMp = mediaPlayer.value
        if (activeMp != null) {
            withContext(Dispatchers.IO) {
                try {
                    val vol = if (isMuted) 0f else 1f
                    activeMp.setVolume(vol, vol)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    Box(
        modifier = modifier
            .clipToBounds()
    ) {
        AndroidView(
            factory = { ctx ->
                android.view.SurfaceView(ctx).apply {
                    holder.addCallback(object : android.view.SurfaceHolder.Callback {
                        override fun surfaceCreated(hold: android.view.SurfaceHolder) {
                            currentSurface.value = hold.surface
                        }

                        override fun surfaceChanged(hold: android.view.SurfaceHolder, format: Int, w: Int, h: Int) {}
                        override fun surfaceDestroyed(hold: android.view.SurfaceHolder) {
                            currentSurface.value = null
                        }
                    })
                }
            },
            update = { _ ->
                // Leave empty to completely avoid calling setSurface during frame-by-frame recomposition (which was blocking the UI thread and causing ANRs)
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX * size.width
                    translationY = offsetY * size.height
                    rotationZ = rotation
                }
        )
    }
}

suspend fun downloadVideo(
    urlStr: String,
    destinationFile: File,
    onProgress: (Float) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        var currentUrl = urlStr
        var redirectCount = 0
        val maxRedirects = 5
        var responseCode = -1

        while (redirectCount < maxRedirects) {
            val url = URL(currentUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = true
            
            responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || 
                responseCode == HttpURLConnection.HTTP_MOVED_PERM || 
                responseCode == 307 || responseCode == 308) {
                val newUrl = connection.getHeaderField("Location")
                if (newUrl != null) {
                    currentUrl = newUrl
                    connection.disconnect()
                    redirectCount++
                    continue
                }
            }
            break
        }

        if (responseCode != HttpURLConnection.HTTP_OK || connection == null) {
            return@withContext false
        }

        val conn = connection!!
        val fileLength = conn.contentLength
        val input = conn.inputStream
        val output = FileOutputStream(destinationFile)

        val data = ByteArray(4096)
        var total: Long = 0
        var count: Int
        var lastPublishedProgress = -1
        while (input.read(data).also { count = it } != -1) {
            total += count
            if (fileLength > 0) {
                val progress = total.toFloat() / fileLength.toFloat()
                val progressPercent = (progress * 100).toInt()
                if (progressPercent != lastPublishedProgress) {
                    lastPublishedProgress = progressPercent
                    withContext(Dispatchers.Main) {
                        onProgress(progress)
                    }
                }
            } else {
                // If content length is unknown, throttle updates every 100KB to avoid UI spam
                if (total % (100 * 1024) < 4096) {
                    withContext(Dispatchers.Main) {
                        onProgress(-1f)
                    }
                }
            }
            output.write(data, 0, count)
        }

        output.flush()
        output.close()
        input.close()
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    } finally {
        connection?.disconnect()
    }
}

suspend fun resolveVideoShareLink(urlStr: String): String? = withContext(Dispatchers.IO) {
    // Danh sách Endpoint v11 cập nhật mới nhất
    val endpoints = listOf(
        "https://api.cobalt.tools",
        "https://cobalt.api.rybbt.com",
        "https://api.smooth.yt"
    )
    
    for (endpoint in endpoints) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(endpoint)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            
            // QUAN TRỌNG: Thêm User-Agent xịn để né Cloudflare chặn
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            
            // Cấu trúc Payload chuẩn của Cobalt API v11
            val jsonBody = org.json.JSONObject().apply {
                put("url", urlStr)
                put("vQuality", "720") // Cobalt v11 dùng vQuality thay vì videoQuality
            }
            
            connection.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = org.json.JSONObject(responseText)
                
                // Cobalt v11 trả về trực tiếp kiểu trạng thái: "stream", "redirect", "picker", "error"
                val status = jsonResponse.optString("status", "")
                
                if (status == "error") {
                    val errorText = jsonResponse.optString("text", "Lỗi dịch vụ Cobalt.")
                    android.util.Log.e("CobaltAPI", "Endpoint $endpoint error: $errorText")
                } else {
                    // Lấy link trực tiếp (status = stream hoặc redirect)
                    val resolvedUrl = jsonResponse.optString("url", "")
                    if (resolvedUrl.isNotEmpty()) {
                        return@withContext resolvedUrl
                    }
                    
                    // Xử lý nếu trả về mảng picker (status = picker)
                    val pickerArray = jsonResponse.optJSONArray("picker")
                    if (pickerArray != null && pickerArray.length() > 0) {
                        for (i in 0 until pickerArray.length()) {
                            val item = pickerArray.optJSONObject(i)
                            if (item != null) {
                                val itemUrl = item.optString("url", "")
                                if (itemUrl.isNotEmpty()) {
                                    return@withContext itemUrl
                                }
                            }
                        }
                    }
                }
            } else {
                android.util.Log.e("CobaltAPI", "Endpoint $endpoint returned HTTP $responseCode")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("CobaltAPI", "Endpoint $endpoint failed: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }
    null
}
fun isSocialShareLink(url: String): Boolean {
    val lower = url.lowercase()
    return lower.contains("youtube.com") || 
           lower.contains("youtu.be") || 
           lower.contains("tiktok.com") || 
           lower.contains("instagram.com") || 
           lower.contains("facebook.com") || 
           lower.contains("fb.watch") || 
           lower.contains("twitter.com") || 
           lower.contains("x.com") || 
           lower.contains("bilibili.com") || 
           lower.contains("vimeo.com") ||
           lower.contains("reddit.com") ||
           lower.contains("pinterest.com")
}

fun preprocessShareLink(urlStr: String): String {
    val trimmed = urlStr.trim()
    if (trimmed.contains("drive.google.com")) {
        val fileId = extractGoogleDriveFileId(trimmed)
        if (fileId != null) {
            return "https://docs.google.com/uc?export=download&id=$fileId"
        }
    }
    if (trimmed.contains("dropbox.com")) {
        if (trimmed.endsWith("dl=0")) {
            return trimmed.replace("dl=0", "dl=1")
        } else if (!trimmed.contains("dl=1")) {
            return if (trimmed.contains("?")) "$trimmed&dl=1" else "$trimmed?dl=1"
        }
    }
    return trimmed
}

fun extractGoogleDriveFileId(url: String): String? {
    try {
        if (url.contains("/file/d/")) {
            val parts = url.split("/file/d/")
            if (parts.size > 1) {
                val subPart = parts[1]
                val idEndIndex = subPart.indexOfAny(charArrayOf('/', '?', '&'))
                return if (idEndIndex != -1) {
                    subPart.substring(0, idEndIndex)
                } else {
                    subPart
                }
            }
        } else if (url.contains("id=")) {
            val parts = url.split("id=")
            if (parts.size > 1) {
                val subPart = parts[1]
                val idEndIndex = subPart.indexOfAny(charArrayOf('&', '?'))
                return if (idEndIndex != -1) {
                    subPart.substring(0, idEndIndex)
                } else {
                    subPart
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}

fun installApk(context: Context, apkFile: File) {
    try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return
            }
        }
        
        val apkUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "com.example.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

data class GitHubApkInfo(
    val latestApk: Pair<String, String>?,
    val allApkNames: Set<String>
)

suspend fun fetchGitHubApkInfo(): GitHubApkInfo? = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        val url = URL("https://api.github.com/repos/catkiuhi/Live-Video-Wallpaper/contents/.build-outputs")
        connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", "Android-App")
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        
        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val jsonArray = org.json.JSONArray(responseText)
            val allApkNames = mutableSetOf<String>()
            var latestPair: Pair<String, String>? = null
            
            for (i in 0 until jsonArray.length()) {
                val fileObj = jsonArray.getJSONObject(i)
                val name = fileObj.getString("name")
                val type = fileObj.getString("type")
                if (type == "file" && name.endsWith(".apk", ignoreCase = true)) {
                    allApkNames.add(name)
                    val downloadUrl = fileObj.optString("download_url", "")
                    if (downloadUrl.isNotEmpty() && latestPair == null) {
                        latestPair = Pair(name, downloadUrl)
                    }
                }
            }
            return@withContext GitHubApkInfo(latestPair, allApkNames)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        connection?.disconnect()
    }
    null
}

