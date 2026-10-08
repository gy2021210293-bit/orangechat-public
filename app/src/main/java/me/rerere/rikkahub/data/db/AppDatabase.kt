/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.FolderDAO
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.dao.MemoryBankDAO
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MemoryBankEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.dao.SshHostDao
import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryConsolidationRunEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryDailyNoteEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryEmbeddingEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryRevisionEntity
import me.rerere.rikkahub.data.memory.v1.db.MemorySourceEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryV1Dao
import me.rerere.rikkahub.data.security.SecurityAuditDao
import me.rerere.rikkahub.data.security.SecurityAuditEntity
import me.rerere.rikkahub.workflow.db.WorkflowDao
import me.rerere.rikkahub.workflow.db.WorkflowEntity
import me.rerere.rikkahub.workflow.db.WorkflowFireClaimDao
import me.rerere.rikkahub.workflow.db.WorkflowFireClaimEntity
import me.rerere.rikkahub.workflow.db.WorkflowRunDao
import me.rerere.rikkahub.workflow.db.WorkflowRunEntity
import me.rerere.rikkahub.data.db.migrations.Migration_16_17
import me.rerere.rikkahub.data.db.migrations.Migration_24_25
import me.rerere.rikkahub.data.db.migrations.Migration_29_30
import me.rerere.rikkahub.data.db.migrations.Migration_30_31
import me.rerere.rikkahub.data.db.migrations.Migration_31_32
import me.rerere.rikkahub.data.db.migrations.Migration_32_33
import me.rerere.rikkahub.data.db.migrations.Migration_33_34
import me.rerere.rikkahub.data.db.migrations.Migration_34_35
import me.rerere.rikkahub.data.db.migrations.Migration_35_36
import me.rerere.rikkahub.data.db.migrations.Migration_36_37
import me.rerere.rikkahub.data.db.migrations.Migration_37_38
import me.rerere.rikkahub.data.db.migrations.Migration_8_9
import me.rerere.rikkahub.data.memory.v1.db.MemoryEventChunkEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryFactAuditEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryMaintenanceTaskEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryProposalEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryRelationEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryTimelineEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryTimelineMemberEntity
import me.rerere.rikkahub.data.ai.desire.db.DesireActiveSendEntity
import me.rerere.rikkahub.data.ai.desire.db.DesireDao
import me.rerere.rikkahub.data.ai.affect.AffectiveDao
import me.rerere.rikkahub.data.ai.affect.AffectiveStateEntity
import me.rerere.rikkahub.data.ai.affect.AffectiveEventReceiptEntity
import me.rerere.rikkahub.data.ai.desire.db.DesireEventReceiptEntity
import me.rerere.rikkahub.data.ai.desire.db.DesireStateEntity
import me.rerere.rikkahub.data.ai.desire.db.DesireTickLogEntity
import me.rerere.rikkahub.data.ai.desire.db.AutonomousWakeRunEntity
import me.rerere.rikkahub.data.ai.desire.db.AutonomousWakeStateEntity
import me.rerere.rikkahub.data.ai.desire.db.AutonomousItemEntity
import me.rerere.rikkahub.data.ai.desire.db.AutonomousWakePlanEntity
import me.rerere.rikkahub.data.ai.desire.db.AutonomousShareEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryEProposalEntity
import me.rerere.rikkahub.data.music.MusicDailyDiscoveryEntity
import me.rerere.rikkahub.data.music.MusicDao
import me.rerere.rikkahub.data.music.MusicEventEntity
import me.rerere.rikkahub.data.music.MusicFeedbackEntity
import me.rerere.rikkahub.data.music.MusicPlaylistEntity
import me.rerere.rikkahub.data.music.MusicSessionStateEntity
import me.rerere.rikkahub.data.music.MusicSummaryEntity
import me.rerere.rikkahub.data.db.migrations.Migration_39_40
import me.rerere.rikkahub.data.db.migrations.Migration_40_41
import me.rerere.rikkahub.data.db.migrations.Migration_41_42
import me.rerere.rikkahub.utils.JsonInstant

@Database(
    entities = [
        ConversationEntity::class,
        MemoryEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        FavoriteEntity::class,
        MemoryBankEntity::class,
        WorkspaceEntity::class,
        FolderEntity::class,
        WorkflowEntity::class,
        WorkflowRunEntity::class,
        WorkflowFireClaimEntity::class,
        SshHostEntity::class,
        SecurityAuditEntity::class,
        MemoryCardEntity::class,
        MemorySourceEntity::class,
        MemoryRevisionEntity::class,
        MemoryDailyNoteEntity::class,
        MemoryConsolidationRunEntity::class,
        MemoryEmbeddingEntity::class,
        MemoryTimelineEntity::class,
        MemoryTimelineMemberEntity::class,
        MemoryEventChunkEntity::class,
        MemoryProposalEntity::class,
        MemoryRelationEntity::class,
        MemoryMaintenanceTaskEntity::class,
        MemoryFactAuditEntity::class,
        DesireStateEntity::class,
        AffectiveStateEntity::class,
        AffectiveEventReceiptEntity::class,
        DesireTickLogEntity::class,
        DesireActiveSendEntity::class,
        DesireEventReceiptEntity::class,
        AutonomousWakeRunEntity::class,
        AutonomousWakeStateEntity::class,
        AutonomousItemEntity::class,
        AutonomousWakePlanEntity::class,
        AutonomousShareEntity::class,
        MemoryExperienceCoordinateEntity::class,
        MemoryEProposalEntity::class,
        MusicPlaylistEntity::class,
        MusicSessionStateEntity::class,
        MusicEventEntity::class,
        MusicDailyDiscoveryEntity::class,
        MusicFeedbackEntity::class,
        MusicSummaryEntity::class,
    ],
    version = 46,
    autoMigrations = [
        AutoMigration(from = 44, to = 45),
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = Migration_8_9::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 16, to = 17, spec = Migration_16_17::class),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 22, to = 23),
        AutoMigration(from = 26, to = 27),
        AutoMigration(from = 27, to = 28),
        AutoMigration(from = 28, to = 29),
    ]
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun managedFileDao(): ManagedFileDAO

    abstract fun favoriteDao(): FavoriteDAO

    abstract fun memoryBankDao(): MemoryBankDAO

    abstract fun workspaceDao(): WorkspaceDAO

    abstract fun folderDao(): FolderDAO

    abstract fun workflowDao(): WorkflowDao

    abstract fun workflowRunDao(): WorkflowRunDao

    abstract fun workflowFireClaimDao(): WorkflowFireClaimDao

    abstract fun sshHostDao(): SshHostDao

    abstract fun securityAuditDao(): SecurityAuditDao

    abstract fun memoryV1Dao(): MemoryV1Dao

    abstract fun desireDao(): DesireDao

    abstract fun affectiveDao(): AffectiveDao

    abstract fun musicDao(): MusicDao
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String {
        return JsonInstant.encodeToString(usage)
    }

    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? {
        return JsonInstant.decodeFromString(usage)
    }
}
