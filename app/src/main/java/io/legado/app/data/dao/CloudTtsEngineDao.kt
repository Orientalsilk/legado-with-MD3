package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.CloudTtsEngineEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudTtsEngineDao {
    @Query("select * from cloud_tts_engines order by enabled desc, name collate nocase")
    fun observeAll(): Flow<List<CloudTtsEngineEntity>>

    @Query("select * from cloud_tts_engines order by enabled desc, name collate nocase")
    suspend fun getAll(): List<CloudTtsEngineEntity>

    @Query("select * from cloud_tts_engines where id = :id limit 1")
    suspend fun get(id: String): CloudTtsEngineEntity?

    /** Small synchronous projection for audio cache keys; avoids loading large options JSON. */
    @Query("select updatedAt from cloud_tts_engines where id = :id limit 1")
    fun getUpdatedAt(id: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(engine: CloudTtsEngineEntity)

    @Delete
    suspend fun delete(engine: CloudTtsEngineEntity)
}
