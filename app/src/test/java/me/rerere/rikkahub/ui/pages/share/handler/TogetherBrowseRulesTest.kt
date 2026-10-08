package me.rerere.rikkahub.ui.pages.share.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import okhttp3.Dns

class TogetherBrowseRulesTest {
    @Test
    fun acceptsWebLinksAndRecognizesXAndXiaohongshuHosts() {
        assertEquals("https://x.com/user/status/123", TogetherBrowseRules.normalizeUrl("x.com/user/status/123"))
        assertEquals(TogetherBrowsePlatform.X, TogetherBrowseRules.platformFor("https://t.co/abc"))
        assertEquals(TogetherBrowsePlatform.XIAOHONGSHU, TogetherBrowseRules.platformFor("https://xhslink.com/abc"))
        assertEquals("https://example.com/post", TogetherBrowseRules.normalizeUrl("https://example.com/post"))
        assertNull(TogetherBrowseRules.normalizeUrl("javascript:alert(1)"))
        assertNull(TogetherBrowseRules.platformFor("https://x.com.attacker.example/post"))
        assertTrue(TogetherBrowseRules.isTrustedImageUrl(TogetherBrowsePlatform.X, "https://pbs.twimg.com/media/a.jpg"))
        assertTrue(TogetherBrowseRules.isTrustedImageUrl(TogetherBrowsePlatform.XIAOHONGSHU, "https://sns-img.xhscdn.com/a.jpg"))
        assertTrue(TogetherBrowseRules.isTrustedImageUrl(TogetherBrowsePlatform.XIAOHONGSHU, "http://sns-img.xhscdn.com/a.jpg"))
        assertFalse(TogetherBrowseRules.isTrustedImageUrl(TogetherBrowsePlatform.X, "http://pbs.twimg.com/media/a.jpg"))
        assertFalse(TogetherBrowseRules.isTrustedImageUrl(TogetherBrowsePlatform.X, "https://attacker.example/a.jpg"))
        assertFalse(TogetherBrowseRules.isTrustedImageUrl(TogetherBrowsePlatform.XIAOHONGSHU, "https://xhscdn.com.attacker.example/a.jpg"))
        assertTrue(TogetherBrowseRules.isWebImageUrl("https://cdn.example.com/a.jpg"))
        assertFalse(TogetherBrowseRules.isWebImageUrl("https://192.168.1.2/a.jpg"))
        assertFalse(TogetherBrowseRules.isWebImageUrl("https://100.64.0.1/a.jpg"))
        assertFalse(TogetherBrowseRules.isWebImageUrl("https://[::1]/a.jpg"))
        assertFalse(TogetherBrowseRules.isWebImageUrl("http://cdn.example.com/a.jpg"))
    }

    @Test
    fun resolvedImageAddressesMustBePublic() {
        listOf("127.0.0.1", "192.168.1.2", "100.64.0.1", "169.254.1.1", "198.18.0.1", "::1", "fc00::1")
            .forEach { assertFalse(it, isPublicImageAddress(InetAddress.getByName(it))) }
        assertTrue(isPublicImageAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(isPublicImageAddress(InetAddress.getByName("2606:4700:4700::1111")))
        val privateDns = publicImageDns(Dns { listOf(InetAddress.getByName("127.0.0.1")) })
        assertTrue(runCatching { privateDns.lookup("cdn.example.com") }.isFailure)
        val publicDns = publicImageDns(Dns { listOf(InetAddress.getByName("8.8.8.8")) })
        assertEquals("8.8.8.8", publicDns.lookup("cdn.example.com").single().hostAddress)
    }


    @Test
    fun candidateFilterKeepsUniqueSamePlatformLinksOnly() {
        val candidates = listOf(
            TogetherBrowseCandidate("same page", "https://x.com/user/status/1"),
            TogetherBrowseCandidate("next", "https://x.com/user/status/2"),
            TogetherBrowseCandidate("duplicate", "https://x.com/user/status/2"),
            TogetherBrowseCandidate("profile", "https://x.com/user"),
            TogetherBrowseCandidate("other platform", "https://www.xiaohongshu.com/explore/012345678901234567890123"),
            TogetherBrowseCandidate("outside", "https://example.com/"),
        )

        assertEquals(
            listOf(TogetherBrowseCandidate("next", "https://x.com/user/status/2")),
            TogetherBrowseRules.filterCandidates(TogetherBrowsePlatform.X, candidates, "https://x.com/user/status/1"),
        )
    }

    @Test
    fun xiaohongshuCandidatesMustBeNotePages() {
        val candidates = listOf(
            TogetherBrowseCandidate("note", "https://www.xiaohongshu.com/explore/112345678901234567890123"),
            TogetherBrowseCandidate("profile", "https://www.xiaohongshu.com/user/profile/abc"),
        )
        assertEquals(
            listOf(candidates.first()),
            TogetherBrowseRules.filterCandidates(
                TogetherBrowsePlatform.XIAOHONGSHU,
                candidates,
                "https://www.xiaohongshu.com/explore/012345678901234567890123",
            ),
        )
    }

    @Test
    fun genericWebCandidatesCanBeSuggested() {
        val candidates = listOf(
            TogetherBrowseCandidate("same page", "https://example.com/article"),
            TogetherBrowseCandidate("related page", "https://example.com/related"),
            TogetherBrowseCandidate("other site", "https://news.example.net/story"),
            TogetherBrowseCandidate("unsafe", "javascript:alert(1)"),
        )

        assertEquals(
            listOf(candidates[1], candidates[2]),
            TogetherBrowseRules.filterWebCandidates(candidates, "https://example.com/article"),
        )
    }

    @Test
    fun snapshotMustStillMatchTheLivePageBeforeItCanBeUsed() {
        val snapshot = TogetherBrowseSnapshot(
            url = "https://x.com/user/status/1",
            title = "Post",
            text = "Body",
            comments = emptyList(),
            imageUrls = emptyList(),
            candidates = emptyList(),
        )

        assertTrue(TogetherBrowseRules.isFreshSnapshot(snapshot, "https://x.com/user/status/1"))
        assertFalse(TogetherBrowseRules.isFreshSnapshot(snapshot, "https://x.com/user/status/2"))
    }
}
