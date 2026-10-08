/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.share.handler

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView as AndroidWebView
import android.webkit.CookieManager
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.Screen
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalNavController
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

object SharedPostDraftClearRegistry {
    private val conversationIds = ConcurrentHashMap.newKeySet<String>()
    fun mark(conversationId: String) { conversationIds += conversationId }
    fun consume(conversationId: String): Boolean = conversationIds.remove(conversationId)
}

private const val EXTRACT_POST_SCRIPT = """
(() => {
  const normalize = value => String(value || '').replaceAll(String.fromCharCode(160), ' ').replaceAll('  ', ' ').trim();
  const xhs = /(^|\.)xiaohongshu\.com$/.test(location.hostname);
  const taobao = /(^|\.)(taobao\.com|tmall\.com)$/.test(location.hostname);
  const selectors = xhs
    ? ['.narmal-note-container .content-container', '#detail-desc', '.note-content', '.note-text']
    : taobao ? ['#detail-entry [class*="first_card"]', '#detail-entry']
    : ['[data-testid="tweetText"]', '#detail-desc', '.note-content', '.note-text', '[class*="noteContent"]', 'article', 'main', '[role="main"]'];
  let textNode = null;
  for (const selector of selectors) {
    textNode = Array.from(document.querySelectorAll(selector)).find(node => normalize(node.innerText));
    if (textNode) break;
  }
  const body = normalize(document.body ? document.body.innerText : '');
  const loginGate = ['登录后查看', '登录后继续', '请先登录', '登录以继续', 'sign in to view', 'log in to view', 'sign in to continue', 'log in to continue'].some(text => body.slice(0, 4000).toLowerCase().includes(text)) || /\/(login|signin)(\/|$)/i.test(location.pathname);
  const lowerBody = body.slice(0, 1800).toLowerCase();
  const loadError = ['网页无法打开', 'webpage not available', 'net::err_', '无法访问此网站'].some(text => lowerBody.includes(text));
  const candidate = textNode ? normalize(textNode.innerText) : body;
  const comments = xhs ? Array.from(document.querySelectorAll('.comment-item')).map(node => normalize(node.innerText)).filter(Boolean) : [];
  const text = loginGate || loadError || !candidate ? ''
    : (textNode ? candidate : '[页面可见文字，可能包含导航或推荐内容]\n' + candidate) + (comments.length ? '\n\n[已加载的评论]\n' + Array.from(new Set(comments)).join('\n\n') : '');
  const root = xhs ? document.querySelector('.narmal-note-container') || textNode?.parentElement?.parentElement
    : taobao ? document.querySelector('#detail-entry')
    : textNode?.closest('article') || textNode || document.body;
  const images = !loginGate && !loadError && root ? Array.from(root.querySelectorAll('img'))
    .filter(img => {
      if (xhs) return !!img.closest('.note-image-box, .note-image-container') ||
        (img.naturalWidth >= 300 && img.naturalHeight >= 300);
      if (taobao) return /(^|\.)img\.alicdn\.com$/.test(new URL(img.src, location.href).hostname);
      return img.getClientRects().length && ((img.naturalWidth >= 120 && img.naturalHeight >= 120)
        || img.hasAttribute('data-src') || img.hasAttribute('data-original') || img.hasAttribute('data-lazy-src'));
    })
    .map(img => {
      const srcset = img.getAttribute('srcset') || img.getAttribute('data-srcset') || '';
      const bestSrcset = srcset.split(',').map(item => item.trim().split(/\s+/)[0]).filter(Boolean).pop();
      const source = img.currentSrc && /^https?:/i.test(img.currentSrc) ? img.currentSrc
        : img.getAttribute('data-original') || img.getAttribute('data-src')
          || img.getAttribute('data-lazy-src') || bestSrcset || img.src || '';
      try { return new URL(source, location.href).href; } catch (_) { return ''; }
    })
    .filter(src => /^https?:\/\//i.test(src)) : [];
  const noteId = xhs ? location.pathname.match(/^\/(?:explore|discovery\/item)\/([a-f0-9]{24})\/?$/i)?.[1] : null;
  const note = noteId ? window.__INITIAL_STATE__?.note?.noteDetailMap?.[noteId]?.note : null;
  const stateImages = !loginGate && !loadError && note && note.noteId === noteId ? (note.imageList || [])
    .map(image => image.urlDefault || image.infoList?.find(item => item.imageScene === 'WB_DFT')?.url
      || image.url || image.urlPre || image.infoList?.[0]?.url || '')
    .filter(src => /^https?:\/\//i.test(src)) : [];
  return JSON.stringify({ title: document.title || '', url: location.href, text,
    images: Array.from(new Set([...stateImages, ...images])).slice(0, 9), requiresLogin: loginGate, loadError });
})()
"""

internal const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedPostSharePage(
    vm: ShareHandlerVM,
    sharedImage: String?,
    targetConversationId: String? = null,
    targetAssistantId: String? = null,
    draftAttachments: List<UIMessagePart> = emptyList(),
) {
    val url = checkNotNull(vm.sharedUrl)
    val navigator = LocalNavController.current
    val webState = rememberWebViewState(
        url = url,
        settings = {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        },
    )
    var comment by rememberSaveable(url) { mutableStateOf(vm.initialComment) }
    var pageTitle by rememberSaveable(url) { mutableStateOf("") }
    var pageText by rememberSaveable(url) { mutableStateOf("") }
    var pageImageCount by rememberSaveable(url) { mutableIntStateOf(0) }
    var postImages by remember(url) { mutableStateOf<List<DownloadedPostImage>>(emptyList()) }
    val selectedPostImages = remember(url) { mutableStateListOf<Int>() }
    var requiresLogin by rememberSaveable(url) { mutableStateOf(false) }
    var status by rememberSaveable(url) { mutableStateOf("正在打开分享的帖子…") }
    var supplementalScreenshots by rememberSaveable(url) { mutableStateOf<List<String>>(emptyList()) }
    var expandedText by rememberSaveable(url) { mutableStateOf(false) }
    var expandedWeb by rememberSaveable(url) { mutableStateOf(false) }
    var desktopWebMode by rememberSaveable(url) { mutableStateOf(true) }
    var originalUserAgent by remember(url) { mutableStateOf<String?>(null) }
    var isReading by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var openingTogether by remember { mutableStateOf(false) }
    var readGeneration by remember(url) { mutableIntStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val screenshotPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        supplementalScreenshots = uris.map(Uri::toString)
    }

    fun readCurrentPage() {
        val activeWebView = webState.webView ?: run {
            status = "网页还没有准备好，请稍后重试。"
            return
        }
        isReading = true
        val readId = ++readGeneration
        status = "正在读取当前页面…"
        pageText = ""
        postImages = emptyList()
        selectedPostImages.clear()
        pageImageCount = 0
        val readingUrl = activeWebView.url
        activeWebView.evaluateJavascript(EXTRACT_POST_SCRIPT) { result ->
            if (readGeneration != readId) return@evaluateJavascript
            if (webState.webView !== activeWebView || webState.isLoading || activeWebView.url != readingUrl) {
                isReading = false
                return@evaluateJavascript
            }
            val snapshot = runCatching {
                val jsonText = JSONArray("[$result]").getString(0)
                Json.parseToJsonElement(jsonText).jsonObject
            }.getOrNull()
            if (snapshot == null) {
                status = "读取失败。你可以在上方登录或等待页面加载后重试。"
                isReading = false
                return@evaluateJavascript
            }

            pageTitle = snapshot["title"]?.jsonPrimitive?.content.orEmpty()
            requiresLogin = snapshot["requiresLogin"]?.jsonPrimitive?.content == "true"
            val loadError = snapshot["loadError"]?.jsonPrimitive?.content == "true" || webState.loadError != null
            pageText = if (requiresLogin || loadError) "" else snapshot["text"]?.jsonPrimitive?.content.orEmpty()
            val imageUrls = if (requiresLogin || loadError) emptyList() else
                SharedPostLinkRules.selectImageUrls(
                    (snapshot["images"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                )
            pageImageCount = imageUrls.size
            status = if (loadError) {
                "网页加载失败：${webState.loadError ?: "页面显示无法打开"}"
            } else if (requiresLogin) {
                "页面提示需要登录。可点“放大网页”并双指缩放寻找登录入口；登录后返回原帖重新读取。登录页内容不会发送给 AI。"
            } else if (pageText.isNotBlank() || imageUrls.isNotEmpty()) {
                "正在读取正文与 $pageImageCount 张帖子图片…"
            } else {
                "暂未读到页面文字或图片。请等待加载后重试，也可以只带链接发送。"
            }
            isReading = false
            if (!loadError && !requiresLogin && imageUrls.isNotEmpty()) {
                isReading = true
                val pageUrl = activeWebView.url ?: url
                val userAgent = activeWebView.settings.userAgentString.orEmpty()
                scope.launch {
                    val downloaded = try {
                        vm.downloadPostImages(imageUrls, pageUrl, userAgent) { imageUrl ->
                            CookieManager.getInstance().getCookie(imageUrl)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (readGeneration == readId && webState.webView === activeWebView && activeWebView.url == readingUrl) {
                        isReading = false
                        postImages = downloaded
                        selectedPostImages.clear()
                        selectedPostImages.addAll(downloaded.indices)
                        status = buildString {
                            append("已读取约 ${pageText.length} 字，原图 ${downloaded.size}/$pageImageCount 张。")
                            if (downloaded.size < pageImageCount) append("部分图片无法读取，可补充截图或相册图片。")
                            else append("请检查内容并补充留言。")
                        }
                    } else if (readGeneration == readId) {
                        isReading = false
                    }
                }
            } else if (!requiresLogin && !loadError && pageText.isNotBlank()) {
                status = "已读取约 ${pageText.length} 字。请检查内容并补充留言。"
            }
        }
    }

    LaunchedEffect(webState.isLoading, webState.currentUrl) {
        readGeneration++
        isReading = false
        pageTitle = ""
        pageText = ""
        pageImageCount = 0
        postImages = emptyList()
        selectedPostImages.clear()
        requiresLogin = false
        if (webState.currentUrl != null && !webState.isLoading) {
            delay(1600)
            readCurrentPage()
            repeat(3) {
                delay(2500)
                val waitingForXhsImages = webState.currentUrl?.contains("xiaohongshu.com", ignoreCase = true) == true &&
                    pageImageCount == 0
                if (!isSending && !isReading && postImages.isEmpty() && !requiresLogin &&
                    (pageText.isBlank() || waitingForXhsImages)) {
                    readCurrentPage()
                }
            }
        }
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        if (webState.canGoBack) webState.goBack() else navigator.popBackStack()
                    },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) { Text("返回") }
                Text("分享给 AI", style = MaterialTheme.typography.titleMedium)
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (openingTogether) return@TextButton
                        openingTogether = true
                        scope.launch {
                            runCatching {
                                val conversationId = vm.resolveTogetherConversationId(
                                    targetConversationId = targetConversationId,
                                    targetAssistantId = targetAssistantId,
                                )
                                val currentUrl = webState.webView?.url ?: webState.currentUrl ?: url
                                val togetherUrl = TogetherBrowseRules.normalizeUrl(currentUrl)
                                    ?: error("当前网页链接无效。")
                                navigator.navigate(
                                    Screen.TogetherBrowse(
                                        conversationId = conversationId.toString(),
                                        initialUrl = togetherUrl,
                                        initialText = comment,
                                        initialImageUri = sharedImage,
                                        initialAttachments = draftAttachments,
                                    )
                                )
                            }.onFailure { error ->
                                status = error.message ?: "无法进入一起刷。"
                            }
                            openingTogether = false
                        }
                    },
                    enabled = !isSending && !openingTogether,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) { Text(if (openingTogether) "打开中" else "一起刷", maxLines = 1) }
                TextButton(
                    modifier = Modifier.weight(1f),
                    onClick = { expandedWeb = !expandedWeb },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) {
                    Text(if (expandedWeb) "收起" else "全屏", maxLines = 1)
                }
                TextButton(
                    modifier = Modifier.weight(1f),
                    onClick = { webState.loadUrl(url) },
                    enabled = !isSending,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) { Text("原帖", maxLines = 1) }
                TextButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val activeWebView = webState.webView
                        if (activeWebView != null) {
                            activeWebView.settings.userAgentString = if (desktopWebMode) {
                                originalUserAgent ?: activeWebView.settings.userAgentString
                            } else {
                                DESKTOP_USER_AGENT
                            }
                            desktopWebMode = !desktopWebMode
                            activeWebView.reload()
                        }
                    },
                    enabled = !isSending && !webState.isLoading,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) { Text(if (desktopWebMode) "手机版" else "桌面版", maxLines = 1) }
            }
            Box(Modifier.weight(if (expandedWeb) 1f else 0.48f).fillMaxWidth().clipToBounds()) {
                WebView(
                    state = webState,
                    modifier = Modifier.fillMaxSize(),
                    onCreated = {
                        originalUserAgent = it.settings.userAgentString
                        if (desktopWebMode) it.settings.userAgentString = DESKTOP_USER_AGENT
                    },
                    onUpdated = { if (desktopWebMode) it.settings.userAgentString = DESKTOP_USER_AGENT },
                    onNavigation = { rawUrl ->
                        val fallback = SharedPostLinkRules.intentFallback(rawUrl)
                            ?: SharedPostLinkRules.xiaohongshuNoteUrl(rawUrl)
                        if (fallback != null) webState.loadUrl(fallback)
                        fallback != null || !SharedPostLinkRules.isWebUrl(rawUrl)
                    },
                    onMainFrameError = { description ->
                        readGeneration++
                        isReading = false
                        pageTitle = ""
                        pageText = ""
                        pageImageCount = 0
                        postImages = emptyList()
                        selectedPostImages.clear()
                        requiresLogin = false
                        status = "网页加载失败：$description"
                    },
                )
            }
            if (!expandedWeb) Column(
                modifier = Modifier
                    .weight(0.52f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (pageText.isNotBlank()) {
                    Text(
                        text = pageText,
                        maxLines = if (expandedText) Int.MAX_VALUE else 8,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pageText.isNotBlank()) {
                    TextButton(onClick = { expandedText = !expandedText }) {
                        Text(if (expandedText) "收起内容" else "展开全部内容（${pageText.length} 字）")
                    }
                }
                if (postImages.isNotEmpty()) {
                    Text("帖子图片（默认选中，可取消）", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        postImages.forEachIndexed { index, image ->
                            val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, image) {
                                value = withContext(Dispatchers.IO) { decodePostImageThumbnail(image.bytes) }
                            }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                bitmap?.let { decodedBitmap ->
                                    androidx.compose.foundation.Image(
                                        bitmap = decodedBitmap,
                                        contentDescription = "帖子图片 ${index + 1}",
                                        modifier = Modifier.size(72.dp),
                                    )
                                }
                                Checkbox(
                                    checked = index in selectedPostImages,
                                    onCheckedChange = { checked ->
                                        if (checked) selectedPostImages.add(index) else selectedPostImages.remove(index)
                                    },
                                )
                            }
                        }
                    }
                }
                Text(url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("补充一句（可选）") },
                    minLines = 1,
                    maxLines = 3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            screenshotPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        enabled = !isSending,
                    ) {
                        Text(if (supplementalScreenshots.isEmpty()) "补充图片" else "已选 ${supplementalScreenshots.size} 张，可重选")
                    }
                    if (sharedImage != null) {
                        Text("已附带分享图片", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { readCurrentPage() }, enabled = !isReading && !isSending) {
                        if (isReading) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                        }
                        Text("重新读取")
                    }
                    Button(
                        onClick = {
                            if (isSending) return@Button
                            isSending = true
                            status = "正在发送到当前 AI 的聊天…"
                            scope.launch {
                                val screenshot = if (pageImageCount > 0 && postImages.isEmpty() && webState.loadError == null) {
                                    webState.webView?.let(::captureVisibleWebView)
                                } else null
                                runCatching {
                                    vm.sendSharedPost(
                                        comment = comment,
                                        pageTitle = pageTitle,
                                        pageText = pageText,
                                        pageImageCount = pageImageCount,
                                        pageScreenshot = screenshot,
                                        sharedImageUris = listOfNotNull(sharedImage) + supplementalScreenshots,
                                        postImages = selectedPostImages.mapNotNull(postImages::getOrNull),
                                        draftAttachments = draftAttachments,
                                        targetConversationId = targetConversationId,
                                        targetAssistantId = targetAssistantId,
                                    )
                                }.onSuccess { conversationId ->
                                    if (targetConversationId != null) {
                                        SharedPostDraftClearRegistry.mark(conversationId.toString())
                                        navigator.popBackStack()
                                    } else {
                                        navigator.clearAndNavigate(Screen.Chat(id = conversationId.toString()))
                                    }
                                }.onFailure { error ->
                                    status = "发送失败：${error.message ?: "请重试"}"
                                    isSending = false
                                }
                            }
                        },
                        enabled = !isSending && !isReading && !webState.isLoading,
                    ) {
                        Text(if (isSending) "发送中…" else if (pageText.isBlank()) "发送链接和附件" else "发送给 AI")
                    }
                }
            }
        }
    }
}

private fun captureVisibleWebView(webView: AndroidWebView): ByteArray? = runCatching {
    if (webView.width <= 0 || webView.height <= 0) return null
    val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
    webView.draw(Canvas(bitmap))
    ByteArrayOutputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        output.toByteArray()
    }
}.getOrNull()

private fun decodePostImageThumbnail(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 512) {
        sampleSize *= 2
    }
    BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )?.asImageBitmap()
}.getOrNull()
