/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

/**
 * 记忆卡片并发修改或版本不一致时抛出的专有异常。
 * 工具层或业务层捕获后可稳定映射为结构化的 REVISION_CONFLICT 响应。
 */
class RevisionConflictException(
    val cardId: String,
    val expectedRevision: Int,
    val actualRevision: Int,
    message: String = "Revision conflict on card $cardId: expected revision $expectedRevision, actual revision $actualRevision"
) : IllegalStateException(message)
