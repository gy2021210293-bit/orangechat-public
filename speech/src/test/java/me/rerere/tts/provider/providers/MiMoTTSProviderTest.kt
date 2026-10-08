/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.tts.provider.providers

import me.rerere.common.http.SseEvent
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MiMoTTSProviderTest {
    @Test
    fun prompt_guidance_keeps_tags_inside_the_text_to_speech_tool() {
        val guidance = MiMoTTSProvider().promptGuidance

        assertTrue(guidance.contains("ONLY in that tool argument"))
        assertTrue(guidance.contains("mimo-v2.5-tts-voicedesign"))
        assertTrue(guidance.contains("(温柔)"))
        assertTrue(guidance.contains("[笑]"))
    }

    @Test
    fun voice_design_request_uses_timbre_description_and_target_text() {
        val request = buildMiMoVoiceDesignRequest(
            TTSProviderSetting.MiMo(voice = "Give me a young male tone.", optimizeTextPreview = true),
            TTSRequest("Yes, I had a sandwich.")
        )

        val messages = request["messages"].toString()
        val audio = request["audio"].toString()
        assertTrue(messages.contains("\"role\":\"user\""))
        assertTrue(messages.contains("Give me a young male tone."))
        assertTrue(messages.contains("\"role\":\"assistant\""))
        assertTrue(messages.contains("Yes, I had a sandwich."))
        assertTrue(audio.contains("\"format\":\"wav\""))
        assertTrue(audio.contains("\"optimize_text_preview\":true"))
    }

    @Test
    fun voice_design_request_preserves_style_and_audio_tags() {
        val speech = "(温柔)晚上好[轻笑]今天过得怎么样？"
        val request = buildMiMoVoiceDesignRequest(
            TTSProviderSetting.MiMo(),
            TTSRequest(speech)
        )

        assertTrue(request["messages"].toString().contains(speech))
    }

    @Test
    fun decode_audio_data_from_sse_chunk() {
        val expected = byteArrayOf(1, 2, 3, 4)
        val encoded = Base64.getEncoder().encodeToString(expected)
        val data = """{"choices":[{"delta":{"audio":{"data":"$encoded"}}}]}"""

        val actual = decodeMiMoAudioData(data)

        assertNotNull(actual)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun ignore_sse_chunk_without_audio_data() {
        val data = """{"choices":[{"delta":{"content":"hello"}}]}"""
        assertNull(decodeMiMoAudioData(data))
    }

    @Test
    fun emits_single_terminal_chunk_on_done_and_closed() {
        val processor = MiMoSseProcessor(model = "mimo-v2-tts", voice = "mimo_default")
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7))
        val audioData = """{"choices":[{"delta":{"audio":{"data":"$encoded"}}}]}"""

        val first = processor.process(SseEvent.Event(id = null, type = null, data = audioData))
        val done = processor.process(SseEvent.Event(id = null, type = null, data = "[DONE]"))
        val terminal = processor.process(SseEvent.Closed)

        assertNotNull(first)
        assertEquals(AudioFormat.PCM, first?.format)
        assertFalse(first?.isLast ?: true)
        assertNull(done)
        assertNotNull(terminal)
        assertTrue(terminal?.isLast ?: false)
    }

    @Test
    fun throws_when_stream_closed_without_audio() {
        val processor = MiMoSseProcessor(model = "mimo-v2-tts", voice = "mimo_default")

        var thrown: Throwable? = null
        try {
            processor.process(SseEvent.Event(id = null, type = null, data = "[DONE]"))
            processor.process(SseEvent.Closed)
        } catch (t: Throwable) {
            thrown = t
        }

        assertNotNull(thrown)
        assertTrue(thrown is IllegalStateException)
    }
}
