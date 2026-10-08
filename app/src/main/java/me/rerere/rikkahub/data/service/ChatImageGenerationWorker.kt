package me.rerere.rikkahub.data.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import me.rerere.rikkahub.data.ai.GeneratedImageStatus
import me.rerere.rikkahub.data.ai.generatedImageRequestOrNull
import me.rerere.rikkahub.data.ai.generatedImageStatus
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

class ChatImageGenerationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val conversationRepository: ConversationRepository by inject()
    private val generationService: ChatImageGenerationService by inject()
    private val chatService: ChatService by inject()

    override suspend fun doWork(): Result {
        val conversationId = inputData.getString(KEY_CONVERSATION_ID)?.let(Uuid::parse) ?: return Result.failure()
        val messageId = inputData.getString(KEY_MESSAGE_ID)?.let(Uuid::parse) ?: return Result.failure()
        val conversation = conversationRepository.getConversationById(conversationId) ?: return Result.success()
        val message = conversation.messageNodes.asSequence().map { it.currentMessage }
            .firstOrNull { it.id == messageId } ?: return Result.success()
        val image = message.parts.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Image>().singleOrNull()
            ?: return Result.success()
        val request = image.generatedImageRequestOrNull() ?: return Result.success()
        if (image.generatedImageStatus() != GeneratedImageStatus.PENDING) return Result.success()

        return runCatching { generationService.generate(request) }
            .fold(
                onSuccess = { url ->
                    chatService.updateGeneratedImageCard(
                        conversationId, messageId, request.jobId, GeneratedImageStatus.SUCCEEDED, url, ""
                    )
                    Result.success()
                },
                onFailure = { error ->
                    if (runAttemptCount < 2 && error is java.io.IOException) {
                        Result.retry()
                    } else {
                        chatService.updateGeneratedImageCard(
                            conversationId,
                            messageId,
                            request.jobId,
                            GeneratedImageStatus.FAILED,
                            "",
                            error.message ?: "图片生成失败",
                        )
                        Result.failure()
                    }
                }
            )
    }

    companion object {
        private const val KEY_CONVERSATION_ID = "conversation_id"
        private const val KEY_MESSAGE_ID = "message_id"
        fun workName(jobId: String) = "chat_image_generation_$jobId"

        fun enqueue(context: Context, conversationId: Uuid, messageId: Uuid, jobId: String) {
            val request = OneTimeWorkRequestBuilder<ChatImageGenerationWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_CONVERSATION_ID, conversationId.toString())
                        .putString(KEY_MESSAGE_ID, messageId.toString())
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(jobId), ExistingWorkPolicy.KEEP, request
            )
        }

        fun retry(context: Context, conversationId: Uuid, messageId: Uuid, jobId: String) {
            val request = OneTimeWorkRequestBuilder<ChatImageGenerationWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_CONVERSATION_ID, conversationId.toString())
                        .putString(KEY_MESSAGE_ID, messageId.toString())
                        .build()
                ).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(jobId), ExistingWorkPolicy.REPLACE, request
            )
        }
    }
}
