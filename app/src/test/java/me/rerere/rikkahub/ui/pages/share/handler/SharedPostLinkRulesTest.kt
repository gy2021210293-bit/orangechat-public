package me.rerere.rikkahub.ui.pages.share.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedPostLinkRulesTest {
    @Test
    fun firstLinkIsSelectedAndRemovedFromComment() {
        val text = "看看这个 xhsdiscover://item/123 https://example.com/post?x=1。另一个 https://other.test"

        assertEquals("https://example.com/post?x=1", SharedPostLinkRules.firstUrl(text))
        assertEquals("看看这个 。另一个 https://other.test", SharedPostLinkRules.commentWithoutFirstUrl(text))
    }

    @Test
    fun appLinkIsBlockedUnlessIntentHasWebFallback() {
        assertNull(SharedPostLinkRules.intentFallback("xhsdiscover://item/123"))
        assertEquals(
            "https://example.com/post?a=1&b=2",
            SharedPostLinkRules.intentFallback(
                "intent://post#Intent;scheme=https;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fpost%3Fa%3D1%26b%3D2;end"
            ),
        )
        assertNull(
            SharedPostLinkRules.intentFallback(
                "intent://post#Intent;S.browser_fallback_url=market%3A%2F%2Fdetails;end"
            )
        )
    }

    @Test
    fun xiaohongshuNoteLinkOpensAsWebPage() {
        assertEquals(
            "https://www.xiaohongshu.com/explore/68da1234567890abcdef1234",
            SharedPostLinkRules.xiaohongshuNoteUrl("xhsdiscover://item/68da1234567890abcdef1234"),
        )
        assertEquals(
            "https://www.xiaohongshu.com/explore/68da1234567890abcdef1234?xsec_token=abc%2B123",
            SharedPostLinkRules.xiaohongshuNoteUrl(
                "xhsdiscover://item/68da1234567890abcdef1234?xsec_token=abc%2B123"
            ),
        )
        assertNull(SharedPostLinkRules.xiaohongshuNoteUrl("xhsdiscover://item/123"))
        assertNull(SharedPostLinkRules.xiaohongshuNoteUrl("xhsdiscover://user/68da1234567890abcdef1234"))
    }

    @Test
    fun postImagesAreWebOnlyDeduplicatedAndCapped() {
        val urls = (1..11).map { "https://img.test/$it" } + "https://img.test/1" + "xhsdiscover://image/1"

        assertEquals((1..9).map { "https://img.test/$it" }, SharedPostLinkRules.selectImageUrls(urls))
    }
}
