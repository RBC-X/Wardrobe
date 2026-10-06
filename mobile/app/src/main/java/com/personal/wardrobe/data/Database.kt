package com.personal.wardrobe.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="records",primaryKeys=["kind","id"])
data class RecordEntity(val kind:String,val id:String,val revision:Int,val payload:String,val deleted:Boolean=false)
@Entity(tableName="outbox")
data class ChangeEntity(@PrimaryKey val changeId:String,val kind:String,val recordId:String,val revision:Int,val deleted:Boolean,val payload:String)
@Entity(tableName="cache")
data class CacheEntity(@PrimaryKey val key:String,val value:String)
@Dao
interface WardrobeDao {
    @Query("SELECT * FROM records") fun observeRecords():Flow<List<RecordEntity>>
    @Query("SELECT * FROM records") suspend fun records():List<RecordEntity>
    @Query("SELECT * FROM records WHERE kind=:kind AND id=:id") suspend fun record(kind:String,id:String):RecordEntity?
    @Upsert suspend fun upsert(record:RecordEntity)
    @Upsert suspend fun upsertMany(records:List<RecordEntity>)
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun queue(change:ChangeEntity)
    @Query("SELECT * FROM outbox ORDER BY rowid LIMIT 300") suspend fun pending():List<ChangeEntity>
    @Query("SELECT COUNT(*) FROM outbox") suspend fun pendingCount():Int
    @Query("SELECT COUNT(*) FROM outbox WHERE kind=:kind AND recordId=:id") suspend fun dirty(kind:String,id:String):Int
    @Query("DELETE FROM outbox WHERE changeId IN (:ids)") suspend fun acknowledge(ids:List<String>)
    @Query("SELECT value FROM cache WHERE `key`=:key") suspend fun cache(key:String):String?
    @Upsert suspend fun cache(value:CacheEntity)
    @Query("SELECT * FROM cache") suspend fun caches():List<CacheEntity>
    @Query("DELETE FROM cache WHERE `key` LIKE 'uploaded:%'") suspend fun resetUploads()
}
@Database(entities=[RecordEntity::class,ChangeEntity::class,CacheEntity::class],version=1,exportSchema=true)
abstract class WardrobeDatabase:RoomDatabase(){abstract fun dao():WardrobeDao}
