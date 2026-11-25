package com.kazemail.app.data.db

import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

@Database(
    entities = [
        AccountEntity::class,
        FolderEntity::class,
        MessageMetaEntity::class,
        MessageBodyEntity::class,
        AttachmentEntity::class,
        MessageFtsEntity::class,
        FolderSyncStateEntity::class,
        AccountSecretEntity::class,
        MessageSyncFlagsEntity::class,
        MessageLocalStateEntity::class,
        DraftEntity::class,
        OutboxMessageEntity::class,
        LocalFilterRuleEntity::class,
        AiProviderProfileEntity::class,
        AiWritingPresetEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class KazeMailDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun folderDao(): FolderDao
    abstract fun messageMetaDao(): MessageMetaDao
    abstract fun messageBodyDao(): MessageBodyDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun messageFtsDao(): MessageFtsDao
    abstract fun folderSyncStateDao(): FolderSyncStateDao
    abstract fun accountSecretDao(): AccountSecretDao
    abstract fun messageSyncFlagsDao(): MessageSyncFlagsDao
    abstract fun messageLocalStateDao(): MessageLocalStateDao
    abstract fun draftDao(): DraftDao
    abstract fun outboxDao(): OutboxDao
    abstract fun localFilterRuleDao(): LocalFilterRuleDao
    abstract fun aiProviderProfileDao(): AiProviderProfileDao
    abstract fun aiWritingPresetDao(): AiWritingPresetDao

    companion object {
        const val DB_NAME = "kazemail_secure.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execRaw(
                    """
                    CREATE TABLE IF NOT EXISTS `message_local_state` (
                        `meta_id` INTEGER NOT NULL,
                        `is_archived` INTEGER NOT NULL,
                        `archived_at` INTEGER,
                        PRIMARY KEY(`meta_id`),
                        FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execRaw(
                    "CREATE INDEX IF NOT EXISTS `index_message_local_state_is_archived_archived_at` ON `message_local_state` (`is_archived`, `archived_at`)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execRaw(
                    """
                    CREATE TABLE IF NOT EXISTS `drafts` (
                        `draft_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `account_id` INTEGER NOT NULL,
                        `reply_to_meta_id` INTEGER,
                        `mode` TEXT NOT NULL,
                        `to_addresses` TEXT NOT NULL,
                        `cc_addresses` TEXT NOT NULL,
                        `bcc_addresses` TEXT NOT NULL,
                        `subject` TEXT NOT NULL,
                        `body_text` TEXT NOT NULL,
                        `in_reply_to` TEXT,
                        `references_json` TEXT,
                        `attachments_json` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execRaw(
                    "CREATE INDEX IF NOT EXISTS `index_drafts_account_id_updated_at` ON `drafts` (`account_id`, `updated_at`)",
                )
                connection.execRaw(
                    """
                    CREATE TABLE IF NOT EXISTS `outbox` (
                        `outbox_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `account_id` INTEGER NOT NULL,
                        `draft_id` INTEGER,
                        `from_email` TEXT NOT NULL,
                        `from_name` TEXT,
                        `to_addresses` TEXT NOT NULL,
                        `cc_addresses` TEXT NOT NULL,
                        `bcc_addresses` TEXT NOT NULL,
                        `subject` TEXT NOT NULL,
                        `body_text` TEXT NOT NULL,
                        `in_reply_to` TEXT,
                        `references_json` TEXT,
                        `message_id_header` TEXT,
                        `attachments_json` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `attempt_count` INTEGER NOT NULL,
                        `last_error` TEXT,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `sent_at` INTEGER,
                        FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execRaw(
                    "CREATE INDEX IF NOT EXISTS `index_outbox_account_id_status_updated_at` ON `outbox` (`account_id`, `status`, `updated_at`)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execRaw("ALTER TABLE `message_local_state` ADD COLUMN `local_category` TEXT")
                connection.execRaw("ALTER TABLE `message_local_state` ADD COLUMN `hidden_from_folder` INTEGER NOT NULL DEFAULT 0")
                connection.execRaw(
                    "CREATE INDEX IF NOT EXISTS `index_message_local_state_local_category` ON `message_local_state` (`local_category`)",
                )
                connection.execRaw(
                    """
                    CREATE TABLE IF NOT EXISTS `local_filter_rules` (
                        `rule_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `account_id` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `is_enabled` INTEGER NOT NULL,
                        `priority` INTEGER NOT NULL,
                        `match_mode` TEXT NOT NULL,
                        `conditions_json` TEXT NOT NULL,
                        `target_local_folder` TEXT,
                        `hide_from_folder` INTEGER NOT NULL,
                        `archive_locally` INTEGER NOT NULL,
                        `mark_read` INTEGER NOT NULL,
                        `mark_flagged` INTEGER NOT NULL,
                        `suppress_notification` INTEGER NOT NULL,
                        `stop_processing` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execRaw(
                    "CREATE INDEX IF NOT EXISTS `index_local_filter_rules_account_id_priority` ON `local_filter_rules` (`account_id`, `priority`)",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execRaw(
                    "CREATE INDEX IF NOT EXISTS `idx_meta_account` ON `messages_meta` (`account_id`)",
                )
                connection.execRaw(
                    "CREATE INDEX IF NOT EXISTS `idx_meta_folder_thread_date` ON `messages_meta` (`folder_id` ASC, `thread_id` ASC, `received_at` DESC)",
                )
                connection.execRaw(
                    "CREATE INDEX IF NOT EXISTS `idx_meta_account_thread_date` ON `messages_meta` (`account_id` ASC, `thread_id` ASC, `received_at` ASC)",
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execRaw(
                    """
                    CREATE TABLE IF NOT EXISTS `ai_provider_profiles` (
                        `profile_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `provider_type` TEXT NOT NULL,
                        `base_url` TEXT NOT NULL,
                        `model_id` TEXT NOT NULL,
                        `encrypted_api_key_b64` TEXT,
                        `api_key_iv_b64` TEXT,
                        `api_key_hint` TEXT,
                        `is_default` INTEGER NOT NULL,
                        `is_enabled` INTEGER NOT NULL,
                        `last_test_status` TEXT NOT NULL,
                        `last_test_message` TEXT,
                        `last_tested_at` INTEGER,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                connection.execRaw(
                    "CREATE INDEX IF NOT EXISTS `index_ai_provider_profiles_is_default_updated_at` ON `ai_provider_profiles` (`is_default`, `updated_at`)",
                )
                connection.execRaw(
                    """
                    CREATE TABLE IF NOT EXISTS `ai_writing_presets` (
                        `preset_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `goal` TEXT NOT NULL,
                        `tone` TEXT NOT NULL,
                        `instruction_prompt` TEXT NOT NULL,
                        `is_built_in` INTEGER NOT NULL,
                        `sort_order` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                connection.execRaw(
                    "CREATE INDEX IF NOT EXISTS `index_ai_writing_presets_sort_order_preset_id` ON `ai_writing_presets` (`sort_order`, `preset_id`)",
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // Prior builds created idx_meta_unread directly in Callback.onCreate outside @Entity(indices).
                // Drop that unmanaged partial index and replace it with Room-tracked idx_meta_folder_read.
                connection.execRaw("DROP INDEX IF EXISTS `idx_meta_unread`")
                connection.execRaw(
                    "CREATE INDEX IF NOT EXISTS `idx_meta_folder_read` ON `messages_meta` (`folder_id`, `is_read`)",
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execRaw("ALTER TABLE `accounts` ADD COLUMN `signature_enabled` INTEGER NOT NULL DEFAULT 0")
                connection.execRaw("ALTER TABLE `accounts` ADD COLUMN `signature_text` TEXT NOT NULL DEFAULT ''")
                connection.execRaw("ALTER TABLE `accounts` ADD COLUMN `signature_configured` INTEGER NOT NULL DEFAULT 0")
                connection.execRaw("ALTER TABLE `drafts` ADD COLUMN `is_html` INTEGER NOT NULL DEFAULT 0")
                connection.execRaw("ALTER TABLE `drafts` ADD COLUMN `body_html` TEXT")
                connection.execRaw("ALTER TABLE `outbox` ADD COLUMN `body_html` TEXT")
            }
        }

        fun buildWithDriver(
            builder: Builder<KazeMailDatabase>,
            driver: SQLiteDriver,
            queryContext: CoroutineContext = Dispatchers.IO,
        ): KazeMailDatabase =
            builder
                .setDriver(driver)
                .setQueryCoroutineContext(queryContext)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                )
                .addCallback(
                    object : Callback() {
                        override suspend fun onOpen(connection: SQLiteConnection) {
                            connection.execRaw("PRAGMA foreign_keys = ON")
                            connection.execRaw("PRAGMA busy_timeout = 5000")
                            connection.execRaw("PRAGMA journal_mode = WAL")
                            connection.execRaw("PRAGMA synchronous = NORMAL")
                            connection.execRaw("PRAGMA temp_store = MEMORY")
                        }
                    },
                )
                .build()

        private fun SQLiteConnection.execRaw(sql: String) {
            prepare(sql).use { it.step() }
        }
    }
}
