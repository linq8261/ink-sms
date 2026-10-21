package com.ink.sms.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.ink.sms.data.entity.RecycledMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface RecycleBinDao {

    @Insert
    suspend fun insert(msg: RecycledMessage): Long

    /** 按进入回收站时间倒序观察全部记录（回收站页实时刷新）。 */
    @Query("SELECT * FROM recycled_messages ORDER BY deletedAt DESC")
    fun observeAll(): Flow<List<RecycledMessage>>

    @Delete
    suspend fun delete(msg: RecycledMessage)

    /** 删除 deletedAt 早于 [cutoff] 的过期记录，返回删除条数。 */
    @Query("DELETE FROM recycled_messages WHERE deletedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}
