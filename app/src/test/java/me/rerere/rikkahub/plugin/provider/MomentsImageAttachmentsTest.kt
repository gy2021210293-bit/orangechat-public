package me.rerere.rikkahub.plugin.provider

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentsImageAttachmentsTest {
    private val allowedHosts = listOf("ciorcbphsbuhhhpjwuhd.supabase.co")

    @Test
    fun acceptsOnlyHttpsPublicMomentImagesFromAllowedHost() {
        assertTrue(
            MomentsImageAttachments.isAllowedImageUrl(
                "https://ciorcbphsbuhhhpjwuhd.supabase.co/storage/v1/object/public/diary-images/photo.jpg",
                allowedHosts,
            )
        )
        assertFalse(
            MomentsImageAttachments.isAllowedImageUrl(
                "http://ciorcbphsbuhhhpjwuhd.supabase.co/storage/v1/object/public/diary-images/photo.jpg",
                allowedHosts,
            )
        )
        assertFalse(
            MomentsImageAttachments.isAllowedImageUrl(
                "https://ciorcbphsbuhhhpjwuhd.supabase.co.attacker.example/storage/v1/object/public/diary-images/photo.jpg",
                allowedHosts,
            )
        )
        assertFalse(
            MomentsImageAttachments.isAllowedImageUrl(
                "https://ciorcbphsbuhhhpjwuhd.supabase.co/storage/v1/object/public/other-bucket/photo.jpg",
                allowedHosts,
            )
        )
        assertFalse(
            MomentsImageAttachments.isAllowedImageUrl(
                "https://ciorcbphsbuhhhpjwuhd.supabase.co/storage/v1/object/public/diary-images/../private/photo.jpg",
                allowedHosts,
            )
        )
    }

    @Test
    fun capsVisualInputAtNineImages() {
        val urls = (1..12).map {
            "https://ciorcbphsbuhhhpjwuhd.supabase.co/storage/v1/object/public/diary-images/$it.jpg"
        }

        val candidates = MomentsImageAttachments.candidateUrls(urls, allowedHosts)

        assertEquals(9, candidates.size)
        assertTrue(candidates.last().endsWith("9.jpg"))
    }

    @Test
    fun statusReportsPartialDownloadsAndUnsupportedModels() {
        val partial = MomentsImageAttachments.DownloadResult(
            images = listOf(UIMessagePart.Image("file:///image.jpg")),
            unreadCount = 1,
        )

        assertTrue(MomentsImageAttachments.statusText(2, partial, supportsImages = true).contains("1/2"))
        assertTrue(MomentsImageAttachments.statusText(2, partial, supportsImages = true).contains("1 张未读取"))
        assertTrue(MomentsImageAttachments.statusText(2, partial, supportsImages = false).contains("图片未读取"))
        assertEquals("", MomentsImageAttachments.statusText(0, partial, supportsImages = true))
    }

    @Test
    fun stripsMisleadingVisualInputField() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val payloadWithVisualInput = json.parseToJsonElement(
            """
            {
                "success": true,
                "data": {
                    "id": "123",
                    "content": "Hello",
                    "visual_input": true,
                    "images": ["https://ciorcbphsbuhhhpjwuhd.supabase.co/storage/v1/object/public/diary-images/1.jpg"]
                }
            }
            """.trimIndent()
        )

        val sanitized = MomentsImageAttachments.sanitizeResult(payloadWithVisualInput)
        val dataObj = (sanitized as kotlinx.serialization.json.JsonObject)["data"] as kotlinx.serialization.json.JsonObject

        assertFalse(dataObj.containsKey("visual_input"))
        assertTrue(dataObj.containsKey("id"))
        assertTrue(dataObj.containsKey("images"))
    }
}
