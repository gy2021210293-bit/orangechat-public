package me.rerere.rikkahub.data.ai.affect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class JevEventClassifier {
    data class Result(val event: AffectiveEvent?, val source: String)
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private val labels = mapOf(
        "none" to "没有明确的关系情感事件，包括普通请求和拒绝某项建议",
        "affection" to "用户直接表达想念、喜欢、感谢陪伴或亲近",
        "fight" to "用户明确表达对助手的不满、争吵或被助手伤害",
        "apology" to "用户直接向助手道歉或请求原谅",
        "reject" to "用户明确要求助手停止联系、停止发消息",
    )

    suspend fun classify(settings: Settings, assistant: Assistant, text: String): Result {
        val directKey = JevKeyCipher.decrypt(assistant.affectiveJevApiKey).trim()
        val providerKey = assistant.affectiveJevProviderId?.let { id ->
            settings.providers.filterIsInstance<ProviderSetting.OpenAI>()
                .firstOrNull { it.id == id && it.enabled && it.baseUrl.contains("openrouter.ai") }?.apiKey
        }
        val apiKey = directKey.ifBlank { providerKey.orEmpty() }
        if (apiKey.isBlank()) return Result(AffectiveEngine.classify(text), "本地规则·Jev未配置")
        val url = assistant.affectiveJevUrl.trim()
        if (!url.startsWith("https://")) return Result(AffectiveEngine.classify(text), "本地规则·Jev地址无效")
        return runCatching { Result(call(url, apiKey, assistant.affectiveJevModel, text), "Jev") }
            .getOrElse { Result(AffectiveEngine.classify(text), "本地规则·Jev失败") }
    }

    private suspend fun call(url: String, apiKey: String, model: String, text: String): AffectiveEvent? = withContext(Dispatchers.IO) {
        val criteria = buildJsonObject { labels.forEach { (key, description) -> put(key, description) } }
        val body = buildJsonObject {
            put("model", model.ifBlank { "typesafe/jev-1.13" })
            put("state", text.take(2000))
            put("questions", buildJsonObject {
                put("event", buildJsonObject {
                    put("type", "choice")
                    put("instructions", "这条用户消息对用户与助手的关系明确表达了哪种事件？只判断文字明确支持的事件。")
                    put("criteria", criteria)
                })
            })
        }.toString()
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Jev HTTP ${response.code}")
            val answer = Json.parseToJsonElement(response.body?.string().orEmpty())
                .jsonObject["answers"]!!.jsonObject["event"]!!.jsonObject
            val choice = answer["choice"]?.jsonPrimitive?.contentOrNull ?: error("Jev missing choice")
            val confidence = answer["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            if (confidence < .75 || choice !in labels) error("Jev uncertain")
            when (choice) {
                "affection" -> AffectiveEvent.AFFECTION
                "fight" -> AffectiveEvent.FIGHT
                "apology" -> AffectiveEvent.APOLOGY
                "reject" -> AffectiveEvent.REJECT
                else -> null
            }
        }
    }
}
