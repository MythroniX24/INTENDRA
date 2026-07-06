package com.interndra.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.interndra.data.model.*

@Database(
    entities = [
        ChatMessage::class,
        TerminalLog::class,
        WebSearchCache::class,
        MemoryEntry::class,
        Workspace::class,
        NetworkEvent::class,
        KnowledgeEntry::class,
        TimelineEntry::class,
        PluginEntry::class,
        AutomationRule::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun dao(): AgentDao

    companion object {
        @Volatile private var instance: AgentDatabase? = null

        // ── Migration 2 → 3: memories, workspaces, network_events, workspace column ──
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS memories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL, content TEXT NOT NULL,
                        workspaceId INTEGER NOT NULL DEFAULT 0,
                        tags TEXT NOT NULL DEFAULT '', importanceScore INTEGER NOT NULL DEFAULT 5,
                        isPinned INTEGER NOT NULL DEFAULT 0, isArchived INTEGER NOT NULL DEFAULT 0,
                        timestamp INTEGER NOT NULL, lastAccessedAt INTEGER NOT NULL,
                        accessCount INTEGER NOT NULL DEFAULT 0,
                        actionType TEXT NOT NULL DEFAULT '', commandsJson TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS workspaces (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '',
                        colorHex TEXT NOT NULL DEFAULT '#00E5FF', emoji TEXT NOT NULL DEFAULT '📁',
                        persona TEXT NOT NULL DEFAULT '',
                        isPinned INTEGER NOT NULL DEFAULT 0, isArchived INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS network_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        domain TEXT NOT NULL, feature TEXT NOT NULL,
                        method TEXT NOT NULL DEFAULT 'POST',
                        dataSentBytes INTEGER NOT NULL DEFAULT 0,
                        wasBlocked INTEGER NOT NULL DEFAULT 0, timestamp INTEGER NOT NULL
                    )
                """.trimIndent())
                try {
                    db.execSQL("ALTER TABLE chat_messages ADD COLUMN workspaceId INTEGER NOT NULL DEFAULT 0")
                } catch (_: Exception) { /* column may already exist */ }
            }
        }

        // ── Migration 3 → 4: knowledge_entries, timeline_entries, plugin_entries, automation_rules ──
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS knowledge_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL, content TEXT NOT NULL,
                        type TEXT NOT NULL DEFAULT 'NOTE',
                        tags TEXT NOT NULL DEFAULT '',
                        sourceUrl TEXT NOT NULL DEFAULT '',
                        filePath TEXT NOT NULL DEFAULT '',
                        wordCount INTEGER NOT NULL DEFAULT 0,
                        isPinned INTEGER NOT NULL DEFAULT 0,
                        isArchived INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS timeline_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        type TEXT NOT NULL,
                        title TEXT NOT NULL,
                        detail TEXT NOT NULL DEFAULT '',
                        outcome TEXT NOT NULL DEFAULT '',
                        durationMs INTEGER NOT NULL DEFAULT 0,
                        tags TEXT NOT NULL DEFAULT '',
                        relatedId INTEGER NOT NULL DEFAULT 0,
                        timestamp INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS plugin_entries (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        version TEXT NOT NULL DEFAULT '1.0.0',
                        author TEXT NOT NULL DEFAULT '',
                        status TEXT NOT NULL DEFAULT 'ACTIVE',
                        commands TEXT NOT NULL DEFAULT '',
                        installedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS automation_rules (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        description TEXT NOT NULL,
                        commandType TEXT NOT NULL,
                        command TEXT NOT NULL,
                        delayMinutes INTEGER NOT NULL DEFAULT 0,
                        triggerCondition TEXT NOT NULL DEFAULT '',
                        isEnabled INTEGER NOT NULL DEFAULT 1,
                        runCount INTEGER NOT NULL DEFAULT 0,
                        lastRunAt INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                // Indexes for common queries
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_knowledge_type ON knowledge_entries(type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_timeline_type ON timeline_entries(type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_timeline_ts ON timeline_entries(timestamp)")
            }
        }

        fun getInstance(context: Context): AgentDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AgentDatabase::class.java,
                    "agent_db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigrationFrom(1) // only nuke v1; v2→v4 migrate cleanly
                    .build()
                    .also { instance = it }
            }
        }
    }
}
