/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.tts.provider.providers

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.common.http.SseEvent
import me.rerere.common.http.sseFlow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

internal const val MIMO_VOICE_DESIGN_MODEL = "mimo-v2.5-tts-voicedesign"

// MiMo 流式音频按文档示例使用 24kHz PCM16LE
private const val MIMO_SAMPLE_RATE = 24000
private val JSON_MEDIA_TYPE = "application/json".toMediaType()
// 只关心 delta.audio.data 其余字段忽略
private val mimoJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class MiMoChunk(
    val choices: List<MiMoChoice> = emptyList()
)

@Serializable
private data class MiMoChoice(
    val delta: MiMoDelta? = null
)

@Serializable
private data class MiMoDelta(
    val audio: MiMoAudio? = null
)

@Serializable
private data class MiMoAudio(
    val data: String? = null
)

@Serializable
private data class MiMoVoiceDesignResponse(
    val choices: List<MiMoVoiceDesignChoice> = emptyList()
)

@Serializable
private data class MiMoVoiceDesignChoice(
    val message: MiMoVoiceDesignMessage? = null
)

@Serializable
private data class MiMoVoiceDesignMessage(
    val audio: MiMoAudio? = null
)

internal fun buildMiMoVoiceDesignRequest(
    providerSetting: TTSProviderSetting.MiMo,
    request: TTSRequest
): JsonObject = buildJsonObject {
    put("model", providerSetting.model)
    put("messages", buildJsonArray {
        add(buildJsonObject {
            put("role", "user")
            put("content", providerSetting.voice)
        })
        add(buildJsonObject {
            put("role", "assistant")
            put("content", request.text)
        })
    })
    put("audio", buildJsonObject {
        put("format", "wav")
        put("optimize_text_preview", providerSetting.optimizeTextPreview)
    })
}

internal fun decodeMiMoAudioData(data: String): ByteArray? {
    val payload = data.trim()
    // [DONE] 表示流结束 不输出音频
    if (payload == "[DONE]") return null
    // 非 [DONE] 的 data 视为 JSON 片段 解析失败直接上抛
    val chunk = mimoJson.decodeFromString<MiMoChunk>(payload)
    val encoded = chunk.choices.firstOrNull()?.delta?.audio?.data ?: return null
    // 空字符串视为无音频片段
    if (encoded.isBlank()) return null
    return Base64.getDecoder().decode(encoded)
}

internal class MiMoSseProcessor(
    private val model: String,
    private val voice: String
) {
    private var hasAudio = false
    // metadata 只构造一次 贯穿整个流
    private val metadata = mapOf(
        "provider" to "mimo",
        "model" to model,
        "voice" to voice
    )

    fun process(event: SseEvent): AudioChunk? {
        return when (event) {
            is SseEvent.Open -> null
            is SseEvent.Event -> {
                // 只处理包含 audio.data 的增量事件 其他事件忽略
                val pcmData = decodeMiMoAudioData(event.data) ?: return null
                hasAudio = true
                AudioChunk(
                    data = pcmData,
                    format = AudioFormat.PCM,
                    sampleRate = MIMO_SAMPLE_RATE,
                    metadata = metadata
                )
            }

            is SseEvent.Closed -> {
                // 如果整段流没有任何音频片段 直接报错
                if (!hasAudio) {
                    throw IllegalStateException("MiMo TTS returned no audio chunks")
                }
                // 流关闭时补一个终结 chunk 便于播放器收尾
                AudioChunk(
                    data = byteArrayOf(),
                    format = AudioFormat.PCM,
                    sampleRate = MIMO_SAMPLE_RATE,
                    isLast = true,
                    metadata = metadata
                )
            }

            is SseEvent.Failure -> throw event.throwable ?: Exception("MiMo TTS streaming failed")
        }
    }
}

class MiMoTTSProvider : TTSProvider<TTSProviderSetting.MiMo> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override val promptGuidance: String = """
        The active MiMo text-to-speech engine, including mimo-v2.5-tts-voicedesign, supports style and audio tags in
        the text_to_speech tool's "text" argument.
        Put tags ONLY in that tool argument, never in the visible reply. Use them sparingly.
        Put one overall style tag at the beginning, for example (温柔), (开心 磁性), or (唱歌).
        Inline tags may refine delivery, for example [笑], [轻笑], [叹气], [吸气], [哽咽], [气声].
        Do not put punctuation inside tags, do not use Markdown emphasis, and do not place a (…) group immediately after an inline [tag].
        Example: (磁性)夜已经深了[叹气]城市还在呼吸。
    """.trimIndent()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.MiMo,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        if (providerSetting.model == MIMO_VOICE_DESIGN_MODEL) {
            val requestBody = buildMiMoVoiceDesignRequest(providerSetting, request)
            val httpRequest = Request.Builder()
                .url("${providerSetting.baseUrl}/chat/completions")
                .addHeader("api-key", providerSetting.apiKey)
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body.string()
                if (!response.isSuccessful) {
                    throw IllegalStateException("MiMo VoiceDesign HTTP ${response.code}: $responseBody")
                }
                val encodedAudio = mimoJson
                    .decodeFromString<MiMoVoiceDesignResponse>(responseBody)
                    .choices
                    .firstOrNull()
                    ?.message
                    ?.audio
                    ?.data
                    ?: throw IllegalStateException("MiMo VoiceDesign returned no audio data")

                emit(
                    AudioChunk(
                        data = Base64.getDecoder().decode(encodedAudio),
                        format = AudioFormat.WAV,
                        isLast = true,
                        metadata = mapOf(
                            "provider" to "mimo",
                            "model" to providerSetting.model,
                            "voice_description" to providerSetting.voice,
                            "optimize_text_preview" to providerSetting.optimizeTextPreview.toString()
                        )
                    )
                )
            }
            return@flow
        }

        // OpenAI 兼容的 chat/completions SSE 流式返回 音频增量在 delta.audio.data
        val requestBody = buildJsonObject {
            put("model", providerSetting.model)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", request.text)
                })
            })
            put("audio", buildJsonObject {
                put("format", "pcm16")
                put("voice", providerSetting.voice)
            })
            put("stream", true)
        }

        // baseUrl 允许用户在设置页自定义 这里直接拼接路径
        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/chat/completions")
            // MiMo 使用 api-key 头传 token
            .addHeader("api-key", providerSetting.apiKey)
            .addHeader("Content-Type", "application/json")
            // JsonObject 的 toString 会输出 JSON 字符串
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val processor = MiMoSseProcessor(
            model = providerSetting.model,
            voice = providerSetting.voice
        )

        httpClient.sseFlow(httpRequest).collect { event ->
            processor.process(event)?.let { emit(it) }
        }
    }
}
