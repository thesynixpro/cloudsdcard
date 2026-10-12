package com.cloudsdcard.app.data

import android.content.Context
import androidx.room.*

@Entity(tableName = "cloud_files", indices = [Index("parentId"), Index("name")])
data class CloudFileEntity(
    @PrimaryKey val fileId: String,
    val name: String,
    val mimeType: String,
    val parentId: String?,
    val sizeBytes: Long?,
    val modifiedTime: String?,
    val isFolder: Boolean,
    val cachedLocalUri: String? = null,
    val pinnedOffline: Boolean = false,
    val lastAccessedTime: Long? = null
)

@Entity(tableName = "transfers", indices = [Index("status"), Index("createdAt")])
data class TransferEntity(
    @PrimaryKey val transferId: String,
    val fileName: String,
    val direction: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val errorMessage: String? = null
)

@Dao interface CloudFileDao {
    @Query("SELECT * FROM cloud_files ORDER BY name COLLATE NOCASE") fun observeAll(): kotlinx.coroutines.flow.Flow<List<CloudFileEntity>>
    @Query("SELECT * FROM cloud_files WHERE parentId IS :parentId ORDER BY isFolder DESC, name COLLATE NOCASE") suspend fun children(parentId: String?): List<CloudFileEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(files: List<CloudFileEntity>)
    @Query("DELETE FROM cloud_files") suspend fun clear()
    @Query("DELETE FROM cloud_files WHERE fileId = :id") suspend fun delete(id: String)
}

@Dao interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY createdAt DESC") fun observeAll(): kotlinx.coroutines.flow.Flow<List<TransferEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: TransferEntity)
    @Query("DELETE FROM transfers WHERE transferId = :id") suspend fun delete(id: String)
}

@Database(entities = [CloudFileEntity::class, TransferEntity::class], version = 1, exportSchema = true)
abstract class CloudDatabase : RoomDatabase() {
    abstract fun cloudFileDao(): CloudFileDao
    abstract fun transferDao(): TransferDao
    companion object {
        @Volatile private var INSTANCE: CloudDatabase? = null
        fun get(context: Context): CloudDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, CloudDatabase::class.java, "cloud-sd-card.db").build().also { INSTANCE = it }
        }
    }
}
