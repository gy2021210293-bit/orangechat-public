package me.rerere.rikkahub.data.ai

internal fun userPromptName(nickname: String): String = nickname.trim().ifEmpty { "你" }

internal fun assistantPromptName(name: String): String = name.trim().ifEmpty { "我" }
