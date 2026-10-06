package com.personal.wardrobe.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.*
import com.google.gson.*
import com.personal.wardrobe.data.*
import com.personal.wardrobe.data.Job
import com.personal.wardrobe.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.Instant
import java.util.UUID

class WardrobeViewModel(val repository:Repository):ViewModel(){
    val snapshot=repository.snapshot.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),WardrobeSnapshot())
    val busy=MutableStateFlow(false);val message=MutableStateFlow("");val capabilities=MutableStateFlow<List<ProviderCapability>>(emptyList());val jobs=MutableStateFlow<List<Job>>(emptyList())
    val draft=MutableStateFlow<Garment?>(null);val profileDraft=MutableStateFlow<Profile?>(null);val generated=MutableStateFlow<List<Outfit>>(emptyList());val trends=MutableStateFlow<List<Trend>>(emptyList());val feeds=MutableStateFlow(JsonArray());val lastRefresh=MutableStateFlow("Never refreshed")
    val mode=MutableStateFlow("balanced");val occasion=MutableStateFlow("any");val weather=MutableStateFlow("mild");val pinned=MutableStateFlow("");val season=MutableStateFlow("all");val connectionState=MutableStateFlow(if(repository.connection.paired)"Saved connection · refresh to check" else "Not paired")
    val productReview=MutableStateFlow<JsonObject?>(null);val analysisReview=MutableStateFlow<Pair<Garment,JsonObject>?>(null);val preview=MutableStateFlow<String?>(null);val collage=MutableStateFlow<File?>(null);val searchProducts=MutableStateFlow(JsonArray())
    val advice=MutableStateFlow("")
    var engine=StylingEngine(repository.assetRules());private var polling: kotlinx.coroutines.Job?=null
    init{viewModelScope.launch{reloadCache()};viewModelScope.launch{combine(combine(snapshot,mode,occasion,weather,pinned){data,mode,occasion,weather,pinned->arrayOf(data,mode,occasion,weather,pinned)},season){values,season->values+season}.collectLatest{values->val data=values[0] as WardrobeSnapshot;generated.value=withContext(Dispatchers.Default){engine.generate(data.garments,data.profile,trends.value,values[1] as String,values[2] as String,values[3] as String,values[4] as String,season=values[5] as String)}}}}
    private suspend fun reloadCache(){engine=StylingEngine(repository.rules());trends.value=repository.trends();feeds.value=repository.feeds();jobs.value=repository.cachedJobs();lastRefresh.value=repository.lastRefresh();regenerate()}
    private fun regenerate(){val data=snapshot.value;viewModelScope.launch{generated.value=withContext(Dispatchers.Default){engine.generate(data.garments,data.profile,trends.value,mode.value,occasion.value,weather.value,pinned.value,season=season.value)}}}
    fun action(block:suspend()->Unit){if(busy.value)return;viewModelScope.launch{busy.value=true;try{block()}catch(error:Exception){if(error is CancellationException)throw error;message.value=error.message?:"This action could not be completed"}finally{busy.value=false}}}
    fun edit(garment:Garment){draft.value=garment}
    fun newItem(){draft.value=Garment(UUID.randomUUID().toString(),"")}
    fun change(field:String,update:(Garment)->Garment){draft.value=draft.value?.let{update(it).copy(manualFields=(it.manualFields+field).distinct())}}
    fun saveDraft(){val garment=draft.value?:return;action{repository.saveGarment(garment);draft.value=null;message.value="Saved to your wardrobe"}}
    fun remove(garment:Garment){action{repository.deleteGarment(garment);draft.value=null;message.value="Item removed"}}
    fun refresh(){action{try{message.value=repository.sync();capabilities.value=repository.capabilities();connectionState.value="PC connected";reloadCache()}catch(error:Exception){if(error is CancellationException)throw error;connectionState.value=when{error is ApiException&&error.status in listOf(401,403)->"Authorization failed · pair this phone again";error.message?.contains("Pair",true)==true->"Pairing needs attention";error.message?.contains("resolve",true)==true->"Tailscale address unreachable · check Tailscale on both devices";else->"PC unavailable · check server and Tailscale"};if(repository.connection.paired&&!(error is ApiException&&error.status in listOf(401,403,422)))SyncWorker.queue(repository.context);throw error}}}
    fun pair(text:String){action{repository.pair(text);message.value=repository.sync();capabilities.value=repository.capabilities();connectionState.value="PC connected";reloadCache()}}
    fun disconnect(){repository.connection.disconnect();capabilities.value=emptyList();connectionState.value="Not paired";message.value="PC disconnected. Your wardrobe remains on your phone."}
    fun importPhoto(bitmap:Bitmap,left:Float,top:Float,right:Float,bottom:Float,reference:Boolean){action{val result=withContext(Dispatchers.IO){repository.photos.import(bitmap,left,top,right,bottom)};if(reference){val profile=profileDraft.value?:snapshot.value.profile;profileDraft.value=profile.copy(referenceImageId=result.imageId)}else{val item=draft.value?:Garment(UUID.randomUUID().toString(),"");draft.value=item.copy(imageIds=(item.imageIds+result.imageId).take(8),color=if(item.imageIds.isEmpty()&&"color" !in item.manualFields)result.colors.firstOrNull()?:item.color else item.color,secondaryColors=result.colors.drop(1))}}}
    fun saveProfile(){profileDraft.value?.let{profile->action{repository.saveProfile(profile);profileDraft.value=null;message.value="Preferences saved"}}}
    fun saveOutfit(outfit:Outfit){action{repository.saveOutfit(outfit);message.value="Outfit saved"}}
    fun deleteOutfit(outfit:Outfit){action{repository.deleteOutfit(outfit)}}
    fun feedback(outfit:Outfit,like:Boolean){action{val p=snapshot.value.profile;repository.saveProfile(p.copy(likedItems=if(like)(p.likedItems+outfit.itemIds).distinct()else p.likedItems-outfit.itemIds.toSet(),dislikedItems=if(like)p.dislikedItems-outfit.itemIds.toSet()else(p.dislikedItems+outfit.itemIds).distinct()));message.value=if(like)"Your preferences will favor these pieces" else "Your preferences will use these pieces less"}}
    fun swap(outfit:Outfit,id:String){val current=snapshot.value.garments;val selected=outfit.itemIds.mapNotNull{x->current.find{it.id==x}};val target=selected.find{it.id==id}?:return
        val replacements=current.filter{it.category==target.category&&it.id!=id}.map{g->selected.map{if(it.id==id)g else it}}.filter{engine.valid(it,outfit.occasion,outfit.weather)}.map{engine.score(it,snapshot.value.profile,trends.value,outfit.mode,outfit.occasion,outfit.weather,outfit.season)}.sortedByDescending{it.score}
        if(replacements.isEmpty()){message.value="No available replacement in this category";return};if(snapshot.value.outfits.any{it.id==outfit.id})action{repository.saveOutfit(replacements.first().copy(name=outfit.name));repository.deleteOutfit(outfit)}else generated.value=generated.value.map{if(it.id==outfit.id)replacements.first()else it}
    }
    fun showCollage(outfit:Outfit){action{val items=outfit.itemIds.mapNotNull{id->snapshot.value.garments.find{it.id==id}};collage.value=withContext(Dispatchers.IO){repository.photos.collage(items)}}}
    fun importProduct(url:String){action{require(repository.connection.paired){"Pair your PC before importing a website"};productReview.value=repository.product(url)}}
    fun acceptProduct(product:JsonObject,imageUrl:String,confirmed:Boolean){action{require(confirmed){"Confirm this is your exact item and variant"};val id=if(imageUrl.isNotBlank())repository.productImage(imageUrl)else "";val item=draft.value?:return@action
        val name=product["name"]?.asString?:item.name;val brand=product["brand"]?.asString.orEmpty();val material=product["composition"]?.asString.orEmpty()
        draft.value=item.copy(name=name,brand=brand,brandConfirmed=brand.isNotBlank(),composition=material,compositionVerified=material.isNotBlank(),productUrl=product["url"].asString,imageIds=if(id.isNotEmpty())(item.imageIds+id).distinct().take(8)else item.imageIds,manualFields=(item.manualFields+listOf("name","brand","composition","productUrl")).distinct(),provenance=item.provenance+mapOf("product" to repository.gson.fromJson(product,Map::class.java)));productReview.value=null}}
    fun requestAnalysis(item:Garment,provider:String){action{val job=repository.submit("analysis",provider,mapOf("garmentId" to item.id,"revision" to item.revision,"imageIds" to item.imageIds.take(3),"consentPhotos" to true));track(job)}}
    fun requestStyle(outfit:Outfit,provider:String){action{track(repository.submit("style",provider,mapOf("itemIds" to outfit.itemIds,"occasion" to outfit.occasion,"weather" to outfit.weather,"mode" to outfit.mode)))}}
    fun requestPreview(outfit:Outfit,provider:String,ownPhoto:Boolean){action{track(repository.submit("preview",provider,mapOf("itemIds" to outfit.itemIds,"occasion" to outfit.occasion,"weather" to outfit.weather,"useOwnPhoto" to ownPhoto,"referenceImageId" to snapshot.value.profile.referenceImageId,"consentPhotos" to true)))}}
    fun requestSearch(gap:Gap,provider:String){action{track(repository.submit("search",provider,mapOf("query" to gap.title+". "+gap.reason)))}}
    fun requestCutout(item:Garment){action{require(item.imageIds.isNotEmpty()){ "Add a photo first" };track(repository.submit("cutout","local",mapOf("imageIds" to listOf(item.imageIds.first()),"garmentId" to item.id,"revision" to item.revision)))}}
    private fun track(job:Job){jobs.value=(listOf(job)+jobs.value.filter{it.id!=job.id}).take(50);message.value="Request queued on your PC";polling?.cancel();polling=viewModelScope.launch{var attempts=0;while(attempts++<100){delay(3000);try{val updated=repository.job(job.id);jobs.value=jobs.value.map{if(it.id==job.id)updated else it};if(updated.status in listOf("completed","failed","cancelled")){if(updated.status=="completed")processResult(updated)else message.value=updated.error.ifBlank{ "Request cancelled" };break}}catch(error:Exception){if(error is CancellationException)throw error;message.value="Request remains on your PC. Refresh when reconnected.";break}}}}
    private suspend fun processResult(job:Job){val result=job.result?:return
        if(job.kind in listOf("style","preview")){
            val revisions=result["itemRevisions"]?.takeIf{it.isJsonObject}?.asJsonObject
            require(revisions!=null&&revisions.size()>0&&revisions.entrySet().all{(id,revision)->snapshot.value.garments.any{it.id==id&&it.revision==revision.asInt}}){"This outfit changed; request fresh advice or a preview"}
        }
        when(job.kind){"analysis"->{val item=snapshot.value.garments.find{it.id==result["garmentId"].asString};if(item!=null)analysisReview.value=item to result}
            "preview"->{val id=result["imageId"].asString;if(!repository.photos.file(id).exists())withContext(Dispatchers.IO){repository.photos.put(repository.api.download(id),id)};preview.value=id;val ids=result["itemIds"]?.asJsonArray?.map{it.asString}?.toSet();val outfit=(snapshot.value.outfits+generated.value).find{it.itemIds.toSet()==ids};if(outfit!=null)repository.saveOutfit(outfit.copy(previewImageId=id))}
            "style"->advice.value=result["explanation"]?.asString?:"Advice received"
            "search"->searchProducts.value=result["products"]?.asJsonArray?:JsonArray()
            "cutout"->{val id=result["imageId"].asString;withContext(Dispatchers.IO){repository.photos.put(repository.api.download(id),id)};val source=result["sourceImageId"].asString;val garmentId=result["garmentId"]?.asString;val revision=result["revision"]?.asInt;val item=snapshot.value.garments.find{it.id==garmentId&&it.revision==revision&&source in it.imageIds};require(item!=null){ "This item changed; request a fresh cutout" };repository.saveGarment(item.copy(cutoutId=id))}}
        message.value=if(job.kind=="style")message.value else "Request completed"
    }
    fun acceptAnalysis(){val pair=analysisReview.value?:return;action{val current=snapshot.value.garments.find{it.id==pair.first.id}?:throw IllegalArgumentException("Item was removed");val result:Map<String,Any> = repository.gson.fromJson(pair.second,object:com.google.gson.reflect.TypeToken<Map<String,Any>>(){}.type);repository.saveGarment(engine.applyAnalysis(current,result));analysisReview.value=null;message.value="Suggestions applied; your manual edits were preserved"}}
    fun cancelJob(job:Job){action{repository.cancelJob(job.id);jobs.value=jobs.value.map{if(it.id==job.id)it.copy(status="cancelled")else it}}}
    fun openJob(job:Job){action{processResult(job)}}
    fun backup(uri:Uri){action{repository.exportBackup(uri);message.value="Backup saved without connection credentials"}}
    fun restore(uri:Uri){action{message.value=repository.restoreBackup(uri);reloadCache()}}
    class Factory(private val repository:Repository):ViewModelProvider.Factory{override fun<T:ViewModel>create(modelClass:Class<T>):T{@Suppress("UNCHECKED_CAST")return WardrobeViewModel(repository)as T}}
}
