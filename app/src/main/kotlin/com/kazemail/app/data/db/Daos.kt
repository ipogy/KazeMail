package com.kazemail.app.data.db

import androidx.paging.PagingSource
import androidx.room3.Dao
import androidx.room3.DaoReturnTypeConverters
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import androidx.room3.Transaction
import androidx.room3.paging.PagingSourceDaoReturnTypeConverter
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY account_id ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY account_id ASC")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE account_id = :accountId LIMIT 1")
    suspend fun getById(accountId: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE account_id = :accountId LIMIT 1")
    fun observeById(accountId: Long): Flow<AccountEntity?>

    @Query("SELECT * FROM accounts WHERE email_address = :email LIMIT 1")
    suspend fun getByEmail(email: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: AccountEntity): Long

    @Query(
        """
        UPDATE accounts
        SET display_name = :displayName,
            imap_host = :imapHost,
            imap_port = :imapPort,
            smtp_host = :smtpHost,
            smtp_port = :smtpPort,
            auth_type = :authType
        WHERE account_id = :accountId
        """,
    )
    suspend fun updateConnectionConfig(
        accountId: Long,
        displayName: String?,
        imapHost: String,
        imapPort: Int,
        smtpHost: String,
        smtpPort: Int,
        authType: String,
    )

    @Query("UPDATE accounts SET last_sync_at = :timestampMs WHERE account_id = :accountId")
    suspend fun updateLastSyncAt(accountId: Long, timestampMs: Long)

    @Query(
        """
        UPDATE accounts
        SET signature_enabled = :enabled,
            signature_text = :signatureText,
            signature_configured = 1
        WHERE account_id = :accountId
        """,
    )
    suspend fun updateSignature(
        accountId: Long,
        enabled: Boolean,
        signatureText: String,
    )

    @Query("DELETE FROM accounts WHERE account_id = :accountId")
    suspend fun deleteById(accountId: Long)
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE account_id = :accountId ORDER BY CASE WHEN UPPER(full_path) = 'INBOX' THEN 0 ELSE 1 END, full_path ASC")
    fun observeByAccount(accountId: Long): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY account_id ASC, full_path ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE account_id = :accountId ORDER BY CASE WHEN UPPER(full_path) = 'INBOX' THEN 0 ELSE 1 END, full_path ASC")
    suspend fun getByAccount(accountId: Long): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE account_id = :accountId AND full_path = :fullPath LIMIT 1")
    suspend fun getByPath(accountId: Long, fullPath: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE folder_id = :folderId LIMIT 1")
    suspend fun getById(folderId: Long): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: FolderEntity): Long

    @Query(
        """
        UPDATE folders
        SET folder_name = :folderName,
            full_path = :fullPath,
            uid_validity = :uidValidity,
            highest_mod_seq = :highestModSeq,
            last_synced_uid = :lastSyncedUid,
            total_count = :totalCount,
            unread_count = :unreadCount
        WHERE folder_id = :folderId
        """,
    )
    suspend fun updateFolderState(
        folderId: Long,
        folderName: String,
        fullPath: String,
        uidValidity: Long,
        highestModSeq: Long,
        lastSyncedUid: Long,
        totalCount: Int,
        unreadCount: Int,
    )

    @Query(
        """
        UPDATE folders
        SET highest_mod_seq = MAX(highest_mod_seq, :highestModSeq),
            last_synced_uid = MAX(last_synced_uid, :lastSyncedUid),
            total_count = :totalCount,
            unread_count = :unreadCount
        WHERE folder_id = :folderId
        """,
    )
    suspend fun updateSyncProgress(
        folderId: Long,
        highestModSeq: Long,
        lastSyncedUid: Long,
        totalCount: Int,
        unreadCount: Int,
    )

    @Query("UPDATE folders SET folder_name = :folderName WHERE folder_id = :folderId")
    suspend fun updateFolderName(folderId: Long, folderName: String)

    @Query(
        """
        UPDATE folders
        SET full_path = :archivedPath,
            folder_name = :archivedName
        WHERE folder_id = :folderId
        """,
    )
    suspend fun renameToArchiveSnapshot(
        folderId: Long,
        archivedPath: String,
        archivedName: String,
    )
}

@Dao
@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)
interface MessageMetaDao {
    @Query("SELECT * FROM messages_meta WHERE folder_id = :folderId ORDER BY received_at DESC LIMIT :limit")
    fun observeRecentByFolder(folderId: Long, limit: Int = 100): Flow<List<MessageMetaEntity>>

    @Query("SELECT * FROM messages_meta WHERE folder_id = :folderId ORDER BY received_at DESC")
    fun pagingSourceByFolder(folderId: Long): PagingSource<Int, MessageMetaEntity>

    @Query(
        """
        SELECT m.*
        FROM messages_meta m
        LEFT JOIN message_local_state s ON s.meta_id = m.id
        WHERE m.folder_id = :folderId
          AND COALESCE(s.is_archived, 0) = 0
          AND COALESCE(s.hidden_from_folder, 0) = 0
        ORDER BY m.received_at DESC
        """,
    )
    fun pagingSourceFlatByFolder(folderId: Long): PagingSource<Int, MessageMetaEntity>

    @Query(
        """
        SELECT
            m.id AS latestMetaId,
            COALESCE(m.thread_id, 'id:' || m.id) AS threadKey,
            m.account_id AS accountId,
            m.folder_id AS folderId,
            m.sender_name AS senderName,
            m.sender_address AS senderAddress,
            m.subject AS subject,
            m.snippet AS snippet,
            MAX(m.received_at) AS latestReceivedAt,
            COUNT(*) AS messageCount,
            SUM(CASE WHEN m.is_read = 0 THEN 1 ELSE 0 END) AS unreadCount,
            MAX(m.is_flagged) AS hasFlagged,
            MAX(m.has_attachments) AS hasAttachments
        FROM messages_meta m
        LEFT JOIN message_local_state s ON s.meta_id = m.id
        WHERE m.folder_id = :folderId
          AND COALESCE(s.is_archived, 0) = 0
          AND COALESCE(s.hidden_from_folder, 0) = 0
        GROUP BY COALESCE(m.thread_id, 'id:' || m.id)
        ORDER BY latestReceivedAt DESC
        """,
    )
    fun pagingSourceThreadsByFolder(folderId: Long): PagingSource<Int, ThreadSummaryTuple>

    @Query(
        """
        SELECT m.*
        FROM messages_meta m
        LEFT JOIN message_local_state s ON s.meta_id = m.id
        WHERE m.folder_id = :folderId
          AND COALESCE(s.is_archived, 0) = 0
          AND COALESCE(s.hidden_from_folder, 0) = 0
          AND (:onlyUnread = 0 OR m.is_read = 0)
          AND (:onlyFlagged = 0 OR m.is_flagged = 1)
          AND (:onlyAttachments = 0 OR m.has_attachments = 1)
        ORDER BY m.received_at DESC
        """,
    )
    fun pagingSourceFlatFiltered(
        folderId: Long,
        onlyUnread: Int,
        onlyFlagged: Int,
        onlyAttachments: Int,
    ): PagingSource<Int, MessageMetaEntity>

    @Query(
        """
        SELECT
            m.id AS latestMetaId,
            COALESCE(m.thread_id, 'id:' || m.id) AS threadKey,
            m.account_id AS accountId,
            m.folder_id AS folderId,
            m.sender_name AS senderName,
            m.sender_address AS senderAddress,
            m.subject AS subject,
            m.snippet AS snippet,
            MAX(m.received_at) AS latestReceivedAt,
            COUNT(*) AS messageCount,
            SUM(CASE WHEN m.is_read = 0 THEN 1 ELSE 0 END) AS unreadCount,
            MAX(m.is_flagged) AS hasFlagged,
            MAX(m.has_attachments) AS hasAttachments
        FROM messages_meta m
        LEFT JOIN message_local_state s ON s.meta_id = m.id
        WHERE m.folder_id = :folderId
          AND COALESCE(s.is_archived, 0) = 0
          AND COALESCE(s.hidden_from_folder, 0) = 0
        GROUP BY COALESCE(m.thread_id, 'id:' || m.id)
        HAVING (:onlyUnread = 0 OR SUM(CASE WHEN m.is_read = 0 THEN 1 ELSE 0 END) > 0)
           AND (:onlyFlagged = 0 OR MAX(m.is_flagged) = 1)
           AND (:onlyAttachments = 0 OR MAX(m.has_attachments) = 1)
        ORDER BY latestReceivedAt DESC
        """,
    )
    fun pagingSourceThreadsFiltered(
        folderId: Long,
        onlyUnread: Int,
        onlyFlagged: Int,
        onlyAttachments: Int,
    ): PagingSource<Int, ThreadSummaryTuple>

    @Query(
        """
        SELECT m.*
        FROM messages_meta m
        INNER JOIN message_local_state s ON s.meta_id = m.id
        WHERE m.account_id = :accountId AND s.is_archived = 1
        ORDER BY m.received_at DESC
        """,
    )
    fun pagingSourceLocallyArchived(accountId: Long): PagingSource<Int, MessageMetaEntity>

    @Query(
        """
        SELECT m.*
        FROM messages_meta m
        LEFT JOIN message_local_state s ON s.meta_id = m.id
        WHERE m.folder_id = :folderId
          AND COALESCE(s.is_archived, 0) = 0
          AND COALESCE(s.hidden_from_folder, 0) = 0
          AND (:onlyUnread = 0 OR m.is_read = 0)
          AND (:onlyFlagged = 0 OR m.is_flagged = 1)
          AND (:onlyAttachments = 0 OR m.has_attachments = 1)
        ORDER BY m.received_at DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun observeFlatPage(
        folderId: Long,
        onlyUnread: Int,
        onlyFlagged: Int,
        onlyAttachments: Int,
        limit: Int,
        offset: Int,
    ): Flow<List<MessageMetaEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM messages_meta m
        LEFT JOIN message_local_state s ON s.meta_id = m.id
        WHERE m.folder_id = :folderId
          AND COALESCE(s.is_archived, 0) = 0
          AND COALESCE(s.hidden_from_folder, 0) = 0
          AND (:onlyUnread = 0 OR m.is_read = 0)
          AND (:onlyFlagged = 0 OR m.is_flagged = 1)
          AND (:onlyAttachments = 0 OR m.has_attachments = 1)
        """,
    )
    fun observeFlatCount(
        folderId: Long,
        onlyUnread: Int,
        onlyFlagged: Int,
        onlyAttachments: Int,
    ): Flow<Int>

    @Query(
        """
        SELECT
            m.id AS latestMetaId,
            COALESCE(m.thread_id, 'id:' || m.id) AS threadKey,
            m.account_id AS accountId,
            m.folder_id AS folderId,
            m.sender_name AS senderName,
            m.sender_address AS senderAddress,
            m.subject AS subject,
            m.snippet AS snippet,
            MAX(m.received_at) AS latestReceivedAt,
            COUNT(*) AS messageCount,
            SUM(CASE WHEN m.is_read = 0 THEN 1 ELSE 0 END) AS unreadCount,
            MAX(m.is_flagged) AS hasFlagged,
            MAX(m.has_attachments) AS hasAttachments
        FROM messages_meta m
        LEFT JOIN message_local_state s ON s.meta_id = m.id
        WHERE m.folder_id = :folderId
          AND COALESCE(s.is_archived, 0) = 0
          AND COALESCE(s.hidden_from_folder, 0) = 0
        GROUP BY COALESCE(m.thread_id, 'id:' || m.id)
        HAVING (:onlyUnread = 0 OR SUM(CASE WHEN m.is_read = 0 THEN 1 ELSE 0 END) > 0)
           AND (:onlyFlagged = 0 OR MAX(m.is_flagged) = 1)
           AND (:onlyAttachments = 0 OR MAX(m.has_attachments) = 1)
        ORDER BY latestReceivedAt DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun observeThreadPage(
        folderId: Long,
        onlyUnread: Int,
        onlyFlagged: Int,
        onlyAttachments: Int,
        limit: Int,
        offset: Int,
    ): Flow<List<ThreadSummaryTuple>>

    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT 1
            FROM messages_meta m
            LEFT JOIN message_local_state s ON s.meta_id = m.id
            WHERE m.folder_id = :folderId
              AND COALESCE(s.is_archived, 0) = 0
              AND COALESCE(s.hidden_from_folder, 0) = 0
            GROUP BY COALESCE(m.thread_id, 'id:' || m.id)
            HAVING (:onlyUnread = 0 OR SUM(CASE WHEN m.is_read = 0 THEN 1 ELSE 0 END) > 0)
               AND (:onlyFlagged = 0 OR MAX(m.is_flagged) = 1)
               AND (:onlyAttachments = 0 OR MAX(m.has_attachments) = 1)
        )
        """,
    )
    fun observeThreadCount(
        folderId: Long,
        onlyUnread: Int,
        onlyFlagged: Int,
        onlyAttachments: Int,
    ): Flow<Int>

    @Query(
        """
        SELECT m.*
        FROM messages_meta m
        INNER JOIN message_local_state s ON s.meta_id = m.id
        WHERE m.account_id = :accountId
          AND COALESCE(s.is_archived, 0) = 0
          AND s.local_category = :categoryName
          AND (:onlyUnread = 0 OR m.is_read = 0)
          AND (:onlyFlagged = 0 OR m.is_flagged = 1)
          AND (:onlyAttachments = 0 OR m.has_attachments = 1)
        ORDER BY m.received_at DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun observeLocalCategoryFlatPage(
        accountId: Long,
        categoryName: String,
        onlyUnread: Int,
        onlyFlagged: Int,
        onlyAttachments: Int,
        limit: Int,
        offset: Int,
    ): Flow<List<MessageMetaEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM messages_meta m
        INNER JOIN message_local_state s ON s.meta_id = m.id
        WHERE m.account_id = :accountId
          AND COALESCE(s.is_archived, 0) = 0
          AND s.local_category = :categoryName
          AND (:onlyUnread = 0 OR m.is_read = 0)
          AND (:onlyFlagged = 0 OR m.is_flagged = 1)
          AND (:onlyAttachments = 0 OR m.has_attachments = 1)
        """,
    )
    fun observeLocalCategoryFlatCount(
        accountId: Long,
        categoryName: String,
        onlyUnread: Int,
        onlyFlagged: Int,
        onlyAttachments: Int,
    ): Flow<Int>

    @Query(
        """
        SELECT
            m.id AS latestMetaId,
            COALESCE(m.thread_id, 'id:' || m.id) AS threadKey,
            m.account_id AS accountId,
            m.folder_id AS folderId,
            m.sender_name AS senderName,
            m.sender_address AS senderAddress,
            m.subject AS subject,
            m.snippet AS snippet,
            MAX(m.received_at) AS latestReceivedAt,
            COUNT(*) AS messageCount,
            SUM(CASE WHEN m.is_read = 0 THEN 1 ELSE 0 END) AS unreadCount,
            MAX(m.is_flagged) AS hasFlagged,
            MAX(m.has_attachments) AS hasAttachments
        FROM messages_meta m
        INNER JOIN message_local_state s ON s.meta_id = m.id
        WHERE m.account_id = :accountId
          AND COALESCE(s.is_archived, 0) = 0
          AND s.local_category = :categoryName
        GROUP BY COALESCE(m.thread_id, 'id:' || m.id)
        HAVING (:onlyUnread = 0 OR SUM(CASE WHEN m.is_read = 0 THEN 1 ELSE 0 END) > 0)
           AND (:onlyFlagged = 0 OR MAX(m.is_flagged) = 1)
           AND (:onlyAttachments = 0 OR MAX(m.has_attachments) = 1)
        ORDER BY latestReceivedAt DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun observeLocalCategoryThreadPage(
        accountId: Long,
        categoryName: String,
        onlyUnread: Int,
        onlyFlagged: Int,
        onlyAttachments: Int,
        limit: Int,
        offset: Int,
    ): Flow<List<ThreadSummaryTuple>>

    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT 1
            FROM messages_meta m
            INNER JOIN message_local_state s ON s.meta_id = m.id
            WHERE m.account_id = :accountId
              AND COALESCE(s.is_archived, 0) = 0
              AND s.local_category = :categoryName
            GROUP BY COALESCE(m.thread_id, 'id:' || m.id)
            HAVING (:onlyUnread = 0 OR SUM(CASE WHEN m.is_read = 0 THEN 1 ELSE 0 END) > 0)
               AND (:onlyFlagged = 0 OR MAX(m.is_flagged) = 1)
               AND (:onlyAttachments = 0 OR MAX(m.has_attachments) = 1)
        )
        """,
    )
    fun observeLocalCategoryThreadCount(
        accountId: Long,
        categoryName: String,
        onlyUnread: Int,
        onlyFlagged: Int,
        onlyAttachments: Int,
    ): Flow<Int>

    @Query(
        """
        SELECT m.*
        FROM messages_meta m
        INNER JOIN message_local_state s ON s.meta_id = m.id
        WHERE m.account_id = :accountId AND s.is_archived = 1
        ORDER BY m.received_at DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun observeArchivedPage(
        accountId: Long,
        limit: Int,
        offset: Int,
    ): Flow<List<MessageMetaEntity>>

    @Query(
        """
        SELECT * FROM messages_meta
        WHERE account_id = :accountId AND thread_id = :threadId
        ORDER BY received_at ASC
        """,
    )
    fun observeThreadMessages(accountId: Long, threadId: String): Flow<List<MessageMetaEntity>>

    @Query("SELECT * FROM messages_meta WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<MessageMetaEntity?>

    @Query(
        """
        SELECT * FROM messages_meta
        WHERE account_id = :accountId AND thread_id = :threadId
        ORDER BY received_at ASC
        """,
    )
    suspend fun getThreadMessages(accountId: Long, threadId: String): List<MessageMetaEntity>

    @Query("SELECT COUNT(*) FROM messages_meta")
    fun observeTotalMessageCount(): Flow<Int>

    @Query("SELECT account_id AS accountId, COUNT(*) AS count FROM messages_meta GROUP BY account_id")
    fun observeMessageCountsByAccount(): Flow<List<AccountMessageCountTuple>>

    @Query("UPDATE messages_meta SET is_read = :isRead WHERE id IN (:metaIds)")
    suspend fun updateReadStateBulk(metaIds: List<Long>, isRead: Boolean)

    @Query("DELETE FROM messages_fts WHERE meta_id IN (SELECT id FROM messages_meta WHERE account_id = :accountId)")
    suspend fun deleteFtsForAccount(accountId: Long)

    @Query("SELECT COUNT(*) FROM messages_meta WHERE folder_id = :folderId")
    suspend fun countByFolder(folderId: Long): Int

    @Query("SELECT COUNT(*) FROM messages_meta WHERE folder_id = :folderId AND is_read = 0")
    suspend fun countUnreadByFolder(folderId: Long): Int

    @Query("SELECT uid FROM messages_meta WHERE folder_id = :folderId AND uid IN (:uids)")
    suspend fun findExistingUids(folderId: Long, uids: List<Long>): List<Long>

    @Query("SELECT * FROM messages_meta WHERE folder_id = :folderId AND uid = :uid LIMIT 1")
    suspend fun getByFolderAndUid(folderId: Long, uid: Long): MessageMetaEntity?

    @Query("SELECT * FROM messages_meta WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MessageMetaEntity?

    @Query("SELECT * FROM messages_meta WHERE id IN (:metaIds)")
    suspend fun getByIds(metaIds: List<Long>): List<MessageMetaEntity>

    @Query(
        """
        SELECT * FROM messages_meta
        WHERE (:accountId <= 0 OR account_id = :accountId)
        ORDER BY received_at DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentMessagesForPicker(
        accountId: Long,
        limit: Int = 50,
    ): List<MessageMetaEntity>

    @Query(
        """
        SELECT * FROM messages_meta
        WHERE (:accountId <= 0 OR account_id = :accountId)
          AND (
              subject LIKE '%' || :likeQuery || '%'
              OR sender_name LIKE '%' || :likeQuery || '%'
              OR sender_address LIKE '%' || :likeQuery || '%'
              OR snippet LIKE '%' || :likeQuery || '%'
          )
        ORDER BY received_at DESC
        LIMIT :limit
        """,
    )
    suspend fun searchMessagesByMetadataLike(
        accountId: Long,
        likeQuery: String,
        limit: Int = 50,
    ): List<MessageMetaEntity>

    @Query("SELECT * FROM messages_meta WHERE account_id = :accountId AND message_id_header = :messageIdHeader LIMIT 1")
    suspend fun findByMessageId(accountId: Long, messageIdHeader: String): MessageMetaEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(meta: MessageMetaEntity): Long

    @Query(
        """
        UPDATE messages_meta
        SET is_read = :isRead,
            is_flagged = :isFlagged
        WHERE folder_id = :folderId AND uid = :uid
        """,
    )
    suspend fun updateFlagsByUid(
        folderId: Long,
        uid: Long,
        isRead: Boolean,
        isFlagged: Boolean,
    )

    @Query("UPDATE messages_meta SET is_read = :isRead WHERE id = :metaId")
    suspend fun updateReadState(metaId: Long, isRead: Boolean)

    @Query("UPDATE messages_meta SET is_flagged = :isFlagged WHERE id = :metaId")
    suspend fun updateFlaggedState(metaId: Long, isFlagged: Boolean)

    @Query("UPDATE messages_meta SET is_read = :isRead WHERE account_id = :accountId AND thread_id = :threadId")
    suspend fun updateThreadReadState(accountId: Long, threadId: String, isRead: Boolean)

    @Query(
        """
        UPDATE messages_meta
        SET folder_id = :newFolderId,
            uid = :newUid
        WHERE id = :metaId
        """,
    )
    suspend fun remapFolderAndUid(metaId: Long, newFolderId: Long, newUid: Long)

    @Query("SELECT * FROM messages_meta WHERE folder_id = :folderId ORDER BY received_at ASC LIMIT :limit OFFSET :offset")
    suspend fun listByFolderPaged(folderId: Long, limit: Int, offset: Int): List<MessageMetaEntity>

    @Query("SELECT MAX(uid) FROM messages_meta WHERE folder_id = :folderId")
    suspend fun findMaxUid(folderId: Long): Long?

    @Query("SELECT id FROM messages_meta WHERE folder_id = :folderId AND message_id_header = :messageIdHeader LIMIT 1")
    suspend fun findIdByMessageIdInFolder(folderId: Long, messageIdHeader: String): Long?

    @Query(
        """
        SELECT m.*
        FROM messages_meta m
        INNER JOIN folders f ON f.folder_id = m.folder_id
        LEFT JOIN message_local_state s ON s.meta_id = m.id
        WHERE m.account_id = :accountId
          AND UPPER(f.full_path) = 'INBOX'
          AND COALESCE(s.is_archived, 0) = 0
          AND m.id < :beforeId
        ORDER BY m.id DESC
        LIMIT :limit
        """,
    )
    suspend fun getAccountInboxMessagesBeforeId(
        accountId: Long,
        beforeId: Long,
        limit: Int,
    ): List<MessageMetaEntity>

    @Query("SELECT * FROM messages_meta WHERE subject LIKE '%=?%' OR sender_name LIKE '%=?%' LIMIT :limit")
    suspend fun findMessagesWithEncodedHeaders(limit: Int = 500): List<MessageMetaEntity>

    @Query("UPDATE messages_meta SET subject = :subject, sender_name = :senderName WHERE id = :metaId")
    suspend fun updateDecodedHeaders(metaId: Long, subject: String?, senderName: String?)
}

data class AccountMessageCountTuple(
    val accountId: Long,
    val count: Int,
)

data class ThreadSummaryTuple(
    val latestMetaId: Long,
    val threadKey: String,
    val accountId: Long,
    val folderId: Long,
    val senderName: String?,
    val senderAddress: String,
    val subject: String?,
    val snippet: String?,
    val latestReceivedAt: Long,
    val messageCount: Int,
    val unreadCount: Int,
    val hasFlagged: Boolean,
    val hasAttachments: Boolean,
)

@Dao
interface MessageBodyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(body: MessageBodyEntity)

    @Query("SELECT * FROM messages_body WHERE meta_id = :metaId LIMIT 1")
    suspend fun getByMetaId(metaId: Long): MessageBodyEntity?
}

@Dao
interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attachments: List<AttachmentEntity>)

    @Query("SELECT * FROM attachments WHERE meta_id = :metaId ORDER BY attachment_id ASC")
    suspend fun getByMetaId(metaId: Long): List<AttachmentEntity>

    @Query("DELETE FROM attachments WHERE meta_id = :metaId")
    suspend fun deleteForMeta(metaId: Long)

    @Query("UPDATE attachments SET local_file_path = :localPath WHERE attachment_id = :attachmentId")
    suspend fun updateLocalPath(attachmentId: Long, localPath: String)

    @Query("UPDATE attachments SET local_file_path = NULL WHERE local_file_path IS NOT NULL")
    suspend fun clearAllLocalPaths()
}

@Dao
interface MessageFtsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fts: MessageFtsEntity)

    @Query("DELETE FROM messages_fts WHERE meta_id = :metaId")
    suspend fun deleteByMetaId(metaId: Long)

    @RawQuery
    suspend fun search(query: RoomRawQuery): List<MessageMetaEntity>
}

@Dao
interface FolderSyncStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: FolderSyncStateEntity)

    @Query("SELECT * FROM folder_sync_state WHERE folder_id = :folderId LIMIT 1")
    suspend fun getByFolderId(folderId: Long): FolderSyncStateEntity?

    @Query("SELECT * FROM folder_sync_state")
    fun observeAll(): Flow<List<FolderSyncStateEntity>>
}

@Dao
interface AccountSecretDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(secret: AccountSecretEntity)

    @Query("SELECT * FROM account_secrets WHERE account_id = :accountId LIMIT 1")
    suspend fun getByAccountId(accountId: Long): AccountSecretEntity?
}

@Dao
interface MessageSyncFlagsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(flags: MessageSyncFlagsEntity)

    @Query("SELECT * FROM message_sync_flags WHERE meta_id = :metaId LIMIT 1")
    suspend fun getByMetaId(metaId: Long): MessageSyncFlagsEntity?
}

@Dao
interface MessageLocalStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: MessageLocalStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(states: List<MessageLocalStateEntity>)

    @Query("SELECT * FROM message_local_state WHERE meta_id = :metaId LIMIT 1")
    suspend fun getByMetaId(metaId: Long): MessageLocalStateEntity?

    @Query("SELECT * FROM message_local_state WHERE meta_id IN (:metaIds)")
    suspend fun getStatesByMetaIds(metaIds: List<Long>): List<MessageLocalStateEntity>

    @Query(
        """
        UPDATE message_local_state
        SET local_category = NULL,
            hidden_from_folder = 0
        WHERE is_archived = 0
          AND meta_id IN (SELECT id FROM messages_meta WHERE account_id = :accountId)
        """,
    )
    suspend fun clearNonArchivedLocalCategories(accountId: Long)

    @Query(
        """
        SELECT COUNT(*)
        FROM message_local_state s
        INNER JOIN messages_meta m ON m.id = s.meta_id
        WHERE m.account_id = :accountId AND s.is_archived = 1
        """,
    )
    fun observeLocallyArchivedCount(accountId: Long): Flow<Int>

    @Query(
        """
        SELECT
            s.local_category AS categoryName,
            COUNT(*) AS totalCount,
            COALESCE(SUM(CASE WHEN m.is_read = 0 THEN 1 ELSE 0 END), 0) AS unreadCount
        FROM message_local_state s
        INNER JOIN messages_meta m ON m.id = s.meta_id
        WHERE m.account_id = :accountId
          AND s.is_archived = 0
          AND s.local_category IS NOT NULL
          AND TRIM(s.local_category) != ''
        GROUP BY s.local_category
        ORDER BY s.local_category ASC
        """,
    )
    fun observeLocalCategorySummaries(accountId: Long): Flow<List<LocalCategorySummaryTuple>>
}

data class LocalCategorySummaryTuple(
    val categoryName: String,
    val totalCount: Int,
    val unreadCount: Int,
)

@Dao
interface LocalFilterRuleDao {
    @Query("SELECT * FROM local_filter_rules WHERE account_id = :accountId ORDER BY priority ASC, rule_id ASC")
    fun observeByAccount(accountId: Long): Flow<List<LocalFilterRuleEntity>>

    @Query("SELECT * FROM local_filter_rules WHERE account_id = :accountId AND is_enabled = 1 ORDER BY priority ASC, rule_id ASC")
    suspend fun getEnabledByAccount(accountId: Long): List<LocalFilterRuleEntity>

    @Query("SELECT * FROM local_filter_rules WHERE account_id = :accountId ORDER BY priority ASC, rule_id ASC")
    suspend fun getAllByAccount(accountId: Long): List<LocalFilterRuleEntity>

    @Query("SELECT * FROM local_filter_rules WHERE rule_id = :ruleId LIMIT 1")
    suspend fun getById(ruleId: Long): LocalFilterRuleEntity?

    @Query("SELECT MAX(priority) FROM local_filter_rules WHERE account_id = :accountId")
    suspend fun maxPriorityForAccount(accountId: Long): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: LocalFilterRuleEntity): Long

    @Query("UPDATE local_filter_rules SET is_enabled = :isEnabled, updated_at = :updatedAt WHERE rule_id = :ruleId")
    suspend fun updateEnabled(ruleId: Long, isEnabled: Boolean, updatedAt: Long)

    @Query("UPDATE local_filter_rules SET priority = :priority, updated_at = :updatedAt WHERE rule_id = :ruleId")
    suspend fun updatePriority(ruleId: Long, priority: Int, updatedAt: Long)

    @Query("DELETE FROM local_filter_rules WHERE rule_id = :ruleId")
    suspend fun deleteById(ruleId: Long)
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts WHERE account_id = :accountId ORDER BY updated_at DESC")
    fun observeByAccount(accountId: Long): Flow<List<DraftEntity>>

    @Query("SELECT COUNT(*) FROM drafts WHERE account_id = :accountId")
    fun observeCountByAccount(accountId: Long): Flow<Int>

    @Query("SELECT * FROM drafts WHERE draft_id = :draftId LIMIT 1")
    suspend fun getById(draftId: Long): DraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: DraftEntity): Long

    @Query("DELETE FROM drafts WHERE draft_id = :draftId")
    suspend fun deleteById(draftId: Long)
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox WHERE account_id = :accountId ORDER BY created_at DESC")
    fun observeByAccount(accountId: Long): Flow<List<OutboxMessageEntity>>

    @Query("SELECT COUNT(*) FROM outbox WHERE account_id = :accountId AND status IN ('QUEUED', 'SENDING', 'FAILED')")
    fun observePendingCountByAccount(accountId: Long): Flow<Int>

    @Query("SELECT * FROM outbox WHERE outbox_id = :outboxId LIMIT 1")
    suspend fun getById(outboxId: Long): OutboxMessageEntity?

    @Query("SELECT * FROM outbox WHERE status = 'QUEUED' ORDER BY created_at ASC")
    suspend fun getAllQueued(): List<OutboxMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: OutboxMessageEntity): Long

    @Query(
        """
        UPDATE outbox
        SET status = 'SENDING',
            updated_at = :nowMs
        WHERE outbox_id = :outboxId AND status = 'QUEUED'
        """,
    )
    suspend fun markSending(outboxId: Long, nowMs: Long): Int

    @Query(
        """
        UPDATE outbox
        SET status = 'SENT',
            message_id_header = :messageIdHeader,
            last_error = NULL,
            sent_at = :sentAtMs,
            updated_at = :sentAtMs
        WHERE outbox_id = :outboxId
        """,
    )
    suspend fun markSent(outboxId: Long, messageIdHeader: String, sentAtMs: Long)

    @Query(
        """
        UPDATE outbox
        SET status = :status,
            attempt_count = attempt_count + 1,
            last_error = :errorMessage,
            updated_at = :nowMs
        WHERE outbox_id = :outboxId
        """,
    )
    suspend fun markAttemptResult(
        outboxId: Long,
        status: String,
        errorMessage: String?,
        nowMs: Long,
    )

    @Query(
        """
        UPDATE outbox
        SET status = 'QUEUED',
            last_error = NULL,
            updated_at = :nowMs
        WHERE outbox_id = :outboxId
        """,
    )
    suspend fun resetToQueued(outboxId: Long, nowMs: Long)

    @Query("DELETE FROM outbox WHERE outbox_id = :outboxId")
    suspend fun deleteById(outboxId: Long)
}

@Dao
interface AiProviderProfileDao {
    @Query("SELECT * FROM ai_provider_profiles ORDER BY is_default DESC, updated_at DESC, profile_id ASC")
    fun observeAll(): Flow<List<AiProviderProfileEntity>>

    @Query("SELECT * FROM ai_provider_profiles WHERE is_default = 1 LIMIT 1")
    fun observeDefaultProfile(): Flow<AiProviderProfileEntity?>

    @Query("SELECT * FROM ai_provider_profiles ORDER BY is_default DESC, updated_at DESC, profile_id ASC")
    suspend fun getAll(): List<AiProviderProfileEntity>

    @Query("SELECT * FROM ai_provider_profiles WHERE profile_id = :profileId LIMIT 1")
    suspend fun getById(profileId: Long): AiProviderProfileEntity?

    @Query("SELECT * FROM ai_provider_profiles WHERE is_default = 1 LIMIT 1")
    suspend fun getDefaultProfile(): AiProviderProfileEntity?

    @Query("SELECT COUNT(*) FROM ai_provider_profiles")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: AiProviderProfileEntity): Long

    @Query("UPDATE ai_provider_profiles SET is_default = 0")
    suspend fun clearDefaultFlags()

    @Query("UPDATE ai_provider_profiles SET is_default = 1, updated_at = :nowMs WHERE profile_id = :profileId")
    suspend fun markAsDefault(profileId: Long, nowMs: Long)

    @Query(
        """
        UPDATE ai_provider_profiles
        SET last_test_status = :status,
            last_test_message = :message,
            last_tested_at = :testedAtMs,
            updated_at = :testedAtMs
        WHERE profile_id = :profileId
        """,
    )
    suspend fun updateTestResult(
        profileId: Long,
        status: String,
        message: String?,
        testedAtMs: Long,
    )

    @Query("DELETE FROM ai_provider_profiles WHERE profile_id = :profileId")
    suspend fun deleteById(profileId: Long)
}

@Dao
interface AiWritingPresetDao {
    @Query("SELECT * FROM ai_writing_presets ORDER BY sort_order ASC, preset_id ASC")
    fun observeAll(): Flow<List<AiWritingPresetEntity>>

    @Query("SELECT * FROM ai_writing_presets ORDER BY sort_order ASC, preset_id ASC")
    suspend fun getAll(): List<AiWritingPresetEntity>

    @Query("SELECT COUNT(*) FROM ai_writing_presets")
    suspend fun count(): Int

    @Query("SELECT * FROM ai_writing_presets WHERE preset_id = :presetId LIMIT 1")
    suspend fun getById(presetId: Long): AiWritingPresetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: AiWritingPresetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(presets: List<AiWritingPresetEntity>)

    @Query("DELETE FROM ai_writing_presets WHERE preset_id = :presetId")
    suspend fun deleteById(presetId: Long)
}
