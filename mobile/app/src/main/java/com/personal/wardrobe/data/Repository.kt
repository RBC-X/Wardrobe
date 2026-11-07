package com.personal.wardrobe.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.personal.wardrobe.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.*
import java.time.Instant
import java.util.UUID
import java.util.zip.*

data class WardrobeSnapshot(val garments:List<Garment> = emptyList(),val profile:Profile=Profile(),val outfits:List<Outfit> = emptyList())
data class CropSuggestion(val left:Float,val top:Float,val right:Float,val bottom:Float)
data class ProviderCapability(val id:String="",val enabled:Boolean=false,val authenticated:Boolean=false,val text:Boolean=false,val vision:Boolean=false,val webSearch:Boolean=false,val imageGeneration:Boolean=false,val imageProbe:Boolean=false,val hasKey:Boolean=false,val config:JsonObject?=null,val reservedThisMonth:Double=0.0,val reason:String="Connect AI on your PC")
data class Job(val id:String="",val kind:String="",val provider:String="",val status:String="",val result:JsonObject?=null,val error:String="",val createdAt:String="",val updatedAt:String="")

class Repository(val context:Context){
    val gson=Gson();val connection=Connection(context);val api=ApiClient(connection);val photos=Photos(context);val secrets=Secrets(context);val phoneAi=PhoneAi(secrets);val chatGpt=ChatGpt(context,secrets);val onDevice=OnDevice(context,photos);private val weatherClient=WeatherClient();val productImport=ProductImport(photos)
    val database=Room.databaseBuilder(context,WardrobeDatabase::class.java,"wardrobe.db").build()
    val dao=database.dao();private val syncMutex=Mutex()
    val snapshot=dao.observeRecords().map{rows->WardrobeSnapshot(rows.filter{it.kind=="garment"&&!it.deleted}.map{gson.fromJson(it.payload,Garment::class.java)},rows.firstOrNull{it.kind=="profile"&&!it.deleted}?.let{gson.fromJson(it.payload,Profile::class.java)}?:Profile(),rows.filter{it.kind=="outfit"&&!it.deleted}.map{gson.fromJson(it.payload,Outfit::class.java)})}
    fun assetRules():Rules=context.assets.open("rules.json").bufferedReader().use{gson.fromJson(it,Rules::class.java)}
    suspend fun rules():Rules=dao.cache("rules")?.let{gson.fromJson(it,Rules::class.java)}?:assetRules()
    suspend fun trends():List<Trend> = dao.cache("trends")?.let{gson.fromJson(it,object:TypeToken<List<Trend>>(){}.type)}?:emptyList()
    suspend fun weather():WeatherNow?=dao.cache("weather")?.let{runCatching{gson.fromJson(it,WeatherNow::class.java)}.getOrNull()}
    suspend fun weatherPlace():String=dao.cache("weatherPlace")?:""
    /** Keyless and phone-only, so outfit ranking still works while the PC is asleep. */
    suspend fun refreshWeather(place:String):WeatherNow=withContext(Dispatchers.IO){val result=weatherClient.forecast(place);dao.cache(CacheEntity("weather",gson.toJson(result)));dao.cache(CacheEntity("weatherPlace",place.trim()));result}
    suspend fun analyzeOnPhone(item:Garment,provider:String):JsonObject {
        require(item.imageIds.isNotEmpty()){"Add a photo first"}
        val cutout=item.cutoutId.takeIf{it.isNotEmpty()}?.let{runCatching{photos.file(it)}.getOrNull()}
        val file=cutout?.takeIf{it.exists()}?:photos.file(item.imageIds.first())
        require(file.exists()){"This photo is still transferring"}
        val excluded=cutout!=null&&cutout.exists()&&file.path==cutout.path
        if(provider=="chatgpt")return chatGpt.analyze(item,file,rules(),excluded)
        return phoneAi.analyze(item,file,rules(),provider,backgroundExcluded=excluded)
    }
    /** Background-free detection on this phone: no key, no network and no PC. */
    suspend fun detectOnDevice(item:Garment):JsonObject {
        require(item.imageIds.isNotEmpty()){"Add a photo first"}
        val file=photos.file(item.imageIds.first())
        require(file.exists()){"This photo is still transferring"}
        return onDevice.detect(file,item.id,item.revision,item.imageIds.first())
    }
    suspend fun cachedJobs():List<Job> = dao.cache("jobs")?.let{gson.fromJson(it,object:TypeToken<List<Job>>(){}.type)}?:emptyList()
    suspend fun feeds():JsonArray=dao.cache("feeds")?.let{JsonParser.parseString(it).asJsonArray}?:JsonArray()
    suspend fun lastRefresh():String=dao.cache("lastRefresh")?:"Never refreshed"
    private suspend fun save(kind:String,id:String,payload:Any,deleted:Boolean=false){
        database.withTransaction{val old=dao.record(kind,id);val json=gson.toJsonTree(payload).asJsonObject;val revision=(old?.revision?:0)+1;json.addProperty("revision",revision);val text=gson.toJson(json)
            dao.upsert(RecordEntity(kind,id,revision,text,deleted));dao.queue(ChangeEntity(UUID.randomUUID().toString(),kind,id,revision,deleted,text))}
    }
    suspend fun saveGarment(garment:Garment){require(garment.name.isNotBlank()){"Give this piece a name"};require(garment.name.length<=200){"Keep the name under 200 characters"};require(garment.category in assetRules().categories);require(Regex("#[0-9a-fA-F]{6}").matches(garment.color)){"Use a six-digit hex colour"};save("garment",garment.id,garment.copy(shape=garment.shape.take(80),updatedAt=Instant.now().toString()))}
    suspend fun deleteGarment(garment:Garment)=save("garment",garment.id,garment.copy(deleted=true,updatedAt=Instant.now().toString()),true)
    suspend fun saveProfile(profile:Profile)=save("profile",profile.id,profile)
    suspend fun saveOutfit(outfit:Outfit)=save("outfit",outfit.id,outfit)
    suspend fun deleteOutfit(outfit:Outfit)=save("outfit",outfit.id,outfit,true)
    suspend fun pair(value:String){val data=JsonParser.parseString(value).asJsonObject;require(data["version"].asInt==1){"Unsupported pairing code"};val url=api.validateUrl(data["url"].asString);val result=api.json("/v1/pair","POST",mapOf("code" to data["code"].asString,"deviceName" to android.os.Build.MODEL),false,url);connection.url=url;connection.token=result["token"].asString;dao.resetUploads();dao.cache(CacheEntity("cursor","0"));for(row in dao.records())dao.queue(ChangeEntity(UUID.randomUUID().toString(),row.kind,row.id,row.revision,row.deleted,row.payload))}
    suspend fun capabilities():List<ProviderCapability>{val result=api.json("/v1/capabilities");return gson.fromJson(result["providers"],object:TypeToken<List<ProviderCapability>>(){}.type)}
    /** Ask this owner's PC to frame a new photo; null keeps the manual crop available offline. */
    suspend fun suggestCrop(bitmap:Bitmap):CropSuggestion?=withContext(Dispatchers.IO){
        // The bundled model already runs on the phone; the PC is only a fallback if it cannot load.
        runCatching{onDevice.suggestCrop(bitmap)}.getOrNull()?.let{return@withContext it}
        if(!connection.paired)return@withContext null
        runCatching{
            val scale=minOf(1f,1400f/maxOf(bitmap.width,bitmap.height))
            val sample=if(scale<1)Bitmap.createScaledBitmap(bitmap,(bitmap.width*scale).toInt(),(bitmap.height*scale).toInt(),true)else bitmap
            val output=ByteArrayOutputStream();sample.compress(Bitmap.CompressFormat.JPEG,85,output);if(sample!==bitmap)sample.recycle()
            val result=api.suggestCrop(output.toByteArray())
            if(result["found"]?.asBoolean!=true)null else CropSuggestion(result["left"].asFloat.coerceIn(0f,.4f),result["top"].asFloat.coerceIn(0f,.4f),result["right"].asFloat.coerceIn(0f,.4f),result["bottom"].asFloat.coerceIn(0f,.4f))
        }.getOrNull()
    }
    suspend fun providers():List<ProviderCapability>{val result=api.json("/v1/providers");return gson.fromJson(result["providers"],object:TypeToken<List<ProviderCapability>>(){}.type)}
    suspend fun configureProvider(id:String,values:Map<String,Any?>):ProviderCapability=gson.fromJson(api.json("/v1/providers/$id","PUT",values),ProviderCapability::class.java)
    suspend fun testProvider(id:String):ProviderCapability=gson.fromJson(api.json("/v1/providers/$id/test","POST"),ProviderCapability::class.java)
    suspend fun sync():String=syncMutex.withLock {
        withContext(Dispatchers.IO){
            require(connection.paired){"Pair your phone with your PC first"}
            do {
            val pending=dao.pending()
            val records=dao.records()
            val needed=mutableSetOf<String>()
            for(record in records){val value=JsonParser.parseString(record.payload).asJsonObject
                if(record.kind=="garment"&&!record.deleted){value["imageIds"]?.asJsonArray?.forEach{needed.add(it.asString)};value["cutoutId"]?.asString?.takeIf{it.isNotEmpty()}?.let{needed.add(it)}}
                if(record.kind=="profile")value["referenceImageId"]?.asString?.takeIf{it.isNotEmpty()}?.let{needed.add(it)}
                if(record.kind=="outfit")value["previewImageId"]?.asString?.takeIf{it.isNotEmpty()}?.let{needed.add(it)}}
            for(change in pending.filter{!it.deleted}){val value=JsonParser.parseString(change.payload).asJsonObject;value["imageIds"]?.asJsonArray?.forEach{needed.add(it.asString)};for(key in listOf("cutoutId","referenceImageId","previewImageId"))value[key]?.takeIf{!it.isJsonNull}?.asString?.takeIf{it.isNotEmpty()}?.let{needed.add(it)}}
            for(id in needed){val file=photos.file(id);require(file.exists()){ "A wardrobe image is missing from phone storage" };if(dao.cache("uploaded:$id")!="yes"){api.upload(id,file.readBytes());dao.cache(CacheEntity("uploaded:$id","yes"))}}
            val changes=pending.map{mapOf("changeId" to it.changeId,"kind" to it.kind,"recordId" to it.recordId,"revision" to it.revision,"deleted" to it.deleted,"payload" to JsonParser.parseString(it.payload))}
            val response=api.json("/v1/sync","POST",mapOf("cursor" to (dao.cache("cursor")?.toLongOrNull()?:0),"changes" to changes))
            val rulesText=response["rulesJson"].asString;require(photos.hash(rulesText.toByteArray())==response["rulesHash"].asString){"Styling database checksum failed"}
            val rules=gson.fromJson(rulesText,Rules::class.java);SyncProtocol.validateRules(rules,assetRules())
            val remote=response["records"].asJsonArray.map{e->val row=e.asJsonObject;RecordEntity(row["kind"].asString,row["recordId"].asString,row["revision"].asInt,gson.toJson(row["payload"]),row["deleted"].asBoolean)}
            val remoteImages=mutableSetOf<String>()
            for(row in remote){val value=JsonParser.parseString(row.payload).asJsonObject
                if(row.kind=="garment"&&!row.deleted){value["imageIds"]?.asJsonArray?.forEach{remoteImages.add(it.asString)};value["cutoutId"]?.asString?.takeIf{it.isNotEmpty()}?.let{remoteImages.add(it)}}
                if(row.kind=="profile")value["referenceImageId"]?.asString?.takeIf{it.isNotEmpty()}?.let{remoteImages.add(it)}
                if(row.kind=="outfit")value["previewImageId"]?.asString?.takeIf{it.isNotEmpty()}?.let{remoteImages.add(it)}}
            response["jobs"]?.asJsonArray?.forEach{j->j.asJsonObject["result"]?.takeIf{it.isJsonObject}?.asJsonObject?.get("imageId")?.takeIf{!it.isJsonNull}?.asString?.takeIf{it.isNotEmpty()}?.let{remoteImages.add(it)}}
            for(id in remoteImages)if(!photos.file(id).exists())photos.put(api.download(id),id)
            val accepted=response["accepted"].asJsonArray.map{it.asString};SyncProtocol.validateAcknowledgements(pending.map{it.changeId},accepted)
            database.withTransaction{
                dao.acknowledge(accepted)
                for(row in remote){val local=dao.record(row.kind,row.id);if(local==null||(row.revision>local.revision&&dao.dirty(row.kind,row.id)==0))dao.upsert(row)}
                dao.cache(CacheEntity("cursor",response["cursor"].asString));dao.cache(CacheEntity("rules",rulesText));dao.cache(CacheEntity("trends",gson.toJson(response["trends"])));dao.cache(CacheEntity("jobs",gson.toJson(response["jobs"])));dao.cache(CacheEntity("feeds",gson.toJson(response["feedItems"])));dao.cache(CacheEntity("lastRefresh",Instant.now().toString()))
            }
            } while(dao.pendingCount()>0)
            "Refreshed. ${dao.pendingCount()} changes still waiting."
        }
    }
    suspend fun submit(kind:String,provider:String,values:Map<String,Any>):Job {sync();val payload=mapOf("kind" to kind,"provider" to provider,"requestId" to UUID.randomUUID().toString())+values;val response=try{api.json("/v1/jobs","POST",payload)}catch(error:IOException){if(error is ApiException)throw error;api.json("/v1/jobs","POST",payload)};return gson.fromJson(response,Job::class.java)}
    suspend fun job(id:String):Job=gson.fromJson(api.json("/v1/jobs/$id"),Job::class.java)
    suspend fun cancelJob(id:String)=api.json("/v1/jobs/$id/cancel","POST")
    suspend fun product(url:String):JsonObject=productImport.product(url)
    suspend fun productImage(url:String):String=productImport.image(url)
    suspend fun styleOnPhone(outfit:Outfit,items:List<Garment>,profile:Profile,provider:String):JsonObject=if(provider=="chatgpt")chatGpt.style(outfit,items,profile) else phoneAi.style(outfit,items,profile,provider)
    fun localProviders():List<ProviderCapability> = phoneAi.providers()+chatGpt.providers()
    fun phoneAvailable(id:String)=phoneAi.available(id)||chatGpt.available(id)
    suspend fun exportBackup(uri:Uri)=withContext(Dispatchers.IO){
        context.contentResolver.openOutputStream(uri)?.use{stream->ZipOutputStream(stream).use{zip->val payload=mapOf("schemaVersion" to 1,"records" to dao.records(),"cache" to dao.caches().filter{it.key in setOf("rules","trends","feeds")});zip.putNextEntry(ZipEntry("wardrobe-phone.json"));zip.write(gson.toJson(payload).toByteArray());zip.closeEntry();for(file in photos.files()){zip.putNextEntry(ZipEntry("media/${file.name}"));file.inputStream().use{it.copyTo(zip)};zip.closeEntry()}}}?:throw IOException("Cannot write backup")
    }
    suspend fun restoreBackup(uri:Uri)=withContext(Dispatchers.IO){
        var metadata:JsonObject?=null;var total=0L
        context.contentResolver.openInputStream(uri)?.use{input->ZipInputStream(input).use{zip->while(true){val entry=zip.nextEntry?:break;val name=entry.name;require(name=="wardrobe-phone.json"||Regex("media/[a-f0-9]{64}").matches(name)){"Invalid backup entry"};val output=ByteArrayOutputStream();val buffer=ByteArray(16384);var entrySize=0;while(true){val count=zip.read(buffer);if(count<0)break;entrySize+=count;total+=count;require(entrySize<=8_000_000&&total<=300_000_000){"Backup is too large"};output.write(buffer,0,count)};if(name=="wardrobe-phone.json")metadata=JsonParser.parseString(output.toString("UTF-8")).asJsonObject else photos.put(output.toByteArray(),name.substringAfter('/'));zip.closeEntry()}}}?:throw IOException("Cannot open backup")
        val value=metadata?:throw IllegalArgumentException("Wardrobe metadata is missing");require(value["schemaVersion"].asInt==1){"Unsupported backup version"}
        val restored=value["records"].asJsonArray.map{gson.fromJson(it,RecordEntity::class.java)}
        for(row in restored){require(row.kind in setOf("garment","profile","outfit")&&Regex("[A-Za-z0-9_-]{1,80}").matches(row.id));val json=JsonParser.parseString(row.payload).asJsonObject;require(json["id"].asString==row.id);if(row.kind=="garment"){val garment=gson.fromJson(row.payload,Garment::class.java);require(garment.category in assetRules().categories&&Regex("#[0-9a-fA-F]{6}").matches(garment.color));(garment.imageIds+listOf(garment.cutoutId).filter{it.isNotEmpty()}).forEach{require(photos.file(it).exists()){ "Backup photo is missing" }}}}
        database.withTransaction{for(row in restored){val old=dao.record(row.kind,row.id);val revision=maxOf(row.revision,(old?.revision?:0)+1);val json=JsonParser.parseString(row.payload).asJsonObject;json.addProperty("revision",revision);val payload=gson.toJson(json);dao.upsert(row.copy(revision=revision,payload=payload));dao.queue(ChangeEntity(UUID.randomUUID().toString(),row.kind,row.id,revision,row.deleted,payload))};value["cache"]?.asJsonArray?.forEach{val cached=gson.fromJson(it,CacheEntity::class.java);if(cached.key=="rules"){SyncProtocol.validateRules(gson.fromJson(cached.value,Rules::class.java),assetRules());dao.cache(cached)}else if(cached.key in setOf("trends","feeds"))dao.cache(cached)};dao.cache(CacheEntity("cursor","0"))}
        "Restored ${restored.size} records. Your current PC connection is unchanged."
    }
}
