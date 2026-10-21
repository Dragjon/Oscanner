package dev.olaf.oscanner.core.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FolderEntity::class,
        DocumentEntity::class,
        PageEntity::class,
        PageFts::class,
        TagEntity::class,
        DocumentTagCrossRef::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class OscannerDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao
    abstract fun folders(): FolderDao
    abstract fun pages(): PageDao
    abstract fun tags(): TagDao

    companion object {
        fun create(context: Context): OscannerDatabase =
            Room.databaseBuilder(context, OscannerDatabase::class.java, "oscanner.db").build()
    }
}
