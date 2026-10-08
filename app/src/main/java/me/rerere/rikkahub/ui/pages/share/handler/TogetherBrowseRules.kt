package me.rerere.rikkahub.ui.pages.share.handler

import java.net.URI

enum class TogetherBrowsePlatform(val label: String, val homeUrl: String) {
    X("X", "https://x.com/home"),
    XIAOHONGSHU("小红书", "https://www.xiaohongshu.com/explore"),
}

data class TogetherBrowseCandidate(val title: String, val url: String)

data class TogetherBrowseSnapshot(
    val url: String,
    val title: String,
    val text: String,
    val comments: List<String>,
    val imageUrls: List<String>,
    val candidates: List<TogetherBrowseCandidate>,
    val requiresLogin: Boolean = false,
)

object TogetherBrowseRules {
    fun isFreshSnapshot(snapshot: TogetherBrowseSnapshot, liveUrl: String): Boolean =
        normalizeUrl(snapshot.url) != null && normalizeUrl(snapshot.url) == normalizeUrl(liveUrl)

    fun normalizeUrl(input: String): String? {
        val value = input.trim().let { if (it.contains("://")) it else "https://$it" }
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        return value.takeIf { !uri.host.isNullOrBlank() }
    }

    fun platformFor(url: String): TogetherBrowsePlatform? {
        val host = runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: return null
        return when {
            host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" ||
                host.endsWith(".twitter.com") || host == "t.co" -> TogetherBrowsePlatform.X
            host == "xiaohongshu.com" || host.endsWith(".xiaohongshu.com") ||
                host == "xhslink.com" || host.endsWith(".xhslink.com") -> TogetherBrowsePlatform.XIAOHONGSHU
            else -> null
        }
    }

    fun isTrustedImageUrl(platform: TogetherBrowsePlatform, url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        if (!url.startsWith("https://", ignoreCase = true) &&
            !(platform == TogetherBrowsePlatform.XIAOHONGSHU && url.startsWith("http://", ignoreCase = true))) return false
        return when (platform) {
            TogetherBrowsePlatform.X -> host == "x.com" || host.endsWith(".x.com") ||
                host == "twitter.com" || host.endsWith(".twitter.com") || host == "twimg.com" || host.endsWith(".twimg.com")
            TogetherBrowsePlatform.XIAOHONGSHU -> host == "xiaohongshu.com" || host.endsWith(".xiaohongshu.com") ||
                host == "xhscdn.com" || host.endsWith(".xhscdn.com")
        }
    }

    fun isWebImageUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.equals("https", ignoreCase = true) != true) return false
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (host.all { it.isDigit() || it == '.' } || ':' in host) return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        val octets = host.split('.').mapNotNull(String::toIntOrNull)
        if (host.split('.').size == 4 && octets.size == 4 && octets.all { it in 0..255 }) {
            val (first, second) = octets
            if (first == 10 || first == 127 || first == 169 && second == 254 ||
                first == 192 && second == 168 || first == 172 && second in 16..31 || first == 0
            ) return false
        }
        if (host.contains(':') && (host == "::1" || host.startsWith("fc", true) || host.startsWith("fd", true) || host.startsWith("fe80:", true))) return false
        return true
    }

    private fun isPostUrl(platform: TogetherBrowsePlatform, url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        return when (platform) {
            TogetherBrowsePlatform.X -> Regex("^/(?:[^/]+/)?status/\\d+(?:/.*)?$").matches(path)
            TogetherBrowsePlatform.XIAOHONGSHU -> Regex("^/(?:explore|discovery/item)/[a-fA-F0-9]{24}/?$").matches(path)
        }
    }

    fun filterCandidates(
        platform: TogetherBrowsePlatform,
        candidates: List<TogetherBrowseCandidate>,
        currentUrl: String,
    ): List<TogetherBrowseCandidate> {
        val current = normalizeUrl(currentUrl) ?: currentUrl
        return candidates.asSequence()
            .mapNotNull { candidate ->
                val url = normalizeUrl(candidate.url) ?: return@mapNotNull null
                if (platformFor(url) != platform || url == current || !isPostUrl(platform, url)) return@mapNotNull null
                candidate.copy(title = candidate.title.trim().ifBlank { url }.take(180), url = url)
            }
            .distinctBy { it.url }
            .take(12)
            .toList()
    }

    fun filterWebCandidates(
        candidates: List<TogetherBrowseCandidate>,
        currentUrl: String,
    ): List<TogetherBrowseCandidate> {
        val current = normalizeUrl(currentUrl) ?: currentUrl
        return candidates.asSequence()
            .mapNotNull { candidate ->
                val url = normalizeUrl(candidate.url) ?: return@mapNotNull null
                if (url == current) return@mapNotNull null
                candidate.copy(title = candidate.title.trim().ifBlank { url }.take(180), url = url)
            }
            .distinctBy { it.url }
            .take(12)
            .toList()
    }
}
