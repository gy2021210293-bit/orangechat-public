package me.rerere.rikkahub.ui.pages.share.handler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.ConnectionPool
import okhttp3.Request
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.TimeUnit

data class DownloadedPostImage(val bytes: ByteArray, val mimeType: String, val sourceUrl: String)

class SharedPostImageDownloader(private val client: OkHttpClient) {
    suspend fun download(
        urls: List<String>,
        pageUrl: String,
        userAgent: String,
        cookieForUrl: (String) -> String?,
        isAllowedImageUrl: (String) -> Boolean = { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) },
        blockPrivateAddresses: Boolean = false,
    ): List<DownloadedPostImage> = withContext(Dispatchers.IO) {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_TOTAL_DOWNLOAD_MILLIS)
        val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
        coroutineScope {
            urls.distinct().take(MAX_IMAGES).map { initialUrl ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            downloadOne(initialUrl, pageUrl, userAgent, cookieForUrl, deadlineNanos, isAllowedImageUrl, blockPrivateAddresses)
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun downloadOne(
        initialUrl: String,
        pageUrl: String,
        userAgent: String,
        cookieForUrl: (String) -> String?,
        deadlineNanos: Long,
        isAllowedImageUrl: (String) -> Boolean,
        blockPrivateAddresses: Boolean,
    ): DownloadedPostImage? {
        var url = initialUrl
        repeat(6) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) return null
            if (!isAllowedImageUrl(url) || URL(url).protocol !in setOf("http", "https")) return null
            val safeClient = client.newBuilder()
                .apply {
                    if (blockPrivateAddresses) {
                        proxy(Proxy.NO_PROXY)
                        connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                        dns(publicImageDns(client.dns))
                    }
                }
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(remainingNanos, TimeUnit.NANOSECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", pageUrl)
            cookieForUrl(url)?.takeIf(String::isNotBlank)?.let { builder.header("Cookie", it) }
            safeClient.newCall(builder.build()).execute().use { response ->
                if (response.code in 300..399) {
                    val location = response.header("Location") ?: return null
                    val redirectedUrl = response.request.url.resolve(location)?.toString() ?: return null
                    if (!isAllowedImageUrl(redirectedUrl)) return null
                    url = redirectedUrl
                    return@repeat
                }
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                if (body.contentLength() > MAX_BYTES) return null
                val bytes = body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) return null
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                val mime = imageMime(bytes) ?: return null
                return DownloadedPostImage(bytes, mime, url)
            }
        }
        return null
    }

    private fun imageMime(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        ) -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
        bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP" -> "image/webp"
        bytes.size >= 12 && String(bytes, 4, 4) == "ftyp" && String(bytes, 8, 4).startsWith("avif") -> "image/avif"
        else -> null
    }

    companion object {
        const val MAX_IMAGES = 9
        const val MAX_BYTES = 10 * 1024 * 1024
        const val MAX_CONCURRENT_DOWNLOADS = 3
        const val MAX_TOTAL_DOWNLOAD_MILLIS = 30_000L
    }
}

internal fun publicImageDns(delegate: Dns): Dns = Dns { hostname ->
    delegate.lookup(hostname).also { addresses ->
        require(addresses.isNotEmpty() && addresses.all(::isPublicImageAddress)) {
            "图片地址解析到非公网地址"
        }
    }
}

internal fun isPublicImageAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 0xff }
    if (bytes.size == 4) {
        val (a, b, c) = bytes
        return !(a == 0 || a == 100 && b in 64..127 || a == 192 && b == 0 && c == 0 ||
            a == 192 && b == 0 && c == 2 || a == 198 && b in 18..19 ||
            a == 198 && b == 51 && c == 100 || a == 203 && b == 0 && c == 113 || a >= 224)
    }
    if (bytes.size == 16) {
        if (bytes[0] and 0xfe == 0xfc) return false
        if (bytes.take(10).all { it == 0 } && bytes[10] == 0xff && bytes[11] == 0xff) {
            return isPublicImageAddress(InetAddress.getByAddress(bytes.drop(12).map(Int::toByte).toByteArray()))
        }
        return true
    }
    return false
}
