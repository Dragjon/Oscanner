package dev.olaf.oscanner.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("folderId"), Index("deletedAt")],
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val folderId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** [dev.olaf.oscanner.core.model.ProcessingState] name. */
    val state: String = "Idle",
    val note: String? = null,
    /** Set when moved to Trash; purged after 30 days. */
    val deletedAt: Long? = null,
    /** [dev.olaf.oscanner.core.model.DocumentKind] name. Added in schema 2. */
    @ColumnInfo(defaultValue = "Document") val kind: String = "Document",
    /**
     * Still has the name it was made with ("Scan …", "Import …"), so it may be named after its
     * heading once read. Cleared by any rename. Added in schema 3; older documents keep theirs.
     */
    @ColumnInfo(defaultValue = "0") val autoTitle: Boolean = false,
)

/**
 * One page. [originalPath] is never overwritten; [editsJson] is the edit recipe
 * (quad, filter, rotation; erase masks later, see EditsJson) and [renderPath] is a rebuildable cache of it.
 */
@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId")],
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val position: Int,
    val originalPath: String,
    val renderPath: String? = null,
    val thumbPath: String? = null,
    val width: Int,
    val height: Int,
    val sizeBytes: Long = 0,
    val editsJson: String = "{}",
    val ocrText: String? = null,
    /** Marked as answered wrong (a test paper's question), to practise again. */
    @ColumnInfo(defaultValue = "0") val wrong: Boolean = false,
)

/** Full-text index over recognized page text (filled in Phase 2). */
@Fts4(contentEntity = PageEntity::class)
@Entity(tableName = "pages_fts")
data class PageFts(
    @ColumnInfo(name = "ocrText") val ocrText: String?,
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(
    tableName = "document_tags",
    primaryKeys = ["documentId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class DocumentTagCrossRef(
    val documentId: Long,
    val tagId: Long,
)

/** Library row: a document joined with the aggregates of its pages. */
data class DocumentRow(
    val id: Long,
    val title: String,
    val folderId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val state: String,
    val deletedAt: Long?,
    val kind: String,
    val pageCount: Int,
    val sizeBytes: Long,
    val coverThumbPath: String?,
    val note: String?,
    /** Tag names joined with [TAG_SEPARATOR], in no set order; null without tags. */
    val tagNames: String?,
)

/** Unit separator: can't be typed into a tag name, so it's safe to join on. */
const val TAG_SEPARATOR = "\u001F"
