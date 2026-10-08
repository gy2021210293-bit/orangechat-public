/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_38_39 : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `memory_cards` ADD COLUMN `always_inject` INTEGER NOT NULL DEFAULT 0")

        // 幂等清理存量悬空 PENDING 事实审计与孤立 REVIEW 候选卡，防止旧版本数据库升级后永久阻断事实成长
        db.execSQL(
            """
            UPDATE `memory_cards`
            SET `status` = 'ARCHIVED',
                `revision` = `revision` + 1,
                `valid_to` = CAST(strftime('%s', 'now') AS INTEGER) * 1000,
                `updated_at` = CAST(strftime('%s', 'now') AS INTEGER) * 1000,
                `updated_by` = 'SYSTEM'
            WHERE `status` = 'REVIEW'
              AND `id` IN (
                  SELECT `candidate_card_id` FROM `memory_fact_audits`
                  WHERE `status` = 'PENDING'
                    AND `candidate_card_id` IS NOT NULL
                    AND (
                        `existing_card_id` NOT IN (SELECT `id` FROM `memory_cards`)
                        OR `existing_card_id` IN (SELECT `id` FROM `memory_cards` WHERE `status` IN ('SUPERSEDED', 'ARCHIVED'))
                    )
              )
            """.trimIndent()
        )

        db.execSQL(
            """
            UPDATE `memory_fact_audits`
            SET `status` = 'REJECTED',
                `resolved_at` = CAST(strftime('%s', 'now') AS INTEGER) * 1000,
                `resolved_by` = 'SYSTEM',
                `reason` = `reason` || ' (Auto-closed: dangling audit targeting invalid/superseded/archived cards)'
            WHERE `status` = 'PENDING'
              AND (
                  `existing_card_id` NOT IN (SELECT `id` FROM `memory_cards`)
                  OR `existing_card_id` IN (SELECT `id` FROM `memory_cards` WHERE `status` IN ('SUPERSEDED', 'ARCHIVED'))
                  OR (`candidate_card_id` IS NOT NULL AND `candidate_card_id` NOT IN (SELECT `id` FROM `memory_cards`))
                  OR (`candidate_card_id` IS NOT NULL AND `candidate_card_id` IN (SELECT `id` FROM `memory_cards` WHERE `status` IN ('SUPERSEDED', 'ARCHIVED')))
              )
            """.trimIndent()
        )
    }
}
