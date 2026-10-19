package dev.olaf.oscanner.core.data.db

import android.content.Context
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
    version = 1,
    exportSchema = true,
)
abstract class OscannerDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao
    abstract fun folders(): FolderDao

    companion object {
        fun create(context: Context): OscannerDatabase =
            Room.databaseBuilder(context, OscannerDatabase::class.java, "oscanner.db").build()
    }
}
