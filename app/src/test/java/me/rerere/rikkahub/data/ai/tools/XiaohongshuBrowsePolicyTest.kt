package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaohongshuBrowsePolicyTest {
    @Test
    fun onlyHttpsXiaohongshuAndShareLinksAreAccepted() {
        assertEquals(
            "https://www.xiaohongshu.com/explore/012345678901234567890123",
            XiaohongshuBrowsePolicy.normalizeUrl("https://www.xiaohongshu.com/explore/012345678901234567890123"),
        )
        assertEquals("https://xhslink.com/abc", XiaohongshuBrowsePolicy.normalizeUrl("https://xhslink.com/abc"))
        assertNull(XiaohongshuBrowsePolicy.normalizeUrl("http://www.xiaohongshu.com/explore/012345678901234567890123"))
        assertNull(XiaohongshuBrowsePolicy.normalizeUrl("https://xiaohongshu.com.attacker.example/post"))
        assertNull(XiaohongshuBrowsePolicy.normalizeUrl("https://example.com/post"))
    }

    @Test
    fun candidateIdsExpireWhenSnapshotGenerationChanges() {
        val id = XiaohongshuBrowsePolicy.candidateId(sessionToken = "abc123", generation = 4, index = 2)

        assertEquals(2, XiaohongshuBrowsePolicy.candidateIndex(id, "abc123", generation = 4, candidateCount = 5))
        assertNull(XiaohongshuBrowsePolicy.candidateIndex(id, "different", generation = 4, candidateCount = 5))
        assertNull(XiaohongshuBrowsePolicy.candidateIndex(id, "abc123", generation = 5, candidateCount = 5))
        assertNull(XiaohongshuBrowsePolicy.candidateIndex("sabc123-v4-8", "abc123", generation = 4, candidateCount = 5))
        assertNull(XiaohongshuBrowsePolicy.candidateIndex("invalid", "abc123", generation = 4, candidateCount = 5))
    }

    @Test
    fun browseSessionAndPostLimitsAreEnforced() {
        assertTrue(XiaohongshuBrowsePolicy.isSessionExpired(createdAt = 1_000, now = 301_000))
        assertFalse(XiaohongshuBrowsePolicy.isSessionExpired(createdAt = 1_000, now = 300_999))
        assertTrue(XiaohongshuBrowsePolicy.canVisitAnotherPost(9))
        assertFalse(XiaohongshuBrowsePolicy.canVisitAnotherPost(10))
        assertTrue(XiaohongshuBrowsePolicy.isPostUrl("https://www.xiaohongshu.com/explore/012345678901234567890123"))
        assertFalse(XiaohongshuBrowsePolicy.isPostUrl("https://www.xiaohongshu.com/explore"))
    }

    @Test
    fun unavailableRedirectIsNotAPost() {
        val url = "https://www.xiaohongshu.com/404?redirectPath=https%3A%2F%2Fwww.xiaohongshu.com%2Fexplore%2F6ac0d29b000000001303f968&error_code=300031"

        assertTrue(XiaohongshuBrowsePolicy.isUnavailablePage(url))
        assertFalse(XiaohongshuBrowsePolicy.isPostUrl(url))
        assertFalse(XiaohongshuBrowsePolicy.isUnavailablePage("https://www.xiaohongshu.com/explore"))
    }

    @Test
    fun appItemLinkKeepsPostIdAndQuery() {
        assertEquals(
            "https://www.xiaohongshu.com/explore/012345678901234567890123?xsec_token=abc",
            XiaohongshuBrowsePolicy.appItemUrl(
                "xhsdiscover://item/012345678901234567890123?xsec_token=abc",
            ),
        )
        assertNull(XiaohongshuBrowsePolicy.appItemUrl("xhsdiscover://item/not-a-post"))
    }
}
