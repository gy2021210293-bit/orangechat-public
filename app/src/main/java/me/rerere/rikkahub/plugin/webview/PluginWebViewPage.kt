/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.gadgetbridge.GadgetbridgeReader
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.loader.LoadedPlugin
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.model.PluginHookConfig
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.repository.PluginRepository
import me.rerere.rikkahub.plugin.provider.MomentsImageAttachments
import me.rerere.rikkahub.ui.pages.share.handler.SharedPostImageDownloader
import org.json.JSONArray
import org.json.JSONObject
import org.koin.compose.koinInject
import java.io.File
import okhttp3.OkHttpClient

private const val TAG = "PluginWebViewPage"
private val CONTEXT_KNOWN_KEYS = setOf("book", "chapter", "page", "annotations", "content", "quote", "note")

@Composable
fun PluginWebViewPage(
    pluginId: String,
    htmlEntryPath: String,
    pluginManager: PluginManager,
    conversationId: String? = null,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val statusBarHeightDp = with(density) {
        WindowInsets.statusBars.getTop(density).toDp().value.toInt()
    }
    val pluginLoader = koinInject<PluginLoader>()
    val chatService = koinInject<ChatService>()
    val pluginRepository = koinInject<PluginRepository>()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pendingImageCallback by remember { mutableStateOf<String?>(null) }
    var pendingFileCallback by remember { mutableStateOf<String?>(null) }
    var pendingBinaryFileCallback by remember { mutableStateOf<String?>(null) }
    var pendingImportAudioCallback by remember { mutableStateOf<String?>(null) }
    var webViewFileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    // State for saveFileAs (SAF CreateDocument)
    var pendingSaveCallbackId by remember { mutableStateOf<String?>(null) }
    var pendingSaveBase64Data by remember { mutableStateOf<String?>(null) }

    var isPageReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1200)
        isPageReady = true
    }

    val plugins by pluginManager.plugins.collectAsStateWithLifecycle()
    val pluginInfo = plugins.find { it.manifest.id == pluginId }

    val dataStore = remember(pluginId) {
        PluginDataStore(context, pluginId)
    }

    // Overlay WebView reference for pomodoro lock screen
    var overlayWebView by remember { mutableStateOf<WebView?>(null) }

    // Launcher for overlay permission (SYSTEM_ALERT_WINDOW)
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Result is handled by checking Settings.canDrawOverlays when needed
    }

    // Launcher for POST_NOTIFICATIONS permission
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Result is handled; proceed regardless
    }

    // Timer end broadcast receiver
    val timerEndReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == PomodoroTimerService.ACTION_TIMER_END) {
                    webView?.post {
                        webView?.evaluateJavascript(
                            "if(typeof window.onTimerEnd === 'function') { window.onTimerEnd(); }",
                            null
                        )
                    }
                    overlayWebView?.post {
                        overlayWebView?.evaluateJavascript(
                            "if(typeof window.onTimerEnd === 'function') { window.onTimerEnd(); }",
                            null
                        )
                    }
                }
            }
        }
    }

    // Music completion broadcast receiver
    val musicCompletedReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == MusicPlayerService.ACTION_MUSIC_COMPLETED) {
                    Log.i(TAG, "[DEBUG] musicCompletedReceiver received broadcast, webView is null: ${webView == null}")
                    webView?.post {
                        webView?.evaluateJavascript(
                            "if(typeof window.onMusicCompleted === 'function') { window.onMusicCompleted(); } else { console.error('[DEBUG] onMusicCompleted not a function'); }",
                            null
                        )
                    }
                }
            }
        }
    }

    // Register broadcast receivers
    DisposableEffect(Unit) {
        LocalBroadcastManager.getInstance(context).registerReceiver(
            timerEndReceiver,
            IntentFilter(PomodoroTimerService.ACTION_TIMER_END)
        )
        LocalBroadcastManager.getInstance(context).registerReceiver(
            musicCompletedReceiver,
            IntentFilter(MusicPlayerService.ACTION_MUSIC_COMPLETED)
        )
        onDispose {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(timerEndReceiver)
            LocalBroadcastManager.getInstance(context).unregisterReceiver(musicCompletedReceiver)
        }
    }

    // 文件选择器 - 用于 Bridge.pickFile()
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val bridgeCallback = pendingFileCallback
        if (bridgeCallback != null) {
            if (uri != null) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val content = inputStream?.bufferedReader()?.readText()
                    inputStream?.close()
                    val fileName = uri.lastPathSegment ?: "unknown.txt"
                    val escapedContent = content?.replace("\\", "\\\\")?.replace("'", "\\'")?.replace("\n", "\\n")?.replace("\r", "\\r") ?: ""
                    val escapedName = fileName.replace("\\", "\\\\").replace("'", "\\'")
                    webView?.post {
                        webView?.evaluateJavascript(
                            "window.__bridgeResult('$bridgeCallback', {success:true,fileName:'$escapedName',content:'$escapedContent'});", null
                        )
                    }
                } catch (e: Exception) {
                    val errMsg = e.message?.replace("\\", "\\\\")?.replace("'", "\\'") ?: "Unknown error"
                    webView?.post {
                        webView?.evaluateJavascript(
                            "window.__bridgeResult('$bridgeCallback', {success:false,error:'$errMsg'});", null
                        )
                    }
                }
            } else {
                webView?.post {
                    webView?.evaluateJavascript(
                        "window.__bridgeResult('$bridgeCallback', {success:false,error:'User cancelled'});", null
                    )
                }
            }
            pendingFileCallback = null
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        val callbackId = pendingImageCallback
        if (callbackId != null && uri != null) {
            try {
                val base64 = uriToBase64(context, uri)
                webView?.evaluateJavascript(
                    "if(window.__bridgeCallbacks && window.__bridgeCallbacks['$callbackId'])" +
                            "{window.__bridgeCallbacks['$callbackId']($base64); delete window.__bridgeCallbacks['$callbackId'];}",
                    null
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process picked image", e)
                webView?.evaluateJavascript(
                    "if(window.__bridgeCallbacks && window.__bridgeCallbacks['$callbackId'])" +
                            "{window.__bridgeCallbacks['$callbackId'](null); delete window.__bridgeCallbacks['$callbackId'];}",
                    null
                )
            }
        } else if (callbackId != null) {
            webView?.evaluateJavascript(
                "if(window.__bridgeCallbacks && window.__bridgeCallbacks['$callbackId'])" +
                        "{window.__bridgeCallbacks['$callbackId'](null); delete window.__bridgeCallbacks['$callbackId'];}",
                null
            )
        }
        pendingImageCallback = null
    }

    // Launcher for binary file picking (Bridge.pickBinaryFile)
    val binaryFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val callbackId = pendingBinaryFileCallback
        if (callbackId != null) {
            if (uri != null) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val bytes = inputStream?.readBytes()
                    inputStream?.close()
                    val base64 = if (bytes != null) Base64.encodeToString(bytes, Base64.NO_WRAP) else ""
                    val fileName = uri.lastPathSegment ?: "unknown"
                    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    val escapedName = fileName.replace("\\", "\\\\").replace("'", "\\'")
                    webView?.post {
                        webView?.evaluateJavascript(
                            "window.__bridgeResult('$callbackId', {success:true,fileName:'$escapedName',mimeType:'$mimeType',base64:'$base64'});", null
                        )
                    }
                } catch (e: Exception) {
                    val errMsg = e.message?.replace("\\", "\\\\")?.replace("'", "\\'") ?: "Unknown error"
                    webView?.post {
                        webView?.evaluateJavascript(
                            "window.__bridgeResult('$callbackId', {success:false,error:'$errMsg'});", null
                        )
                    }
                }
            } else {
                webView?.post {
                    webView?.evaluateJavascript(
                        "window.__bridgeResult('$callbackId', {success:false,error:'User cancelled'});", null
                    )
                }
            }
            pendingBinaryFileCallback = null
        }
    }

    // Launcher for importing audio files (Bridge.importAudioFile) - multi-select with duplicate detection
    val audioFileImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<@JvmSuppressWildcards Uri>? ->
        val callbackId = pendingImportAudioCallback
        if (callbackId != null) {
            if (uris.isNullOrEmpty()) {
                // null or empty list -> user cancelled
                webView?.post {
                    webView?.evaluateJavascript(
                        "window.__bridgeResult('$callbackId', {success:false,error:'User cancelled'});", null
                    )
                }
            } else {
                val imported = StringBuilder()
                val duplicates = StringBuilder()
                val errors = StringBuilder()
                val seenInBatch = mutableSetOf<String>()

                // Create music/ subdirectory once
                val musicDir = File(dataStore.getDataDir(), "music")
                if (!musicDir.exists()) {
                    musicDir.mkdirs()
                }

                for (uri in uris) {
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        val bytes = inputStream?.readBytes()
                        inputStream?.close()

                        if (bytes == null) {
                            val errName = uri.lastPathSegment ?: "unknown"
                            val escapedErrName = errName.replace("\\", "\\\\").replace("'", "\\'")
                            val escapedErr = "Failed to read file".replace("\\", "\\\\").replace("'", "\\'")
                            if (errors.isNotEmpty()) errors.append(",")
                            errors.append("{fileName:'$escapedErrName',error:'$escapedErr'}")
                            continue
                        }

                        // Get original file name from URI (reuse existing path/colon logic)
                        var fileName = uri.lastPathSegment ?: "unknown.mp3"
                        val lastSlash = fileName.lastIndexOf('/')
                        val lastColon = fileName.lastIndexOf(':')
                        val cutIndex = maxOf(lastSlash, lastColon)
                        if (cutIndex >= 0) {
                            fileName = fileName.substring(cutIndex + 1)
                        }

                        // Duplicate detection: existing in music/ OR already seen in this batch
                        val targetFile = File(musicDir, fileName)
                        if (targetFile.exists() || seenInBatch.contains(fileName)) {
                            val escapedDup = fileName.replace("\\", "\\\\").replace("'", "\\'")
                            if (duplicates.isNotEmpty()) duplicates.append(",")
                            duplicates.append("'$escapedDup'")
                        } else {
                            targetFile.writeBytes(bytes)
                            seenInBatch.add(fileName)

                            val filePath = targetFile.absolutePath
                            val escapedPath = filePath.replace("\\", "\\\\").replace("'", "\\'")
                            val escapedName = fileName.replace("\\", "\\\\").replace("'", "\\'")
                            if (imported.isNotEmpty()) imported.append(",")
                            imported.append("{filePath:'$escapedPath',fileName:'$escapedName'}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "importAudioFile: failed to process uri=$uri, err=${e.message}", e)
                        val errName = (uri.lastPathSegment ?: "unknown").replace("\\", "\\\\").replace("'", "\\'")
                        val escapedErr = (e.message ?: "Unknown error").replace("\\", "\\\\").replace("'", "\\'")
                        if (errors.isNotEmpty()) errors.append(",")
                        errors.append("{fileName:'$errName',error:'$escapedErr'}")
                    }
                }

                webView?.post {
                    webView?.evaluateJavascript(
                        "window.__bridgeResult('$callbackId', {success:true,imported:[$imported],duplicates:[$duplicates],errors:[$errors]});", null
                    )
                }
            }
            pendingImportAudioCallback = null
        }
    }

    // Launcher for saveFileAs (SAF CreateDocument - user picks save location)
    val saveFileAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val callbackId = pendingSaveCallbackId
        val base64Data = pendingSaveBase64Data
        if (callbackId != null) {
            if (uri != null && base64Data != null) {
                try {
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(bytes)
                        outputStream.flush()
                    }
                    val fileName = uri.lastPathSegment ?: "saved_file"
                    val escapedName = fileName.replace("\\", "\\\\").replace("'", "\\'")
                    webView?.post {
                        webView?.evaluateJavascript(
                            "window.__bridgeResult('$callbackId', {success:true,fileName:'$escapedName'});", null
                        )
                    }
                } catch (e: Exception) {
                    val errMsg = e.message?.replace("\\", "\\\\")?.replace("'", "\\'") ?: "Unknown error"
                    webView?.post {
                        webView?.evaluateJavascript(
                            "window.__bridgeResult('$callbackId', {success:false,error:'$errMsg'});", null
                        )
                    }
                }
            } else {
                webView?.post {
                    webView?.evaluateJavascript(
                        "window.__bridgeResult('$callbackId', {success:false,error:'User cancelled'});", null
                    )
                }
            }
            pendingSaveCallbackId = null
            pendingSaveBase64Data = null
        }
    }

    // Launcher for native HTML <input type="file"> file chooser
    val htmlFileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val callback = webViewFileChooserCallback
        if (callback != null) {
            if (uri != null) {
                callback.onReceiveValue(arrayOf(uri))
            } else {
                callback.onReceiveValue(null)
            }
            webViewFileChooserCallback = null
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
                Lifecycle.Event.ON_PAUSE -> webView?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler {
        val list = webView?.copyBackForwardList()
        val canGoBack = if (webView != null && list != null && list.currentIndex > 0) {
            val prevUrl = list.getItemAtIndex(list.currentIndex - 1)?.url
            prevUrl != null && !prevUrl.startsWith("about:blank")
        } else false
        if (canGoBack) {
            webView?.goBack()
        } else {
            onNavigateBack()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
            if (pluginInfo == null) {
                Text(
                    text = "插件不存在",
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PluginWebViewPool.acquire(ctx).apply {
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            webViewClient = PluginWebViewClient(
                                pluginInfo = pluginInfo,
                                dataStore = dataStore,
                                pluginLoader = pluginLoader,
                                pluginManager = pluginManager,
                                pluginRepository = pluginRepository,
                                initialConversationId = conversationId,
                                onRecordChatEvent = { action, taskName ->
                                    chatService.recordPluginActionEvent(conversationId, pluginId, action, taskName)
                                },
                                statusBarHeightDp = statusBarHeightDp,
                                onPageLoaded = {
                                    isPageReady = true
                                },
                                onPickImage = { callbackId ->
                                    pendingImageCallback = callbackId
                                    pickImageLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onPickFile = { callbackId ->
                                    pendingFileCallback = callbackId
                                    filePickerLauncher.launch(arrayOf("text/plain", "text/markdown", "application/octet-stream"))
                                },
                                onPickBinaryFile = { callbackId ->
                                    pendingBinaryFileCallback = callbackId
                                    binaryFilePickerLauncher.launch(arrayOf("*/*"))
                                },
                                onImportAudioFile = { callbackId ->
                                    pendingImportAudioCallback = callbackId
                                    audioFileImportLauncher.launch(arrayOf("audio/*"))
                                },
                                onSaveFileAs = { callbackId, fileName, base64Data ->
                                    pendingSaveCallbackId = callbackId
                                    pendingSaveBase64Data = base64Data
                                    saveFileAsLauncher.launch(fileName)
                                },
                                onClose = onNavigateBack,
                                onStartTimer = { wv, seconds ->
                                    // Check overlay permission
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        overlayPermissionLauncher.launch(intent)
                                        wv.post {
                                            wv.evaluateJavascript(
                                                "window.__bridgeResult('${null}', {success:false,error:'Overlay permission required. Please grant it in settings and try again.'});", null
                                            )
                                        }
                                        return@PluginWebViewClient
                                    }
                                    // Check notification permission
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                                            != android.content.pm.PackageManager.PERMISSION_GRANTED
                                        ) {
                                            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                    PomodoroTimerService.start(context, seconds)
                                    wv.post {
                                        // Find the callbackId from the pending bridge call
                                        // We use a simplified approach - the callback is handled in handleBridgeCall
                                    }
                                },
                                onStopTimer = {
                                    PomodoroTimerService.stop(context)
                                },
                                onShowOverlay = { wv, html ->
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                        wv.post {
                                            wv.evaluateJavascript(
                                                "window.__bridgeResult('${null}', {success:false,error:'Overlay permission not granted'});", null
                                            )
                                        }
                                        return@PluginWebViewClient
                                    }

                                    val activity = context as? Activity
                                    val windowManager = activity?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                                    if (windowManager == null) {
                                        wv.post {
                                            wv.evaluateJavascript(
                                                "window.__bridgeResult('${null}', {success:false,error:'Cannot access WindowManager'});", null
                                            )
                                        }
                                        return@PluginWebViewClient
                                    }

                                    // Remove existing overlay if any
                                    overlayWebView?.let { existingOverlay ->
                                        try {
                                            windowManager.removeView(existingOverlay)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Error removing existing overlay", e)
                                        }
                                        existingOverlay.destroy()
                                    }

                                    val overlayWv = WebView(context).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        isFocusable = true
                                        isFocusableInTouchMode = true
                                    }
                                    overlayWv.requestFocus()

                                    // Inject bridge JS for overlay
                                    overlayWv.webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            view?.evaluateJavascript(overlayBridgeJavascript, null)
                                            // Auto-focus input/textarea on click so keyboard appears
                                            view?.evaluateJavascript(
                                                "document.addEventListener('click', function(e){ if(e.target.tagName==='INPUT'||e.target.tagName==='TEXTAREA'){ e.target.focus(); } });",
                                                null
                                            )
                                        }

                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): Boolean {
                                            val url = request?.url?.toString() ?: return false
                                            if (url.startsWith("bridge://")) {
                                                val uri = Uri.parse(url)
                                                val method = uri.host ?: return false
                                                val params = uri.queryParameterNames.associateWith {
                                                    uri.getQueryParameter(it) ?: ""
                                                }
                                                when (method) {
                                                    "hideOverlay" -> {
                                                        try {
                                                            windowManager.removeView(overlayWv)
                                                        } catch (e: Exception) {
                                                            Log.w(TAG, "Error removing overlay", e)
                                                        }
                                                        overlayWv.destroy()
                                                        overlayWebView = null
                                                    }
                                                    "getTimerState" -> {
                                                        val running = PomodoroTimerService.isRunning()
                                                        val remaining = PomodoroTimerService.getRemainingSeconds()
                                                        val cbId = params["callbackId"] ?: ""
                                                        overlayWv.post {
                                                            overlayWv.evaluateJavascript(
                                                                "window.__bridgeResult('$cbId', {running:$running,remaining:$remaining});", null
                                                            )
                                                        }
                                                    }
                                                    "getData" -> {
                                                        val key = params["key"] ?: ""
                                                        val value = dataStore.getData(key)
                                                        val result = if (value != null) {
                                                            "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}\""
                                                        } else "null"
                                                        val cbId = params["callbackId"] ?: ""
                                                        overlayWv.post {
                                                            overlayWv.evaluateJavascript(
                                                                "window.__bridgeResult('$cbId', $result);", null
                                                            )
                                                        }
                                                    }
                                                    "setData" -> {
                                                        val key = params["key"] ?: ""
                                                        val value = params["value"] ?: ""
                                                        dataStore.setData(key, value)
                                                        val cbId = params["callbackId"] ?: ""
                                                        overlayWv.post {
                                                            overlayWv.evaluateJavascript(
                                                                "window.__bridgeResult('$cbId', true);", null
                                                            )
                                                        }
                                                    }
                                                    "callAI" -> {
                                                        val cbId = params["callbackId"] ?: ""
                                                        val prompt = params["prompt"] ?: ""
                                                        val contextJson = params["context"] ?: "{}"
                                                        CoroutineScope(Dispatchers.IO).launch {
                                                            try {
                                                                // Use the PluginWebViewClient's callAI via a simple approach
                                                                val settingsStore: SettingsStore = org.koin.java.KoinJavaComponent.get(SettingsStore::class.java)
                                                                val providerManager: ProviderManager = org.koin.java.KoinJavaComponent.get(ProviderManager::class.java)
                                                                val settings = settingsStore.settingsFlow.first()
                                                                val model = settings.getCurrentChatModel()
                                                                val aiResult = if (model == null) {
                                                                    """{"success":false,"error":"No chat model configured"}"""
                                                                } else {
                                                                    val providerSetting = model.findProvider(settings.providers)
                                                                    if (providerSetting == null) {
                                                                        """{"success":false,"error":"Provider not found"}"""
                                                                    } else {
                                                                        val providerImpl = providerManager.getProviderByType(providerSetting)
                                                                        val systemPrompt = "你是一个番茄钟陪伴助手。用户正在使用番茄钟专注。请用简短温暖的话回应，鼓励用户保持专注。当前上下文：$contextJson"
                                                                        val messages = listOf(
                                                                            UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(systemPrompt))),
                                                                            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(prompt)))
                                                                        )
                                                                        val textGenParams = TextGenerationParams(model = model, tools = emptyList(), temperature = 0.7f)
                                                                        val response = providerImpl.generateText(providerSetting, messages, textGenParams)
                                                                        val responseText = response.choices.firstOrNull()?.message?.parts
                                                                            ?.filterIsInstance<UIMessagePart.Text>()
                                                                            ?.firstOrNull()?.text ?: ""
                                                                        val escaped = responseText.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                                                                        """{"success":true,"text":"$escaped"}"""
                                                                    }
                                                                }
                                                                overlayWv.post {
                                                                    overlayWv.evaluateJavascript("window.__bridgeResult('$cbId', $aiResult);", null)
                                                                }
                                                            } catch (e: Exception) {
                                                                val err = """{"success":false,"error":"${e.message?.replace("\"", "\\\"")?.replace("\\", "\\\\")}"}"""
                                                                overlayWv.post {
                                                                    overlayWv.evaluateJavascript("window.__bridgeResult('$cbId', $err);", null)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                return true
                                            }
                                            return false
                                        }
                                    }

                                    // Block back key
                                    overlayWv.setOnKeyListener { _, keyCode, event ->
                                        keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP
                                    }

                                    val params = WindowManager.LayoutParams(
                                        WindowManager.LayoutParams.MATCH_PARENT,
                                        WindowManager.LayoutParams.MATCH_PARENT,
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                        else
                                            @Suppress("DEPRECATION")
                                            WindowManager.LayoutParams.TYPE_PHONE,
                                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                                        android.graphics.PixelFormat.TRANSLUCENT
                                    )
                                    params.gravity = Gravity.CENTER
                                    params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

                                    // Load HTML content
                                    overlayWv.loadDataWithBaseURL(
                                        "https://rikkahub.local",
                                        html,
                                        "text/html",
                                        "UTF-8",
                                        null
                                    )

                                    windowManager.addView(overlayWv, params)
                                    overlayWebView = overlayWv
                                },
                                onHideOverlay = {
                                    val activity = context as? Activity
                                    val windowManager = activity?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                                    overlayWebView?.let { existingOverlay ->
                                        try {
                                            windowManager?.removeView(existingOverlay)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Error removing overlay", e)
                                        }
                                        existingOverlay.destroy()
                                        overlayWebView = null
                                    }
                                }
                            )

                            // 处理 HTML <input type="file"> 文件选择
                            webChromeClient = object : WebChromeClient() {
                                override fun onShowFileChooser(
                                    webView: WebView?,
                                    filePathCallback: ValueCallback<Array<Uri>>?,
                                    fileChooserParams: FileChooserParams?
                                ): Boolean {
                                    // Cancel any previous callback
                                    webViewFileChooserCallback?.onReceiveValue(null)
                                    webViewFileChooserCallback = filePathCallback

                                    val acceptTypes = fileChooserParams?.acceptTypes
                                        ?.filter { it.isNotBlank() }
                                        ?.toTypedArray()
                                        ?: arrayOf("*/*")
                                    // If no meaningful accept types, use */*
                                    if (acceptTypes.isEmpty()) {
                                        htmlFileChooserLauncher.launch(arrayOf("*/*"))
                                    } else {
                                        htmlFileChooserLauncher.launch(acceptTypes)
                                    }
                                    return true
                                }
                            }

                            // 禁用原生长按选择菜单 - 通过CSS/JS控制，不再用原生拦截
                            // （原生setOnLongClickListener会阻止批注模式的文字选择）

                            val htmlFile = File(pluginInfo.directory, htmlEntryPath)
                            if (htmlFile.exists()) {
                                loadUrl("file://${htmlFile.absolutePath}")
                            } else {
                                loadData(
                                    "<html><body><h2>\u9875\u9762\u6587\u4EF6\u4E0D\u5B58\u5728</h2><p>$htmlEntryPath</p></body></html>",
                                    "text/html",
                                    "UTF-8"
                                )
                                isPageReady = true
                            }

                            webView = this@apply
                        }
                    },
                    update = { wv ->
                        webView = wv
                    }
                )

                AnimatedVisibility(
                    visible = !isPageReady,
                    enter = fadeIn(),
                    exit = fadeOut(animationSpec = tween(150))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

    DisposableEffect(Unit) {
        onDispose {
            // Clean up main WebView and return to pool
            webView?.let { wv ->
                PluginWebViewPool.release(wv)
            }
            webView = null
            // Clean up overlay WebView
            overlayWebView?.let { overlay ->
                try {
                    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    windowManager?.removeView(overlay)
                } catch (e: Exception) {
                    Log.w(TAG, "Error removing overlay on dispose", e)
                }
                overlay.destroy()
            }
            overlayWebView = null
            // Stop timer service
            PomodoroTimerService.stop(context)
        }
    }
}

private class PluginWebViewClient(
    private val pluginInfo: PluginInfo,
    private val dataStore: PluginDataStore,
    private val pluginLoader: PluginLoader,
    private val pluginManager: PluginManager,
    private val pluginRepository: PluginRepository,
    private val initialConversationId: String? = null,
    private val onRecordChatEvent: suspend (action: String, taskName: String) -> Boolean,
    private val statusBarHeightDp: Int,
    private val onPageLoaded: (() -> Unit)? = null,
    private val onPickImage: (callbackId: String) -> Unit,
    private val onPickFile: (callbackId: String) -> Unit,
    private val onPickBinaryFile: (callbackId: String) -> Unit,
    private val onImportAudioFile: (callbackId: String) -> Unit,
    private val onSaveFileAs: (callbackId: String, fileName: String, base64Data: String) -> Unit,
    private val onClose: () -> Unit,
    private val onStartTimer: (webView: WebView, seconds: Int) -> Unit,
    private val onStopTimer: () -> Unit,
    private val onShowOverlay: (webView: WebView, html: String) -> Unit,
    private val onHideOverlay: () -> Unit
) : WebViewClient() {
    private val json = Json { ignoreUnknownKeys = true }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        if (url != null && !url.startsWith("about:blank")) {
            view?.evaluateJavascript(bridgeJavascript, null)
            onPageLoaded?.invoke()
        }
    }

    private fun getSafeFile(baseDir: File, path: String): File? {
        if (path.isBlank()) return null
        val target = File(baseDir, path).canonicalFile
        val base = baseDir.canonicalFile
        return if (target.path == base.path || target.path.startsWith(base.path + File.separator)) {
            target
        } else {
            Log.w(TAG, "Blocked path traversal attempt: path='$path', base='$base'")
            null
        }
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url?.toString() ?: return false
        if (url.startsWith("bridge://")) {
            handleBridgeCall(view!!, url)
            return true
        }
        if (!url.startsWith("file://") && !url.startsWith("about:blank")) {
            // Open external URLs (e.g. Supabase download links) in system browser
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                view?.context?.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open external URL: $url", e)
            }
            return true
        }
        return false
    }

    private var hasClearedInitialHistory = false

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (url == null || url.startsWith("about:blank")) return

        if (!hasClearedInitialHistory) {
            hasClearedInitialHistory = true
            view?.clearHistory()
        }

        onPageLoaded?.invoke()
        view?.evaluateJavascript(bridgeJavascript, null)
        if (statusBarHeightDp > 0) {
            val safeAreaJs = """
                (function() {
                    var styleId = '__orangechat_safe_area_style__';
                    if (!document.getElementById(styleId)) {
                        var style = document.createElement('style');
                        style.id = styleId;
                        style.innerHTML = ':root { --safe-area-inset-top: ${statusBarHeightDp}px; --sat: ${statusBarHeightDp}px; } body { padding-top: ${statusBarHeightDp}px !important; box-sizing: border-box !important; }';
                        (document.head || document.documentElement).appendChild(style);
                    }
                })();
            """.trimIndent()
            view?.evaluateJavascript(safeAreaJs, null)
        }
    }

    private fun handleBridgeCall(webView: WebView, url: String) {
        val uri = Uri.parse(url)
        val method = uri.host ?: return
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }

        when (method) {
            "getPluginConfig" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val savedConfig = pluginRepository.getPluginConfig(pluginInfo.manifest.id)
                        val mergedConfig = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()

                        pluginInfo.manifest.config.forEach { field ->
                            if (savedConfig.containsKey(field.name)) {
                                mergedConfig[field.name] = savedConfig[field.name]!!
                            } else if (field.default != null) {
                                mergedConfig[field.name] = field.default
                            }
                        }

                        savedConfig.forEach { (key, value) ->
                            if (!mergedConfig.containsKey(key)) {
                                mergedConfig[key] = value
                            }
                        }

                        val jsonObj = JsonObject(mergedConfig)
                        val result = json.encodeToString(JsonObject.serializer(), jsonObj)
                        Log.d(TAG, "getPluginConfig for ${pluginInfo.manifest.id}: $result")
                        val callbackId = params["callbackId"] ?: ""
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult(${JSONObject.quote(callbackId)}, $result);", null
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get plugin config", e)
                        val callbackId = params["callbackId"] ?: ""
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult(${JSONObject.quote(callbackId)}, {});", null
                            )
                        }
                    }
                }
            }

            "getData" -> {
                val key = params["key"] ?: ""
                val value = dataStore.getData(key)
                val quotedValue = if (value != null) JSONObject.quote(value) else "null"
                val callbackId = params["callbackId"] ?: ""
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult(${JSONObject.quote(callbackId)}, $quotedValue);", null
                    )
                }
            }

            "setData" -> {
                val key = params["key"] ?: ""
                val value = params["value"] ?: ""
                val callbackId = params["callbackId"] ?: ""
                dataStore.setData(key, value)
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult(${JSONObject.quote(callbackId)}, true);", null
                    )
                }
            }

            "deleteData" -> {
                val key = params["key"] ?: ""
                val callbackId = params["callbackId"] ?: ""
                dataStore.deleteData(key)
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult(${JSONObject.quote(callbackId)}, true);", null
                    )
                }
            }

            "listData" -> {
                val keys = dataStore.listData()
                val jsonArray = JSONArray(keys)
                val callbackId = params["callbackId"] ?: ""
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult(${JSONObject.quote(callbackId)}, ${jsonArray.toString()});", null
                    )
                }
            }

            "pickImage" -> {
                val callbackId = params["callbackId"] ?: ""
                onPickImage(callbackId)
            }

            "pickFile" -> {
                val callbackId = params["callbackId"] ?: ""
                onPickFile(callbackId)
            }

            "pickBinaryFile" -> {
                val callbackId = params["callbackId"] ?: ""
                onPickBinaryFile(callbackId)
            }

            "importAudioFile" -> {
                val callbackId = params["callbackId"] ?: ""
                onImportAudioFile(callbackId)
            }

            "writeFile" -> {
                val fileName = params["fileName"] ?: ""
                val base64Data = params["data"] ?: ""
                val callbackId = params["callbackId"] ?: ""
                try {
                    val dir = dataStore.getDataDir()
                    val file = getSafeFile(dir, fileName) ?: throw SecurityException("Invalid file path")
                    file.parentFile?.mkdirs()
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    file.writeBytes(bytes)
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult(${JSONObject.quote(callbackId)}, ${JSONObject.quote(file.absolutePath)});", null
                        )
                    }
                } catch (e: Exception) {
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult(${JSONObject.quote(callbackId)}, null);", null
                        )
                    }
                }
            }

            "readFile" -> {
                val fileName = params["fileName"] ?: ""
                val callbackId = params["callbackId"] ?: ""
                try {
                    val dir = dataStore.getDataDir()
                    val file = getSafeFile(dir, fileName) ?: throw SecurityException("Invalid file path")
                    if (file.exists()) {
                        val bytes = file.readBytes()
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult(${JSONObject.quote(callbackId)}, ${JSONObject.quote(base64)});", null
                            )
                        }
                    } else {
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult(${JSONObject.quote(callbackId)}, null);", null
                            )
                        }
                    }
                } catch (e: Exception) {
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult(${JSONObject.quote(callbackId)}, null);", null
                        )
                    }
                }
            }

            "listFiles" -> {
                val dirPath = params["dir"] ?: ""
                val callbackId = params["callbackId"] ?: ""
                val dataDir = dataStore.getDataDir()
                val targetDir = if (dirPath.isBlank()) dataDir else getSafeFile(dataDir, dirPath)
                val files = if (targetDir != null && targetDir.exists() && targetDir.isDirectory) {
                    targetDir.listFiles()?.map { it.name } ?: emptyList()
                } else emptyList()
                val jsonArray = JSONArray(files)
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult(${JSONObject.quote(callbackId)}, ${jsonArray.toString()});", null
                    )
                }
            }

            "deleteFile" -> {
                val fileName = params["fileName"] ?: ""
                val callbackId = params["callbackId"] ?: ""
                val dir = dataStore.getDataDir()
                val file = getSafeFile(dir, fileName)
                val result = if (file != null && file.exists()) file.delete() else false
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult(${JSONObject.quote(callbackId)}, $result);", null
                    )
                }
            }

            "musicPlay" -> {
                val filePath = params["filePath"] ?: ""
                val title = params["title"] ?: ""
                val artist = params["artist"] ?: ""
                val callbackId = params["callbackId"] ?: ""
                try {
                    MusicPlayerService.play(webView.context, filePath, title, artist)
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult(${JSONObject.quote(callbackId)}, {success:true});", null
                        )
                    }
                } catch (e: Exception) {
                    val errMsg = e.message ?: "Unknown error"
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult(${JSONObject.quote(callbackId)}, {success:false,error:${JSONObject.quote(errMsg)}});", null
                        )
                    }
                }
            }

            "musicPause" -> {
                val callbackId = params["callbackId"] ?: ""
                try {
                    MusicPlayerService.pause(webView.context)
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult(${JSONObject.quote(callbackId)}, {success:true});", null
                        )
                    }
                } catch (e: Exception) {
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult(${JSONObject.quote(callbackId)}, {success:false});", null
                        )
                    }
                }
            }

            "musicResume" -> {
                val callbackId = params["callbackId"] ?: ""
                try {
                    MusicPlayerService.resume(webView.context)
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult(${JSONObject.quote(callbackId)}, {success:true});", null
                        )
                    }
                } catch (e: Exception) {
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult(${JSONObject.quote(callbackId)}, {success:false});", null
                        )
                    }
                }
            }

            "musicStop" -> {
                val callbackId = params["callbackId"] ?: ""
                try {
                    MusicPlayerService.stop(webView.context)
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult(${JSONObject.quote(callbackId)}, {success:true});", null
                        )
                    }
                } catch (e: Exception) {
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult(${JSONObject.quote(callbackId)}, {success:false});", null
                        )
                    }
                }
            }

            "musicSeek" -> {
                val callbackId = params["callbackId"] ?: ""
                val position = params["position"]?.toIntOrNull() ?: 0
                try {
                    MusicPlayerService.seekTo(webView.context, position)
                    webView.post { webView.evaluateJavascript("window.__bridgeResult(${JSONObject.quote(callbackId)}, {success:true});", null) }
                } catch (e: Exception) {
                    Log.e(TAG, "musicSeek failed, position=$position, err=${e.message}", e)
                    webView.post { webView.evaluateJavascript("window.__bridgeResult(${JSONObject.quote(callbackId)}, {success:false});", null) }
                }
            }

            "musicGetProgress" -> {
                val callbackId = params["callbackId"] ?: ""
                try {
                    val position = MusicPlayerService.getCurrentPosition()
                    val duration = MusicPlayerService.getDuration()
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult(${JSONObject.quote(callbackId)}, {position:$position,duration:$duration});", null
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "musicGetProgress failed, err=${e.message}", e)
                    webView.post { webView.evaluateJavascript("window.__bridgeResult(${JSONObject.quote(callbackId)}, {position:0,duration:0});", null) }
                }
            }

            "close" -> {
                onClose()
            }

            "callTool" -> {
                val toolName = params["toolName"] ?: ""
                val toolParams = params["params"] ?: "{}"
                val callbackId = params["callbackId"] ?: ""
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val result = callPluginTool(toolName, toolParams)
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult(${JSONObject.quote(callbackId)}, ${result});", null
                            )
                        }
                    } catch (e: Exception) {
                        val errorResult = buildBridgeResultJson(success = false, error = e.message ?: "callTool failed")
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult(${JSONObject.quote(callbackId)}, $errorResult);", null
                            )
                        }
                    }
                }
            }

            "getCurrentConversationId" -> {
                val callbackId = params["callbackId"] ?: ""
                val convIdResult = initialConversationId?.let { JSONObject.quote(it) } ?: "null"
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult(${JSONObject.quote(callbackId)}, $convIdResult);", null
                    )
                }
            }

            "recordChatEvent" -> {
                val callbackId = params["callbackId"] ?: ""
                val action = params["action"] ?: ""
                val taskName = params["taskName"] ?: ""
                CoroutineScope(Dispatchers.IO).launch {
                    val result = runCatching { onRecordChatEvent(action, taskName) }
                    val response = when {
                        result.isFailure -> buildBridgeResultJson(success = false, error = "聊天事件写入失败")
                        result.getOrDefault(false) -> buildBridgeResultJson(success = true)
                        else -> buildBridgeResultJson(success = false, error = "当前入口没有关联聊天")
                    }
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult(${JSONObject.quote(callbackId)}, $response);", null
                        )
                    }
                }
            }

            "callAI" -> {
                val callbackId = params["callbackId"] ?: ""
                val prompt = params["prompt"] ?: ""

                if (!pluginInfo.manifest.permissions.contains("ai_chat")) {
                    val errorResult = buildBridgeResultJson(
                        success = false,
                        error = "Permission denied: ai_chat permission not declared in manifest"
                    )
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult(${JSONObject.quote(callbackId)}, $errorResult);", null
                        )
                    }
                    return
                }

                if (prompt.isBlank()) {
                    val errorResult = buildBridgeResultJson(
                        success = false,
                        error = "prompt is required"
                    )
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult(${JSONObject.quote(callbackId)}, $errorResult);", null
                        )
                    }
                    return
                }

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val aiResult = callAI(params)
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult(${JSONObject.quote(callbackId)}, $aiResult);", null
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "callAI failed", e)
                        val errorResult = buildBridgeResultJson(
                            success = false,
                            error = e.message ?: "callAI failed"
                        )
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult(${JSONObject.quote(callbackId)}, $errorResult);", null
                            )
                        }
                    }
                }
            }

            "notifyHook" -> {
                val callbackId = params["callbackId"] ?: ""
                val hookName = params["hookName"] ?: ""
                val hookContextJson = params["context"] ?: "{}"

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val hookResult = handleHookTrigger(webView, hookName, hookContextJson)
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult(${JSONObject.quote(callbackId)}, $hookResult);", null
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "notifyHook failed", e)
                        val errorResult = buildBridgeResultJson(
                            success = false,
                            error = e.message ?: "notifyHook failed"
                        )
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult(${JSONObject.quote(callbackId)}, $errorResult);", null
                            )
                        }
                    }
                }
            }

            "startTimer" -> {
                val callbackId = params["callbackId"] ?: ""
                val seconds = params["seconds"]?.toIntOrNull() ?: (25 * 60)
                onStartTimer(webView, seconds)
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult(${JSONObject.quote(callbackId)}, {success:true});", null
                    )
                }
            }

            "stopTimer" -> {
                val callbackId = params["callbackId"] ?: ""
                onStopTimer()
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult(${JSONObject.quote(callbackId)}, {success:true});", null
                    )
                }
            }

            "getTimerState" -> {
                val callbackId = params["callbackId"] ?: ""
                val running = PomodoroTimerService.isRunning()
                val remaining = PomodoroTimerService.getRemainingSeconds()
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult(${JSONObject.quote(callbackId)}, {running:$running,remaining:$remaining});", null
                    )
                }
            }

            "showOverlay" -> {
                val callbackId = params["callbackId"] ?: ""
                val html = params["html"] ?: ""
                onShowOverlay(webView, html)
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult(${JSONObject.quote(callbackId)}, {success:true});", null
                    )
                }
            }

            "saveFileAs" -> {
                val callbackId = params["callbackId"] ?: ""
                val fileName = params["fileName"] ?: "untitled.json"
                val base64Data = params["data"] ?: ""
                onSaveFileAs(callbackId, fileName, base64Data)
            }

            "getActivityCalories" -> {
                val date = params["date"] ?: ""
                val explicitPath = params["customPath"] ?: ""
                val callbackId = params["callbackId"] ?: ""
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val configuredPath = try {
                            val store: SettingsStore = org.koin.java.KoinJavaComponent.get(SettingsStore::class.java)
                            store.settingsFlow.value.systemToolsSetting.gadgetbridgeDbPath
                        } catch (_: Exception) { "" }
                        val finalPath = explicitPath.ifBlank { configuredPath }
                        val result = GadgetbridgeReader.readActivityCaloriesForDate(date, finalPath)
                        val jsonResult = JSONObject().apply {
                            put("success", true)
                            put("date", result.date)
                            if (result.calories != null) {
                                put("calories", result.calories)
                            } else {
                                put("calories", JSONObject.NULL)
                            }
                            put("status", result.status)
                            if (result.lastUpdated != null) {
                                put("lastUpdated", result.lastUpdated)
                            } else {
                                put("lastUpdated", JSONObject.NULL)
                            }
                        }.toString()
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult(${JSONObject.quote(callbackId)}, $jsonResult);", null
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get activity calories", e)
                        val errResult = JSONObject().apply {
                            put("success", false)
                            put("date", date)
                            put("status", "error")
                            put("calories", JSONObject.NULL)
                            put("error", e.message ?: "Unknown error")
                        }.toString()
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult(${JSONObject.quote(callbackId)}, $errResult);", null
                            )
                        }
                    }
                }
            }

            "hideOverlay" -> {
                val callbackId = params["callbackId"] ?: ""
                onHideOverlay()
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult(${JSONObject.quote(callbackId)}, {success:true});", null
                    )
                }
            }
        }
    }

    private fun formatContextSummary(contextJson: String): String {
        if (contextJson.isBlank() || contextJson == "{}") return ""
        return try {
            val ctx = JSONObject(contextJson)
            val sb = StringBuilder()
            if (ctx.has("book")) sb.append("\n书名：").append(ctx.optString("book"))
            if (ctx.has("chapter")) {
                val ch = ctx.optString("chapter").trim()
                if (ch.isNotEmpty()) {
                    val formatted = if (ch.startsWith("第") || ch.endsWith("章")) ch else "第${ch}章"
                    sb.append("\n章节：").append(formatted)
                }
            }
            if (ctx.has("page")) {
                val pg = ctx.optString("page").trim()
                if (pg.isNotEmpty()) {
                    val formatted = if (pg.startsWith("第") || pg.endsWith("页")) pg else "第${pg}页"
                    sb.append("\n页码：").append(formatted)
                }
            }
            if (ctx.has("annotations")) sb.append("\n已有批注：").append(ctx.optString("annotations"))
            if (ctx.has("content")) sb.append("\n页面内容：").append(ctx.optString("content"))
            if (ctx.has("quote")) sb.append("\n引用原文：").append(ctx.optString("quote"))
            if (ctx.has("note")) sb.append("\n用户批注：").append(ctx.optString("note"))
            val it = ctx.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (k !in CONTEXT_KNOWN_KEYS) {
                    sb.append("\n$k：").append(ctx.opt(k))
                }
            }
            sb.toString().trim()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse context JSON", e)
            ""
        }
    }

    private fun buildBridgeResultJson(
        success: Boolean,
        text: String? = null,
        error: String? = null,
        conversationId: String? = null,
        messageId: String? = null,
        extra: Map<String, Any?> = emptyMap(),
    ): String {
        val json = JSONObject()
        json.put("success", success)
        if (text != null) json.put("text", text)
        if (error != null) json.put("error", error)
        if (conversationId != null) json.put("conversationId", conversationId)
        if (messageId != null) json.put("messageId", messageId)
        for ((k, v) in extra) {
            json.put(k, v)
        }
        return json.toString()
    }

    private suspend fun callAI(params: Map<String, String>): String {
        if (!pluginInfo.manifest.permissions.contains("ai_chat")) {
            return buildBridgeResultJson(
                success = false,
                error = "Permission denied: ai_chat permission not declared in manifest"
            )
        }

        val prompt = params["prompt"] ?: ""
        val contextJson = params["context"] ?: "{}"
        val conversationIdParam = params["conversationId"]?.takeIf { it.isNotBlank() } ?: initialConversationId
        val syncToChat = params["syncToChat"]?.toBooleanStrictOrNull() ?: false
        val loadHistory = params["loadHistory"]?.toBooleanStrictOrNull() ?: syncToChat
        val historyLimit = (params["historyLimit"]?.toIntOrNull() ?: 10).coerceIn(1, 50)
        val assistantIdParam = params["assistantId"]?.takeIf { it.isNotBlank() }
        val timeoutSeconds = (params["timeout"]?.toLongOrNull() ?: 120L).coerceIn(10L, 300L)

        val settingsStore: SettingsStore = org.koin.java.KoinJavaComponent.get(SettingsStore::class.java)
        val providerManager: ProviderManager = org.koin.java.KoinJavaComponent.get(ProviderManager::class.java)
        val conversationRepo: ConversationRepository = org.koin.java.KoinJavaComponent.get(ConversationRepository::class.java)
        val chatService: ChatService = org.koin.java.KoinJavaComponent.get(ChatService::class.java)

        val settings = settingsStore.settingsFlow.first()
        val assistant = if (assistantIdParam != null) {
            settings.assistants.find { it.id.toString() == assistantIdParam } ?: settings.getCurrentAssistant()
        } else {
            settings.getCurrentAssistant()
        }

        val momentImageUrls = if (pluginInfo.manifest.id == MomentsImageAttachments.PLUGIN_ID) {
            runCatching {
                Json.parseToJsonElement(params["imageUrls"] ?: "[]")
                    .let { element ->
                        (element as? kotlinx.serialization.json.JsonArray)
                            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                            .orEmpty()
                    }
            }.getOrDefault(emptyList()).filter(String::isNotBlank)
        } else {
            emptyList()
        }
        val selectedModel = assistant.chatModelId?.let { settings.findModelById(it) }
            ?: settings.getCurrentChatModel()
        val canSendImages = selectedModel?.inputModalities?.contains(Modality.IMAGE) == true
        val imageResult = if (momentImageUrls.isNotEmpty() && canSendImages) {
            val filesManager: FilesManager = org.koin.java.KoinJavaComponent.get(FilesManager::class.java)
            val client: OkHttpClient = org.koin.java.KoinJavaComponent.get(OkHttpClient::class.java)
            MomentsImageAttachments.download(
                urls = momentImageUrls,
                allowedHosts = pluginInfo.manifest.allowedHosts,
                downloader = SharedPostImageDownloader(client),
                filesManager = filesManager,
                pluginDataStore = dataStore,
            )
        } else {
            MomentsImageAttachments.DownloadResult(emptyList(), momentImageUrls.size)
        }
        val imageStatus = MomentsImageAttachments.statusText(
            totalCount = momentImageUrls.size,
            result = imageResult,
            supportsImages = canSendImages,
        ).takeIf(String::isNotBlank)?.let { "\n\n$it" }.orEmpty()

        // 场景 A: 需要将问答同步并落库到主会话中
        if (syncToChat) {
            val targetConvId: Uuid = when {
                conversationIdParam.isNullOrBlank() || conversationIdParam == "recent" || conversationIdParam == "current" -> {
                    val recent = conversationRepo.getRecentConversations(assistant.id, limit = 1)
                    recent.firstOrNull()?.id ?: Uuid.random()
                }
                else -> runCatching { Uuid.parse(conversationIdParam) }.getOrElse { Uuid.random() }
            }

            chatService.addConversationReference(targetConvId)
            return try {
                chatService.initializeConversation(targetConvId)

                // 发送前记录已有 assistant 消息与已知错误
                val initialConv = chatService.getConversationFlow(targetConvId).value
                val lastAssistantIdBefore = initialConv.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
                val errorIdsBefore = chatService.errors.value
                    .filter { it.conversationId == targetConvId }
                    .map { it.id }
                    .toSet()

                // 格式化上下文与自定义人设，前置附加到用户消息中
                val customSystemPrompt = params["systemPrompt"]?.takeIf { it.isNotBlank() }
                val contextSummary = formatContextSummary(contextJson)
                val finalPrompt = buildString {
                    if (!customSystemPrompt.isNullOrBlank()) {
                        append("[插件背景要求]\n").append(customSystemPrompt).append("\n\n")
                    }
                    if (contextSummary.isNotBlank()) {
                        append("[插件上下文信息]\n").append(contextSummary).append("\n\n")
                    }
                    append(prompt)
                    append(imageStatus)
                }

                // 发送用户消息并触发 AI 回复生成与落库
                chatService.sendMessage(
                    conversationId = targetConvId,
                    content = listOf(UIMessagePart.Text(finalPrompt)) + imageResult.images,
                    answer = true
                )

                // 等待生成结束 (默认 120 秒，支持 params["timeout"] 自定义)
                val finished = withTimeoutOrNull(timeoutSeconds * 1000L) {
                    chatService.generationDoneFlow.first { it == targetConvId }
                }

                if (finished == null) {
                    // 超时时主动取消底层后台任务
                    runCatching { chatService.stopGeneration(targetConvId) }
                    return buildBridgeResultJson(
                        success = false,
                        error = "AI 生成超时 (${timeoutSeconds}s)",
                        conversationId = targetConvId.toString()
                    )
                }

                // 检查是否捕获到新增错误
                val newErrors = chatService.errors.value
                    .filter { it.conversationId == targetConvId && it.id !in errorIdsBefore }
                if (newErrors.isNotEmpty()) {
                    val lastError = newErrors.last()
                    val errorDetails = listOfNotNull(lastError.title, lastError.error.message ?: lastError.error.toString())
                        .joinToString(": ")
                    return buildBridgeResultJson(
                        success = false,
                        error = errorDetails.ifBlank { "AI 生成失败" },
                        conversationId = targetConvId.toString()
                    )
                }

                val currentConv = chatService.getConversationFlow(targetConvId).value
                val lastAssistant = currentConv.currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }

                if (lastAssistant == null || (lastAssistantIdBefore != null && lastAssistant.id == lastAssistantIdBefore)) {
                    return buildBridgeResultJson(
                        success = false,
                        error = "未生成有效回复",
                        conversationId = targetConvId.toString()
                    )
                }

                val replyText = lastAssistant.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                    .trim()

                if (replyText.isEmpty()) {
                    return buildBridgeResultJson(
                        success = false,
                        error = "未生成有效回复",
                        conversationId = targetConvId.toString()
                    )
                }

                buildBridgeResultJson(
                    success = true,
                    text = replyText,
                    conversationId = targetConvId.toString(),
                    messageId = lastAssistant.id.toString()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Sync to chat failed", e)
                buildBridgeResultJson(
                    success = false,
                    error = e.message ?: "Sync to chat failed",
                    conversationId = targetConvId.toString()
                )
            } finally {
                chatService.removeConversationReference(targetConvId)
            }
        }

        // 场景 B: 独立请求或需要加载历史上下文 (不落库)
        // 优先使用 assistant 配置的 chatModelId，若未配置或不存在则 fallback 到当前模型
        val model = assistant.chatModelId?.let { settings.findModelById(it) }
            ?: settings.getCurrentChatModel()
            ?: return buildBridgeResultJson(success = false, error = "No chat model configured")

        val providerSetting = model.findProvider(settings.providers)
            ?: return buildBridgeResultJson(success = false, error = "Provider not found for model: ${model.displayName.ifBlank { model.modelId }}")

        val providerImpl = providerManager.getProviderByType(providerSetting)

        val customSystemPrompt = params["systemPrompt"]?.takeIf { it.isNotBlank() }
        val systemPrompt = buildString {
            // 1. 继承助手的 systemPrompt
            if (assistant.systemPrompt.isNotBlank()) {
                append(assistant.systemPrompt)
            }

            // 2. 插件自定义 prompt 或默认身份
            if (!customSystemPrompt.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(customSystemPrompt)
            } else if (isEmpty()) {
                if (contextJson.contains("\"book\"") || contextJson.contains("\"chapter\"") || contextJson.contains("\"annotations\"")) {
                    append("你是一个阅读助手。请根据用户的问题给出有深度的、温柔的回答。")
                } else {
                    append("你是一个智能助手。请根据用户的问题给出有深度的、准确的回答。")
                }
            }

            // 3. 插件上下文
            val contextSummary = formatContextSummary(contextJson)
            if (contextSummary.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("当前上下文：\n").append(contextSummary)
            }
        }

        val messages = buildList {
            add(
                UIMessage(
                    role = MessageRole.SYSTEM,
                    parts = listOf(UIMessagePart.Text(systemPrompt))
                )
            )

            if (loadHistory) {
                val targetConvId: Uuid? = when {
                    conversationIdParam.isNullOrBlank() || conversationIdParam == "recent" || conversationIdParam == "current" -> {
                        conversationRepo.getRecentConversations(assistant.id, limit = 1).firstOrNull()?.id
                    }
                    else -> runCatching { Uuid.parse(conversationIdParam) }.getOrNull()
                }

                if (targetConvId != null) {
                    val historyConv = conversationRepo.getConversationById(targetConvId)
                    val historyMessages = historyConv?.currentMessages
                        ?.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
                        ?.takeLast(historyLimit)
                        ?.toMutableList()
                        ?: mutableListOf()

                    // 确保多轮历史以 USER 开头（Google Gemini 等 API 严格要求首个 turn 必须为 USER 角色）
                    while (historyMessages.isNotEmpty() && historyMessages.first().role == MessageRole.ASSISTANT) {
                        historyMessages.removeAt(0)
                    }

                    // 确保以 ASSISTANT 结尾（避免与随后的当前 prompt 产生连续两条 USER）
                    while (historyMessages.isNotEmpty() && historyMessages.last().role == MessageRole.USER) {
                        historyMessages.removeAt(historyMessages.lastIndex)
                    }

                    addAll(historyMessages)
                }
            }

            add(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(prompt + imageStatus)) + imageResult.images
                )
            )
        }

        val textGenParams = TextGenerationParams(
            model = model,
            tools = emptyList(),
            temperature = assistant.temperature ?: 0.7f,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = assistant.customHeaders,
            customBody = assistant.customBodies
        )

        return try {
            val response = withTimeoutOrNull(timeoutSeconds * 1000L) {
                providerImpl.generateText(providerSetting, messages, textGenParams)
            } ?: return buildBridgeResultJson(
                success = false,
                error = "AI 生成超时 (${timeoutSeconds}s)"
            )

            val responseText = response.choices.firstOrNull()?.message?.parts
                ?.filterIsInstance<UIMessagePart.Text>()
                ?.joinToString("\n") { it.text }
                ?.trim()
                .orEmpty()

            if (responseText.isEmpty()) {
                return buildBridgeResultJson(
                    success = false,
                    error = "未生成有效回复",
                    conversationId = conversationIdParam
                )
            }

            buildBridgeResultJson(
                success = true,
                text = responseText,
                conversationId = conversationIdParam
            )
        } catch (e: Exception) {
            Log.e(TAG, "AI generation failed", e)
            buildBridgeResultJson(
                success = false,
                error = e.message ?: "AI generation failed"
            )
        }
    }

    private suspend fun handleHookTrigger(webView: WebView, hookName: String, contextJson: String): String {
        val hookConfig = pluginInfo.manifest.hookConfigs[hookName]
            ?: return buildBridgeResultJson(success = false, error = "Hook '$hookName' not configured in manifest")

        when (hookConfig.action) {
            "call_js_function" -> {
                val functionName = hookConfig.function
                    ?: return buildBridgeResultJson(success = false, error = "function is required for call_js_function action")

                if (!hookConfig.autoTrigger) {
                    return buildBridgeResultJson(success = false, error = "Hook '$hookName' is not auto-triggered")
                }

                val jsCode = "try { if(typeof $functionName === 'function') { $functionName(${escapeJsString(contextJson)}); } } catch(e) { console.error('Hook JS error:', e); }"
                webView.post {
                    webView.evaluateJavascript(jsCode, null)
                }
                return buildBridgeResultJson(
                    success = true,
                    extra = mapOf("action" to "call_js_function", "function" to functionName)
                )
            }

            "call_ai" -> {
                if (!pluginInfo.manifest.permissions.contains("ai_chat")) {
                    return buildBridgeResultJson(
                        success = false,
                        error = "Permission denied: ai_chat permission not declared in manifest"
                    )
                }

                val template = hookConfig.promptTemplate
                    ?: return buildBridgeResultJson(success = false, error = "promptTemplate is required for call_ai action")

                val contextObj = try {
                    JSONObject(contextJson)
                } catch (e: Exception) {
                    return buildBridgeResultJson(success = false, error = "Invalid context JSON")
                }

                var prompt = template
                val it = contextObj.keys()
                while (it.hasNext()) {
                    val key = it.next()
                    prompt = prompt.replace("{$key}", contextObj.optString(key))
                }

                val aiResult = callAI(mapOf("prompt" to prompt, "context" to contextJson))
                return aiResult
            }

            else -> {
                return buildBridgeResultJson(success = false, error = "Unknown hook action: ${hookConfig.action}")
            }
        }
    }

    private fun escapeJsString(str: String): String {
        return JSONObject.quote(str)
    }

    private suspend fun callPluginTool(toolName: String, params: String): String {
        return try {
            val loadedPlugin: LoadedPlugin? = pluginLoader.getAllLoadedPlugins().find { plugin: LoadedPlugin ->
                plugin.info.manifest.tools.any { toolDef -> toolDef.name == toolName }
            }

            if (loadedPlugin == null) {
                return buildBridgeResultJson(success = false, error = "Tool not found: $toolName")
            }

            val jsonParams = Json.parseToJsonElement(params)

            val result = pluginLoader.callTool(
                pluginId = loadedPlugin.id,
                toolName = toolName,
                params = jsonParams
            )

            return result.fold(
                onSuccess = { jsonElement: kotlinx.serialization.json.JsonElement ->
                    Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), jsonElement)
                },
                onFailure = { error: Throwable ->
                    buildBridgeResultJson(success = false, error = error.message ?: "tool call failed")
                }
            )
        } catch (e: Exception) {
            buildBridgeResultJson(success = false, error = e.message ?: "tool call failed")
        }
    }
}

private const val bridgeJavascript = """
(function() {
    if (window.Bridge) return;

    window.__bridgeCallbacks = {};
    window.__bridgeResultId = 0;

    window.__bridgeResult = function(callbackId, result) {
        if (callbackId && window.__bridgeCallbacks[callbackId]) {
            try {
                window.__bridgeCallbacks[callbackId](result);
            } catch(e) {
                console.error('Bridge callback error:', e);
            }
            delete window.__bridgeCallbacks[callbackId];
        }
    };

    function bridgeCall(method, params) {
        return new Promise(function(resolve, reject) {
            var callbackId = 'cb_' + (++window.__bridgeResultId);
            window.__bridgeCallbacks[callbackId] = resolve;

            var url = 'bridge://' + method + '?callbackId=' + encodeURIComponent(callbackId);
            for (var key in params) {
                if (params.hasOwnProperty(key)) {
                    url += '&' + encodeURIComponent(key) + '=' + encodeURIComponent(String(params[key]));
                }
            }

            var iframe = document.createElement('iframe');
            iframe.style.display = 'none';
            iframe.src = url;
            document.body.appendChild(iframe);
            setTimeout(function() {
                document.body.removeChild(iframe);
            }, 100);
        });
    }

    window.Bridge = {
        getPluginConfig: function() {
            return bridgeCall('getPluginConfig', {});
        },
        getData: function(key) {
            return bridgeCall('getData', {key: key});
        },
        setData: function(key, value) {
            return bridgeCall('setData', {key: key, value: value});
        },
        deleteData: function(key) {
            return bridgeCall('deleteData', {key: key});
        },
        listData: function() {
            return bridgeCall('listData', {});
        },
        pickImage: function() {
            return bridgeCall('pickImage', {});
        },
        pickFile: function() {
            return bridgeCall('pickFile', {});
        },
        pickBinaryFile: function() {
            return bridgeCall('pickBinaryFile', {});
        },
        importAudioFile: function() {
            return bridgeCall('importAudioFile', {});
        },
        callTool: function(toolName, params) {
            return bridgeCall('callTool', {toolName: toolName, params: params || '{}'});
        },
        writeFile: function(fileName, base64Data) {
            return bridgeCall('writeFile', {fileName: fileName, data: base64Data});
        },
        saveFileAs: function(fileName, base64Data) {
            return bridgeCall('saveFileAs', {fileName: fileName, data: base64Data});
        },
        readFile: function(fileName) {
            return bridgeCall('readFile', {fileName: fileName});
        },
        listFiles: function(dirPath) {
            return bridgeCall('listFiles', {dir: dirPath || ''});
        },
        deleteFile: function(fileName) {
            return bridgeCall('deleteFile', {fileName: fileName});
        },
        close: function() {
            bridgeCall('close', {});
        },
        callAI: function(promptOrOptions, options) {
            var raw = {};
            if (typeof promptOrOptions === 'object' && promptOrOptions !== null) {
                raw = promptOrOptions;
            } else {
                raw.prompt = promptOrOptions;
                if (typeof options === 'object' && options !== null) {
                    for (var k in options) {
                        if (options.hasOwnProperty(k)) raw[k] = options[k];
                    }
                } else if (typeof options === 'string') {
                    raw.context = options;
                }
            }

            var reserved = [
                'prompt', 'conversationId', 'syncToChat', 'loadHistory',
                'historyLimit', 'systemPrompt', 'assistantId', 'timeout', 'imageUrls'
            ];
            var params = {};
            var contextObj = {};

            if (typeof raw.context === 'string') {
                try {
                    contextObj = JSON.parse(raw.context);
                } catch(e) {
                    contextObj = { text: raw.context };
                }
            } else if (typeof raw.context === 'object' && raw.context !== null) {
                for (var ck in raw.context) {
                    if (raw.context.hasOwnProperty(ck)) contextObj[ck] = raw.context[ck];
                }
            }

            for (var key in raw) {
                if (!raw.hasOwnProperty(key) || key === 'context') continue;
                if (reserved.indexOf(key) !== -1) {
                    params[key] = raw[key];
                } else {
                    contextObj[key] = raw[key];
                }
            }

            params.prompt = raw.prompt || '';
            if (Array.isArray(raw.imageUrls)) params.imageUrls = JSON.stringify(raw.imageUrls);
            params.context = JSON.stringify(contextObj);
            return bridgeCall('callAI', params);
        },
        getCurrentConversationId: function() {
            return bridgeCall('getCurrentConversationId', {});
        },
        recordChatEvent: function(action, taskName) {
            return bridgeCall('recordChatEvent', {action: action, taskName: taskName});
        },
        notifyHook: function(hookName, context) {
            return bridgeCall('notifyHook', {hookName: hookName, context: context || '{}'});
        },
        startTimer: function(seconds) {
            return bridgeCall('startTimer', {seconds: seconds});
        },
        stopTimer: function() {
            return bridgeCall('stopTimer', {});
        },
        getTimerState: function() {
            return bridgeCall('getTimerState', {});
        },
        showOverlay: function(html) {
            return bridgeCall('showOverlay', {html: html});
        },
        hideOverlay: function() {
            return bridgeCall('hideOverlay', {});
        },
        musicPlay: function(filePath, title, artist) {
            return bridgeCall('musicPlay', {filePath: filePath, title: title || '', artist: artist || ''});
        },
        musicPause: function() {
            return bridgeCall('musicPause', {});
        },
        musicResume: function() {
            return bridgeCall('musicResume', {});
        },
        musicStop: function() {
            return bridgeCall('musicStop', {});
        },
        musicSeek: function(position) {
            return bridgeCall('musicSeek', {position: position});
        },
        musicGetProgress: function() {
            return bridgeCall('musicGetProgress', {});
        },
        getActivityCalories: function(date, customPath) {
            return bridgeCall('getActivityCalories', {date: date || '', customPath: customPath || ''});
        }
    };

    window.onTimerEnd = window.onTimerEnd || function() {};
    window.onMusicCompleted = window.onMusicCompleted || function() {};

    console.log('Bridge API initialized');
})();
"""

private const val overlayBridgeJavascript = """
(function() {
    if (window.__overlayBridgeReady) return;
    window.__overlayBridgeReady = true;

    window.__bridgeCallbacks = {};
    window.__bridgeResultId = 0;

    window.__bridgeResult = function(callbackId, result) {
        if (callbackId && window.__bridgeCallbacks[callbackId]) {
            try {
                window.__bridgeCallbacks[callbackId](result);
            } catch(e) {
                console.error('Bridge callback error:', e);
            }
            delete window.__bridgeCallbacks[callbackId];
        }
    };

    function bridgeCall(method, params) {
        return new Promise(function(resolve, reject) {
            var callbackId = 'cb_' + (++window.__bridgeResultId);
            window.__bridgeCallbacks[callbackId] = resolve;
            var url = 'bridge://' + method + '?callbackId=' + encodeURIComponent(callbackId);
            for (var key in params) {
                if (params.hasOwnProperty(key)) {
                    url += '&' + encodeURIComponent(key) + '=' + encodeURIComponent(String(params[key]));
                }
            }
            var iframe = document.createElement('iframe');
            iframe.style.display = 'none';
            iframe.src = url;
            document.body.appendChild(iframe);
            setTimeout(function() { document.body.removeChild(iframe); }, 100);
        });
    }

    window.Bridge = {
        hideOverlay: function() {
            return bridgeCall('hideOverlay', {});
        },
        getTimerState: function() {
            return bridgeCall('getTimerState', {});
        },
        getData: function(key) {
            return bridgeCall('getData', {key: key});
        },
        setData: function(key, value) {
            return bridgeCall('setData', {key: key, value: value});
        },
        callAI: function(prompt, context) {
            return bridgeCall('callAI', {prompt: prompt, context: context || '{}'});
        }
    };

    window.onTimerEnd = function() {};

    console.log('Overlay Bridge API initialized');
})();
"""

private fun uriToBase64(context: android.content.Context, uri: Uri): String {
    val inputStream = context.contentResolver.openInputStream(uri) ?: return "null"
    val bytes = inputStream.readBytes()
    inputStream.close()
    return "\"" + Base64.encodeToString(bytes, Base64.NO_WRAP) + "\""
}
