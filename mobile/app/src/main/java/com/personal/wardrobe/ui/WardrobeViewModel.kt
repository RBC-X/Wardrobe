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

data class ReviewState(val item:Garment,val suggestion:Garment,val cutoutId:String="",val confidence:Double=0.0,val backgroundExcluded:Boolean=false,val source:String="detect")

private val OpenGreetings=listOf(
    "What do we plan on wearing today?",
    "Let's start with the weather.",
    "What feels right for today?",
    "A good day begins with a good outfit.",
    "Let's make getting dressed the easy part.",
    "Your wardrobe, ready for today.",
    "What are we wearing today?",
    "Let's find your look for today.",
    "A small edit for a better morning.",
    "Dress for the day ahead.",
)

class WardrobeViewModel(val repository:Repository):ViewModel(){
    val snapshot=repository.snapshot.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),WardrobeSnapshot())
    val busy=MutableStateFlow(false);val message=MutableStateFlow("");val capabilities=MutableStateFlow<List<ProviderCapability>>(emptyList());val jobs=MutableStateFlow<List<Job>>(emptyList())
    val draft=MutableStateFlow<Garment?>(null);val profileDraft=MutableStateFlow<Profile?>(null);val generated=MutableStateFlow<List<Outfit>>(emptyList());val trends=MutableStateFlow<List<Trend>>(emptyList());val feeds=MutableStateFlow(JsonArray());val lastRefresh=MutableStateFlow("Never refreshed")
    val mode=MutableStateFlow("balanced");val occasion=MutableStateFlow("any");val weather=MutableStateFlow("mild");val pinned=MutableStateFlow("");val season=MutableStateFlow("all");val connectionState=MutableStateFlow(if(repository.connection.paired)"Saved connection · refresh to check" else "Not paired")
    val productReview=MutableStateFlow<JsonObject?>(null);val review=MutableStateFlow<ReviewState?>(null);val preview=MutableStateFlow<String?>(null);val collage=MutableStateFlow<File?>(null);val searchProducts=MutableStateFlow(JsonArray())
    val providerList=MutableStateFlow<List<ProviderCapability>>(emptyList());val checking=MutableStateFlow("");val phoneProviders=MutableStateFlow<List<ProviderCapability>>(emptyList());val weatherNow=MutableStateFlow<WeatherNow?>(null);val weatherPlace=MutableStateFlow("");val locating=MutableStateFlow(false);val weatherReady=MutableStateFlow(false)
    val greeting=MutableStateFlow(OpenGreetings.random());val advice=MutableStateFlow("")
    var engine=StylingEngine(repository.assetRules());private var polling: kotlinx.coroutines.Job?=null
    init{viewModelScope.launch{reloadCache()};viewModelScope.launch{loadProvidersSilently()};viewModelScope.launch{primeWeather()};viewModelScope.launch{combine(combine(snapshot,mode,occasion,weather,pinned){data,mode,occasion,weather,pinned->arrayOf(data,mode,occasion,weather,pinned)},season){values,season->values+season}.collectLatest{values->val data=values[0] as WardrobeSnapshot;generated.value=withContext(Dispatchers.Default){engine.generate(data.garments,data.profile,trends.value,values[1] as String,values[2] as String,values[3] as String,values[4] as String,season=values[5] as String)}}}}
    private suspend fun reloadCache(){engine=StylingEngine(repository.rules());trends.value=repository.trends();feeds.value=repository.feeds();jobs.value=repository.cachedJobs();lastRefresh.value=repository.lastRefresh();regenerate()}
    private fun mergeProviders(remote:List<ProviderCapability>,local:List<ProviderCapability>)=remote.map{p->local.find{it.id==p.id}?.let{l->p.copy(hasKey=p.hasKey||l.hasKey)}?:p}+local.filter{l->remote.none{it.id==l.id}}
    /** Phone keys and PC connections share one list, so a local key never disappears after a refresh. */
    private suspend fun applyProviders(){
        val local=withContext(Dispatchers.IO){repository.localProviders()};phoneProviders.value=local
        if(!repository.connection.paired){providerList.value=local;capabilities.value=local;return}
        val remote=runCatching{repository.providers()}.getOrNull()
        if(remote==null||remote.isEmpty()){providerList.value=local;capabilities.value=local;return}
        val merged=mergeProviders(remote,local);providerList.value=merged;capabilities.value=merged
    }
    private suspend fun loadProvidersSilently(){
        val local=withContext(Dispatchers.IO){repository.localProviders()};phoneProviders.value=local
        if(!repository.connection.paired){providerList.value=local;capabilities.value=local;return}
        runCatching{mergeProviders(repository.providers(),local)}.onSuccess{providerList.value=it;capabilities.value=it}
    }
    private suspend fun reloadPhoneProviders(){phoneProviders.value=withContext(Dispatchers.IO){repository.localProviders()}}
    private suspend fun primeWeather(){
        try{
            val place=repository.weatherPlace();weatherPlace.value=place
            repository.weather()?.let{weatherNow.value=it;weather.value=it.band}
            val coordinates=repository.weatherCoordinates()
            val refreshed=when{
                coordinates!=null->runCatching{repository.refreshWeather(coordinates.first,coordinates.second,place.ifBlank{"Current location"})}.getOrNull()
                place.isNotBlank()->runCatching{repository.refreshWeather(place)}.getOrNull()
                else->null
            }
            refreshed?.let{weatherNow.value=it;weatherPlace.value=it.place;weather.value=it.band}
        }finally{weatherReady.value=true}
    }
    fun loadWeather(place:String){action{val result=repository.refreshWeather(place);weatherNow.value=result;weatherPlace.value=result.place;weather.value=result.band;message.value="Ranked for ${result.band} weather · ${result.line}"}}
    fun loadCurrentWeather(){
        if(locating.value)return
        viewModelScope.launch{
            locating.value=true
            try{
                val fix=withContext(Dispatchers.IO){repository.location.current()}
                val result=repository.refreshWeather(fix.latitude,fix.longitude,fix.place)
                weatherNow.value=result;weatherPlace.value=result.place;weather.value=result.band
                message.value="Forecast updated for ${result.place}"
            }catch(error:Exception){
                if(error is CancellationException)throw error
                message.value=error.message?:"Weather could not be loaded. Enter a city instead."
            }finally{locating.value=false}
        }
    }
    fun refreshGreeting(){val choices=OpenGreetings.filter{it!=greeting.value};greeting.value=choices.randomOrNull()?:OpenGreetings.random()}
    private fun regenerate(){val data=snapshot.value;viewModelScope.launch{generated.value=withContext(Dispatchers.Default){engine.generate(data.garments,data.profile,trends.value,mode.value,occasion.value,weather.value,pinned.value,season=season.value)}}}
    fun action(block:suspend()->Unit){if(busy.value)return;viewModelScope.launch{busy.value=true;try{block()}catch(error:Exception){if(error is CancellationException)throw error;message.value=error.message?:"This action could not be completed"}finally{busy.value=false}}}
    fun edit(garment:Garment){draft.value=garment}
    fun newItem(){draft.value=Garment(UUID.randomUUID().toString(),"")}
    fun change(field:String,update:(Garment)->Garment){draft.value=draft.value?.let{update(it).copy(manualFields=(it.manualFields+field).distinct())}}
    fun saveDraft(){val garment=draft.value?:return;action{repository.saveGarment(garment);draft.value=null;message.value="Saved to your wardrobe"}}
    fun remove(garment:Garment){action{repository.deleteGarment(garment);draft.value=null;message.value="Item removed"}}
    fun refresh(){action{try{message.value=repository.sync();applyProviders();connectionState.value="PC connected";reloadCache()}catch(error:Exception){if(error is CancellationException)throw error;connectionState.value=when{error is ApiException&&error.status in listOf(401,403)->"Authorization failed · pair this phone again";error.message?.contains("Pair",true)==true->"Pairing needs attention";error.message?.contains("resolve",true)==true->"Tailscale address unreachable · check Tailscale on both devices";else->"PC unavailable · check server and Tailscale"};if(repository.connection.paired&&!(error is ApiException&&error.status in listOf(401,403,422)))SyncWorker.queue(repository.context);throw error}}}
    fun pair(text:String){action{repository.pair(text);message.value=repository.sync();applyProviders();connectionState.value="PC connected";reloadCache()}}
    fun disconnect(){repository.connection.disconnect();capabilities.value=emptyList();connectionState.value="Not paired";message.value="PC disconnected. Your wardrobe remains on your phone."}
    suspend fun suggestCrop(bitmap:Bitmap):CropSuggestion?=withContext(Dispatchers.IO){repository.suggestCrop(bitmap)}
    fun importPhoto(bitmap:Bitmap,left:Float,top:Float,right:Float,bottom:Float,reference:Boolean){action{val result=withContext(Dispatchers.IO){repository.photos.import(bitmap,left,top,right,bottom)};if(reference){val profile=profileDraft.value?:snapshot.value.profile;profileDraft.value=profile.copy(referenceImageId=result.imageId)}else{val item=draft.value?:Garment(UUID.randomUUID().toString(),"");draft.value=item.copy(imageIds=(item.imageIds+result.imageId).takeLast(8),color=if(item.imageIds.isEmpty()&&"color" !in item.manualFields)result.colors.firstOrNull()?:item.color else item.color,secondaryColors=result.colors.drop(1))}}}
    fun saveProfile(){profileDraft.value?.let{profile->action{repository.saveProfile(profile);profileDraft.value=null;message.value="Preferences saved"}}}
    fun saveOutfit(outfit:Outfit){action{repository.saveOutfit(outfit);message.value="Outfit saved"}}
    fun deleteOutfit(outfit:Outfit){action{repository.deleteOutfit(outfit)}}
    fun feedback(outfit:Outfit,like:Boolean){action{val p=snapshot.value.profile;repository.saveProfile(p.copy(likedItems=if(like)(p.likedItems+outfit.itemIds).distinct()else p.likedItems-outfit.itemIds.toSet(),dislikedItems=if(like)p.dislikedItems-outfit.itemIds.toSet()else(p.dislikedItems+outfit.itemIds).distinct()));message.value=if(like)"Your preferences will favor these pieces" else "Your preferences will use these pieces less"}}
    fun swap(outfit:Outfit,id:String){val current=snapshot.value.garments;val selected=outfit.itemIds.mapNotNull{x->current.find{it.id==x}};val target=selected.find{it.id==id}?:return
        val replacements=current.filter{it.category==target.category&&it.id!=id}.map{g->selected.map{if(it.id==id)g else it}}.filter{engine.valid(it,outfit.occasion,outfit.weather)}.map{engine.score(it,snapshot.value.profile,trends.value,outfit.mode,outfit.occasion,outfit.weather,outfit.season)}.sortedByDescending{it.score}
        if(replacements.isEmpty()){message.value="No available replacement in this category";return};if(snapshot.value.outfits.any{it.id==outfit.id})action{repository.saveOutfit(replacements.first().copy(name=outfit.name));repository.deleteOutfit(outfit)}else generated.value=generated.value.map{if(it.id==outfit.id)replacements.first()else it}
    }
    fun showCollage(outfit:Outfit){action{val items=outfit.itemIds.mapNotNull{id->snapshot.value.garments.find{it.id==id}};collage.value=withContext(Dispatchers.IO){repository.photos.collage(items)}}}
    fun importProduct(url:String){action{productReview.value=repository.product(url)}}
    fun acceptProduct(product:JsonObject,imageUrl:String,confirmed:Boolean){action{require(confirmed){"Confirm this is your exact item and variant"};val id=if(imageUrl.isNotBlank())repository.productImage(imageUrl)else "";val item=draft.value?:return@action
        val name=product["name"]?.asString?:item.name;val brand=product["brand"]?.asString.orEmpty();val material=product["composition"]?.asString.orEmpty()
        draft.value=item.copy(name=name,brand=brand,brandConfirmed=brand.isNotBlank(),composition=material,compositionVerified=material.isNotBlank(),productUrl=product["url"].asString,imageIds=if(id.isNotEmpty())(item.imageIds+id).distinct().take(8)else item.imageIds,manualFields=(item.manualFields+listOf("name","brand","composition","productUrl")).distinct(),provenance=item.provenance+mapOf("product" to repository.gson.fromJson(product,Map::class.java)));productReview.value=null}}
    fun requestAnalysis(item:Garment,provider:String){action{val job=repository.submit("analysis",provider,mapOf("garmentId" to item.id,"revision" to item.revision,"imageIds" to item.imageIds.take(3),"consentPhotos" to true));track(job)}}
    fun requestPhoneAnalysis(item:Garment,provider:String){action{message.value="Analyzing on this phone…";val result=repository.analyzeOnPhone(item,provider);presentReview(item,result,item.cutoutId,"phone");message.value="Analysis ready to review"}}
    fun requestStyle(outfit:Outfit,provider:String){action{
        if(repository.phoneAvailable(provider)){
            val items=outfit.itemIds.mapNotNull{id->snapshot.value.garments.find{it.id==id}}
            require(items.size==outfit.itemIds.size){"This outfit changed; request fresh advice"}
            val revisions=items.associate{it.id to it.revision}
            message.value="Writing advice on this phone…"
            val result=repository.styleOnPhone(outfit,items,snapshot.value.profile,provider)
            val current=outfit.itemIds.mapNotNull{id->snapshot.value.garments.find{it.id==id}}
            require(current.size==items.size&&current.all{revisions[it.id]==it.revision}){"This outfit changed; request fresh advice"}
            advice.value=result["explanation"]?.takeIf{!it.isJsonNull}?.asString?.takeIf{it.isNotBlank()}?:"Advice received"
            message.value="Styling advice ready"
            return@action
        }
        track(repository.submit("style",provider,mapOf("itemIds" to outfit.itemIds,"occasion" to outfit.occasion,"weather" to outfit.weather,"mode" to outfit.mode)))
    }}
    fun requestPreview(outfit:Outfit,provider:String,ownPhoto:Boolean){action{track(repository.submit("preview",provider,mapOf("itemIds" to outfit.itemIds,"occasion" to outfit.occasion,"weather" to outfit.weather,"useOwnPhoto" to ownPhoto,"referenceImageId" to snapshot.value.profile.referenceImageId,"consentPhotos" to true)))}}
    fun requestSearch(gap:Gap,provider:String){action{track(repository.submit("search",provider,mapOf("query" to gap.title+". "+gap.reason)))}}
    fun requestCutout(item:Garment){action{require(item.imageIds.isNotEmpty()){ "Add a photo first" };track(repository.submit("cutout","local",mapOf("imageIds" to listOf(item.imageIds.first()),"garmentId" to item.id,"revision" to item.revision)))}}
    fun requestDetect(item:Garment){action{
        require(item.imageIds.isNotEmpty()){"Add a photo first"}
        val local=runCatching{repository.detectOnDevice(item)}
        local.getOrNull()?.let{result->presentReview(item,result,result["cutoutId"]?.takeIf{!it.isJsonNull}?.asString.orEmpty(),"detect");message.value="Detected on this phone; the background was excluded";return@action}
        val failure=local.exceptionOrNull()
        if(!repository.connection.paired)throw IllegalArgumentException(failure?.message?:"Detection is unavailable on this install")
        message.value="Using your PC for this photo"
        track(repository.submit("detect","local",mapOf("imageIds" to listOf(item.imageIds.first()),"garmentId" to item.id,"revision" to item.revision)))
    }}
    private fun track(job:Job){jobs.value=(listOf(job)+jobs.value.filter{it.id!=job.id}).take(50);message.value="Request queued on your PC";polling?.cancel();polling=viewModelScope.launch{var attempts=0;while(attempts++<100){delay(3000);try{val updated=repository.job(job.id);jobs.value=jobs.value.map{if(it.id==job.id)updated else it};if(updated.status in listOf("completed","failed","cancelled")){if(updated.status=="completed")processResult(updated)else message.value=updated.error.ifBlank{ "Request cancelled" };break}}catch(error:Exception){if(error is CancellationException)throw error;message.value="Request remains on your PC. Refresh when reconnected.";break}}}}
    private suspend fun processResult(job:Job){val result=job.result?:return
        if(job.kind in listOf("style","preview")){
            val revisions=result["itemRevisions"]?.takeIf{it.isJsonObject}?.asJsonObject
            require(revisions!=null&&revisions.size()>0&&revisions.entrySet().all{(id,revision)->snapshot.value.garments.any{it.id==id&&it.revision==revision.asInt}}){"This outfit changed; request fresh advice or a preview"}
        }
        when(job.kind){"analysis"->{val item=snapshot.value.garments.find{it.id==result["garmentId"].asString};if(item!=null)presentReview(item,result,"","analysis")}
            "detect"->{val item=snapshot.value.garments.find{it.id==result["garmentId"].asString&&it.revision==result["revision"].asInt};require(item!=null){"This item changed; request fresh detection"};val cutoutId=result["cutoutId"]?.takeIf{!it.isJsonNull}?.asString.orEmpty();if(cutoutId.isNotEmpty()&&!repository.photos.file(cutoutId).exists())withContext(Dispatchers.IO){repository.photos.put(repository.api.download(cutoutId),cutoutId)};presentReview(item,result,cutoutId,"detect")}
            "preview"->{val id=result["imageId"].asString;if(!repository.photos.file(id).exists())withContext(Dispatchers.IO){repository.photos.put(repository.api.download(id),id)};preview.value=id;val ids=result["itemIds"]?.asJsonArray?.map{it.asString}?.toSet();val outfit=(snapshot.value.outfits+generated.value).find{it.itemIds.toSet()==ids};if(outfit!=null)repository.saveOutfit(outfit.copy(previewImageId=id))}
            "style"->advice.value=result["explanation"]?.asString?:"Advice received"
            "search"->searchProducts.value=result["products"]?.asJsonArray?:JsonArray()
            "cutout"->{val id=result["imageId"].asString;withContext(Dispatchers.IO){repository.photos.put(repository.api.download(id),id)};val source=result["sourceImageId"].asString;val garmentId=result["garmentId"]?.asString;val revision=result["revision"]?.asInt;val item=snapshot.value.garments.find{it.id==garmentId&&it.revision==revision&&source in it.imageIds};require(item!=null){ "This item changed; request a fresh cutout" };repository.saveGarment(item.copy(cutoutId=id))}}
        message.value=if(job.kind=="style")message.value else "Request completed"
    }
    private fun presentReview(item:Garment,result:JsonObject,cutoutId:String,source:String){
        val values:Map<String,Any> = repository.gson.fromJson(result,object:com.google.gson.reflect.TypeToken<Map<String,Any>>(){}.type)
        val suggestion=engine.applyAnalysis(item,values)
        review.value=ReviewState(item,suggestion,cutoutId,result["confidence"]?.takeIf{!it.isJsonNull}?.asDouble?:0.0,result["backgroundExcluded"]?.takeIf{!it.isJsonNull}?.asBoolean?:false,source)
    }
    fun dismissReview(){review.value=null}
    fun applyReview(edited:Garment){val state=review.value?:return;action{
        val current=snapshot.value.garments.find{it.id==state.item.id}?:throw IllegalArgumentException("Item was removed")
        require(current.revision==state.item.revision){"This item changed; request fresh detection"}
        val suggestion=state.suggestion
        val locked=buildList{
            if(edited.category!=suggestion.category)add("category")
            if(edited.subcategory!=suggestion.subcategory)add("subcategory")
            if(edited.color!=suggestion.color)add("color")
            if(edited.pattern!=suggestion.pattern)add("pattern")
            if(edited.texture!=suggestion.texture)add("texture")
            if(edited.fit!=suggestion.fit)add("fit")
            if(edited.shape!=suggestion.shape)add("shape")
        }
        repository.saveGarment(current.copy(category=edited.category,subcategory=edited.subcategory,color=edited.color,pattern=edited.pattern,texture=edited.texture,fit=edited.fit,shape=edited.shape,cutoutId=state.cutoutId.ifEmpty{current.cutoutId},manualFields=(current.manualFields+locked).distinct()))
        review.value=null;message.value="Saved. Fields you corrected are now protected."
    }}
    fun loadProviders(){action{applyProviders()}}
    fun checkProvider(id:String){action{checking.value=id;try{val result=repository.testProvider(id);providerList.value=providerList.value.map{if(it.id==id)result else it};capabilities.value=providerList.value;message.value="Connection checked"}finally{checking.value=""}}}
    fun saveProvider(id:String,values:Map<String,Any?>,verify:Boolean){action{
        if(values.containsKey("apiKey"))repository.phoneAi.setId(id,(values["apiKey"] as? String).orEmpty())
        reloadPhoneProviders()
        if(!repository.connection.paired){
            providerList.value=withContext(Dispatchers.IO){repository.localProviders()};capabilities.value=providerList.value
            message.value=when{repository.phoneAi.available(id)->"Saved on this phone. Clothing analysis runs here now.";values.containsKey("apiKey")->"AI key removed from this phone";else->"Add an API key to analyse clothing on this phone"}
            return@action
        }
        var result=repository.configureProvider(id,values)
        providerList.value=providerList.value.map{if(it.id==id)result else it};capabilities.value=providerList.value
        if(verify){checking.value=id;try{result=repository.testProvider(id);providerList.value=providerList.value.map{if(it.id==id)result else it};capabilities.value=providerList.value;message.value="Connection ready"}finally{checking.value=""}}
        else message.value="AI settings saved on your PC and this phone"
    }}
    fun connectChatGpt(){action{
        message.value=repository.chatGpt.signIn()
        runCatching{repository.chatGpt.refreshModels()}.onFailure{error->
            message.value=(message.value+" · models could not be loaded: "+(error.message?:"try Refresh model list")).take(300)
        }
        applyProviders()
    }}
    fun refreshChatGptModels(){action{
        val count=repository.chatGpt.refreshModels().size
        applyProviders()
        message.value=if(count==0)"No ChatGPT models were listed; try again later" else "Loaded $count ChatGPT models"
    }}
    fun selectChatGptModel(id:String){action{
        repository.chatGpt.selectModel(id)
        applyProviders()
        message.value="ChatGPT model set to $id"
    }}
    fun disconnectChatGpt(){action{val revoked=repository.chatGpt.signOut();applyProviders();message.value=if(revoked)"ChatGPT plan disconnected" else "Signed out on this phone; remote revocation was not confirmed"}}
    fun cancelJob(job:Job){action{repository.cancelJob(job.id);jobs.value=jobs.value.map{if(it.id==job.id)it.copy(status="cancelled")else it}}}
    fun openJob(job:Job){action{processResult(job)}}
    fun backup(uri:Uri){action{repository.exportBackup(uri);message.value="Backup saved without connection credentials"}}
    fun restore(uri:Uri){action{message.value=repository.restoreBackup(uri);reloadCache()}}
    class Factory(private val repository:Repository):ViewModelProvider.Factory{override fun<T:ViewModel>create(modelClass:Class<T>):T{@Suppress("UNCHECKED_CAST")return WardrobeViewModel(repository)as T}}
}
