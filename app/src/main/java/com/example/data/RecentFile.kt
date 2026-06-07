package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recent_files")
data class RecentFile(
    @PrimaryKey val filePath: String,
    val fileName: String,
    val lastOpened: Long,
    val fileSize: Long
)

@Dao
interface RecentFileDao {
    @Query("SELECT * FROM recent_files ORDER BY lastOpened DESC")
    fun getAllRecentFiles(): Flow<List<RecentFile>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecentFile(recentFile: RecentFile)

    @Delete
    suspend fun deleteRecentFile(recentFile: RecentFile)

    @Query("DELETE FROM recent_files WHERE filePath = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM recent_files")
    suspend fun clearAll()
}

@Database(entities = [RecentFile::class], version = 1, exportSchema = false)
abstract class MarkdownDatabase : RoomDatabase() {
    abstract fun recentFileDao(): RecentFileDao

    companion object {
        @Volatile
        private var INSTANCE: MarkdownDatabase? = null

        fun getDatabase(context: android.content.Context): MarkdownDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MarkdownDatabase::class.java,
                    "markdown_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
