package me.rerere.rikkahub.data.service

import androidx.core.net.toUri
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.rikkahub.data.ai.GeneratedImageRequest
import me.rerere.rikkahub.data.ai.composeImageGenerationPrompt
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import java.io.File
import kotlin.uuid.Uuid

class ChatImageGenerationService(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val filesManager: FilesManager,
    private val genMediaRepository: GenMediaRepository,
) {
    suspend fun generate(request: GeneratedImageRequest): String {
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(Uuid.parse(request.modelId))
            ?: error("配置的生图模型已不存在")
        val providerSetting = model.findProvider(settings.providers)
            ?: error("生图模型的 Provider 已不存在")
        val prompt = composeImageGenerationPrompt(request.systemPrompt, request.prompt)
        val result = providerManager.getProviderByType(providerSetting).generateImage(
            providerSetting,
            ImageGenerationParams(
                model = model,
                prompt = prompt,
                numOfImages = 1,
                aspectRatio = when (request.aspectRatio) {
                    "landscape" -> ImageAspectRatio.LANDSCAPE
                    "portrait" -> ImageAspectRatio.PORTRAIT
                    else -> ImageAspectRatio.SQUARE
                },
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            )
        )
        val item = result.items.firstOrNull() ?: error("生图模型没有返回图片")
        val file = saveImage(item, prompt, model.displayName, 0)
        return file.toUri().toString()
    }

    suspend fun saveImage(
        item: ImageGenerationItem,
        prompt: String,
        modelName: String,
        index: Int,
        type: String = GenMediaEntity.TYPE_IMAGE_GENERATION,
        sourcePaths: String? = null,
    ): File {
        val timestamp = System.currentTimeMillis()
        val safeModelName = modelName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val destination = File(filesManager.getImagesDir(), "${timestamp}_${safeModelName}_$index.png")
        val file = filesManager.createImageFileFromBase64(item.data, destination.absolutePath)
        genMediaRepository.insertMedia(
            GenMediaEntity(
                path = "images/${file.name}",
                modelId = modelName,
                prompt = prompt,
                createAt = timestamp,
                type = type,
                sourcePaths = sourcePaths,
            )
        )
        return file
    }
}
