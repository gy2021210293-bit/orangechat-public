package me.rerere.rikkahub.ui.pages.share.handler

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object SharedPostLinkRules {
    private val urlPattern = Regex("https?://[^\\s<>，。！？；：、）】》]+", RegexOption.IGNORE_CASE)

    fun firstUrl(text: String): String? = urlPattern.find(text)?.value?.trimEnd('.', ',', ')', ']', '，', '。')

    fun commentWithoutFirstUrl(text: String): String {
        val match = urlPattern.find(text) ?: return text.trim()
        val url = match.value.trimEnd('.', ',', ')', ']', '，', '。')
        return text.removeRange(match.range.first, match.range.first + url.length)
            .replace(Regex("(?:[a-z][a-z0-9+.-]*):\\/\\/[^\\s<>]+", RegexOption.IGNORE_CASE)) { appLink ->
                if (appLink.value.startsWith("http://", true) || appLink.value.startsWith("https://", true)) {
                    appLink.value
                } else {
                    ""
                }
            }
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun intentFallback(url: String): String? {
        if (!url.startsWith("intent://", ignoreCase = true)) return null
        val encoded = Regex("(?:^|;)S\\.browser_fallback_url=([^;]+)")
            .find(url.substringAfter('#', ""))?.groupValues?.get(1) ?: return null
        val fallback = runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()) }.getOrNull() ?: return null
        val scheme = runCatching { URI(fallback).scheme?.lowercase() }.getOrNull()
        return fallback.takeIf { scheme == "http" || scheme == "https" }
    }

    fun xiaohongshuNoteUrl(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("xhsdiscover", ignoreCase = true) || !uri.host.equals("item", ignoreCase = true)) return null
        val noteId = uri.path?.removePrefix("/") ?: return null
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return noteId.takeIf { it.matches(Regex("[a-fA-F0-9]{24}")) }
            ?.let { "https://www.xiaohongshu.com/explore/$it$query" }
    }

    fun isWebUrl(url: String): Boolean =
        runCatching { URI(url).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false)

    fun selectImageUrls(urls: List<String>): List<String> = urls
        .filter { isWebUrl(it) }
        .distinct()
        .take(9)
}
