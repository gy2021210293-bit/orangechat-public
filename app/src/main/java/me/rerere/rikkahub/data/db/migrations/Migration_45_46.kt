package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_45_46 : Migration(45, 46) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `affective_states` (`assistantId` TEXT NOT NULL, `stateJson` TEXT NOT NULL, PRIMARY KEY(`assistantId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `affective_event_receipts` (`assistantId` TEXT NOT NULL, `eventId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`assistantId`, `eventId`))")
    }
}
