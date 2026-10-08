package me.rerere.rikkahub.plugin.provider

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.ui.pages.share.handler.SharedPostImageDownloader
import java.io.File
import java.net.URI
import java.net.URL

internal object MomentsImageAttachments {
    const val PLUGIN_ID = "com.yanyan.diary"
    const val MAX_IMAGES = 9
    private const val LOCAL_PREFIX = "local:"

    fun localFileName(reference: String): String? = reference
        .takeIf { it.startsWith(LOCAL_PREFIX) }
        ?.removePrefix(LOCAL_PREFIX)
        ?.takeIf { it.startsWith("moments/") && it.split('/').none { part -> part.isBlank() || part == "." || part == ".." || '\\' in part } }

    fun isAllowedImageUrl(url: String, allowedHosts: List<String>): Boolean = runCatching {
        val parsed = URL(url)
        val path = URI(url).path
        parsed.protocol == "https" &&
            parsed.host.lowercase() in allowedHosts.map(String::lowercase) &&
            parsed.userInfo == null &&
            parsed.port in listOf(-1, 443) &&
            path.startsWith("/storage/v1/object/public/diary-images/") &&
            path.split('/').none { it == "." || it == ".." || '\\' in it }
    }.getOrDefault(false)

    fun candidateUrls(urls: List<String>, allowedHosts: List<String>): List<String> =
        urls.take(MAX_IMAGES).distinct().filter { isAllowedImageUrl(it, allowedHosts) }

    fun statusText(totalCount: Int, result: DownloadResult, supportsImages: Boolean): String = when {
        totalCount <= 0 -> ""
        !supportsImages -> "[朋友圈图片未读取：当前选中模型未配置图片输入能力。]"
        else -> buildString {
            append("[朋友圈图片视觉输入：已附带 ${result.images.size}/$totalCount 张")
            if (result.unreadCount > 0) append("；${result.unreadCount} 张未读取")
            append("。]")
        }
    }

    fun sanitizeResult(element: JsonElement): JsonElement {
        val rootObj = element as? JsonObject ?: return element
        val dataObj = rootObj["data"] as? JsonObject ?: return element
        if (!dataObj.containsKey("visual_input")) return element
        val cleanedData = JsonObject(dataObj.filterKeys { it != "visual_input" })
        return JsonObject(rootObj + ("data" to cleanedData))
    }

    suspend fun download(
        urls: List<String>,
        allowedHosts: List<String>,
        downloader: SharedPostImageDownloader,
        filesManager: FilesManager,
        pluginDataStore: PluginDataStore? = null,
    ): DownloadResult {
        val candidates = urls.take(MAX_IMAGES).distinct()
        val parts = mutableListOf<UIMessagePart.Image>()
        val remoteUrls = candidates.filter { !it.startsWith(LOCAL_PREFIX) && isAllowedImageUrl(it, allowedHosts) }
        val localRefs = candidates.filter { it.startsWith(LOCAL_PREFIX) }

        if (pluginDataStore != null) {
            localRefs.forEach { reference ->
                val name = localFileName(reference) ?: return@forEach
                val file = safeLocalFile(pluginDataStore.getDataDir(), name) ?: return@forEach
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
                if (bytes.size > SharedPostImageDownloader.MAX_BYTES) return@forEach
                val mime = imageMime(bytes) ?: return@forEach
                filesManager.createChatImageFilesByByteArrays(listOf(bytes to mime))
                    .firstOrNull()?.let { parts.add(UIMessagePart.Image(it.toString())) }
            }
        }

        if (remoteUrls.isNotEmpty()) {
            val firstUrl = URL(remoteUrls.first())
            val downloaded = downloader.download(
                urls = remoteUrls,
                pageUrl = "https://${firstUrl.host}/",
                userAgent = "OrangeChat/朋友圈图片读取",
                cookieForUrl = { null },
                isAllowedImageUrl = { isAllowedImageUrl(it, allowedHosts) },
                blockPrivateAddresses = true,
            )
            downloaded.forEach { image ->
                filesManager.createChatImageFilesByByteArrays(listOf(image.bytes to image.mimeType))
                    .firstOrNull()?.let { parts.add(UIMessagePart.Image(it.toString())) }
            }
        }
        return DownloadResult(parts, urls.size - parts.size)
    }

    private fun safeLocalFile(baseDir: File, relativePath: String): File? = runCatching {
        val base = baseDir.canonicalFile
        File(base, relativePath).canonicalFile.takeIf { it.path.startsWith(base.path + File.separator) && it.isFile }
    }.getOrNull()

    private fun imageMime(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        ) -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
        bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP" -> "image/webp"
        bytes.size >= 12 && String(bytes, 4, 4) == "ftyp" && String(bytes, 8, 4).startsWith("avif") -> "image/avif"
        else -> null
    }

    data class DownloadResult(val images: List<UIMessagePart.Image>, val unreadCount: Int)
}
