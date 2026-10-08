package me.rerere.rikkahub.data.ai.affect

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "affective_states")
data class AffectiveStateEntity(
    @PrimaryKey val assistantId: String,
    val stateJson: String,
)

@Entity(tableName = "affective_event_receipts", primaryKeys = ["assistantId", "eventId"])
data class AffectiveEventReceiptEntity(
    val assistantId: String,
    val eventId: String,
    val createdAt: Long,
)

@Dao
interface AffectiveDao {
    @Query("SELECT * FROM affective_states WHERE assistantId = :assistantId")
    suspend fun get(assistantId: String): AffectiveStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: AffectiveStateEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun claim(entity: AffectiveEventReceiptEntity): Long

    @Query("DELETE FROM affective_event_receipts WHERE assistantId = :assistantId")
    suspend fun clearReceipts(assistantId: String)
}
