package com.personal.wardrobe.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.gson.*
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.personal.wardrobe.data.*
import com.personal.wardrobe.domain.*
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.roundToInt

private data class AiChoice(val kind:String,val outfit:Outfit?=null,val garment:Garment?=null,val gap:Gap?=null)

@Composable fun WardrobeApp(vm:WardrobeViewModel){
    val data by vm.snapshot.collectAsStateWithLifecycle();val busy by vm.busy.collectAsStateWithLifecycle();val message by vm.message.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle();val profile by vm.profileDraft.collectAsStateWithLifecycle()
    val productReview by vm.productReview.collectAsStateWithLifecycle();val review by vm.review.collectAsStateWithLifecycle()
    val collage by vm.collage.collectAsStateWithLifecycle();val preview by vm.preview.collectAsStateWithLifecycle();val advice by vm.advice.collectAsStateWithLifecycle()
    val providers by vm.capabilities.collectAsStateWithLifecycle();val phoneProviders by vm.phoneProviders.collectAsStateWithLifecycle()
    val context=LocalContext.current;val scope=rememberCoroutineScope();val snackbar=remember{SnackbarHostState()}
    var page by remember{mutableIntStateOf(0)};var camera by remember{mutableStateOf("")};var targetReference by remember{mutableStateOf(false)}
    var crop by remember{mutableStateOf<Bitmap?>(null)};var ai by remember{mutableStateOf<AiChoice?>(null)};var restoreUri by remember{mutableStateOf<Uri?>(null)}
    fun readPhoto(uri:Uri){scope.launch{try{crop=withContext(Dispatchers.IO){vm.repository.photos.read(uri)}}catch(error:Exception){vm.message.value=error.message?:"Photo unavailable"}}}
    val gallery=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){it?.let{uri->readPhoto(uri)}}
    val backup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){it?.let{vm.backup(it)}}
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){restoreUri=it}
    val exportImage=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")){uri->if(uri!=null){val file=collage?:preview?.let{vm.repository.photos.file(it)};if(file!=null)vm.action{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use{out->file.inputStream().use{it.copyTo(out)}}?:error("Could not export image")};vm.message.value="Image exported"}}}
    LaunchedEffect(message){if(message.isNotBlank()){snackbar.showSnackbar(message);if(vm.message.value==message)vm.message.value=""}}
    Scaffold(snackbarHost={SnackbarHost(snackbar)},bottomBar={
        NavigationBar {
            listOf("Today","Wardrobe","Outfits","Discover","Connect").forEachIndexed { index,title ->
                NavigationBarItem(selected=page==index,onClick={page=index},icon={Icon(listOf(Icons.Outlined.Home,Icons.AutoMirrored.Outlined.List,Icons.Outlined.FavoriteBorder,Icons.Outlined.ShoppingCart,Icons.Outlined.Settings)[index],title)},label={Text(title)})
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)){if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            when(page){
                0->TodayScreen(vm,data,{ai=it},{vm.profileDraft.value=data.profile},{page=1;vm.newItem()})
                1->WardrobeScreen(vm,data)
                2->OutfitsScreen(vm,data){ai=it}
                3->RecommendationsScreen(vm,data){ai=it}
                else->ConnectionsScreen(vm,{camera="pair"},{backup.launch("Wardrobe-backup.zip")},{restore.launch(arrayOf("application/zip","application/octet-stream"))})
            }
        }
    }
    draft?.let{item->Editor(vm,item,data,{isCamera->targetReference=false;if(isCamera)camera="item"else gallery.launch("image/*")},{ai=AiChoice("vision",garment=it)},{vm.requestDetect(it)})}
    profile?.let{value->ProfileEditor(vm,value,{isCamera->targetReference=true;if(isCamera)camera="reference"else gallery.launch("image/*")})}
    if(camera.isNotEmpty())CameraDialog(camera=="pair",{camera=""},{uri->if(camera=="pair"){
        val scanner=BarcodeScanning.getClient();runCatching{InputImage.fromFilePath(context,uri)}.onSuccess{image->scanner.process(image).addOnSuccessListener{codes->val code=codes.firstOrNull()?.rawValue;if(code!=null)vm.pair(code)else vm.message.value="QR was not detected. Move closer or paste the pairing text."}.addOnFailureListener{vm.message.value="QR could not be read"}.addOnCompleteListener{scanner.close()}}.onFailure{scanner.close();vm.message.value="QR photo could not be opened"}
    }else{targetReference=camera=="reference";readPhoto(uri)}},{vm.message.value=it})
    crop?.let{bitmap->CropDialog(bitmap,suggest={vm.suggestCrop(it)},onDismiss={crop=null},onUse={l,t,r,b->vm.importPhoto(bitmap,l,t,r,b,targetReference);crop=null})}
    ai?.let{choice->AiDialog(choice,providers,phoneProviders,data.profile.referenceImageId.isNotEmpty(),{ai=null},{provider,own->when(choice.kind){"vision"->choice.garment?.let{item->if(vm.repository.phoneAvailable(provider))vm.requestPhoneAnalysis(item,provider)else vm.requestAnalysis(item,provider)};"text"->choice.outfit?.let{vm.requestStyle(it,provider)};"image"->choice.outfit?.let{vm.requestPreview(it,provider,own)};"search"->choice.gap?.let{vm.requestSearch(it,provider)}};ai=null})}
    productReview?.let{ProductReview(vm,it)}
    review?.let{state->ReviewSheet(vm,state)}
    val shownImage:Any?=collage?:preview?.let{vm.repository.photos.file(it)}
    if(shownImage!=null)Dialog({vm.collage.value=null;vm.preview.value=null},properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize()){Column(Modifier.systemBarsPadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){Text(if(collage!=null)"Your outfit board"else"Approximate try-on",style=MaterialTheme.typography.headlineSmall);if(preview!=null)Text("Generated visualization. Fit, proportions, colors, and small details can differ from the real clothes.");AsyncImage(shownImage,"Outfit preview",Modifier.fillMaxWidth().weight(1f),contentScale=ContentScale.Fit);Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){OutlinedButton({vm.collage.value=null;vm.preview.value=null},Modifier.weight(1f)){Text("Close")};Button({exportImage.launch("Wardrobe-outfit.jpg")},Modifier.weight(1f)){Text("Export")}}}}}
    if(advice.isNotEmpty())AlertDialog(onDismissRequest={vm.advice.value=""},title={Text("Styling advice")},text={Text(advice,Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton({vm.advice.value=""}){Text("Done")}})
    restoreUri?.let{uri->AlertDialog(onDismissRequest={restoreUri=null},title={Text("Restore your wardrobe?")},text={Text("Matching records in this backup replace the saved versions. Other current items remain. Connection credentials are excluded.")},confirmButton={Button({vm.restore(uri);restoreUri=null}){Text("Restore")}},dismissButton={TextButton({restoreUri=null}){Text("Cancel")}})}
}

@Composable private fun Header(title:String,subtitle:String,action:(@Composable ()->Unit)?=null){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.headlineLarge);Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)};action?.invoke()}}
@Composable private fun Note(title:String,text:String){Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)){Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(title,style=MaterialTheme.typography.titleMedium);Text(text)}}}
@Composable private fun Select(label:String,value:String,options:List<String>,onSelect:(String)->Unit){var open by remember{mutableStateOf(false)};Box{OutlinedButton({open=true},Modifier.fillMaxWidth()){Text("$label · ${value.replaceFirstChar{it.uppercase()}}",Modifier.weight(1f));Icon(Icons.Outlined.ArrowDropDown,null)};DropdownMenu(open,{open=false}){options.forEach{option->DropdownMenuItem(text={Text(option.replaceFirstChar{it.uppercase()})},onClick={onSelect(option);open=false})}}}}
@Composable private fun Field(label:String,value:String,onChange:(String)->Unit,help:String="",singleLine:Boolean=true){OutlinedTextField(value,onChange,Modifier.fillMaxWidth(),label={Text(label)},singleLine=singleLine,supportingText=if(help.isNotEmpty()){{Text(help)}}else null)}
@OptIn(ExperimentalLayoutApi::class) @Composable private fun Tags(options:List<String>,selected:List<String>,onChange:(List<String>)->Unit){FlowRow(horizontalArrangement=Arrangement.spacedBy(7.dp)){options.forEach{tag->FilterChip(tag in selected,{onChange(if(tag in selected)selected-tag else selected+tag)},label={Text(tag)})}}}
@Composable private fun Check(label:String,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth().clickable{onChange(!value)},verticalAlignment=Alignment.CenterVertically){Checkbox(value,onChange);Text(label,Modifier.weight(1f))}}
@Composable private fun GarmentImage(vm:WardrobeViewModel,item:Garment,modifier:Modifier=Modifier){val id=item.cutoutId.ifEmpty{item.imageIds.firstOrNull().orEmpty()};Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow),contentAlignment=Alignment.Center){if(id.isNotEmpty())AsyncImage(vm.repository.photos.file(id),item.name,Modifier.fillMaxSize().padding(8.dp),contentScale=ContentScale.Fit)else{Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.AutoMirrored.Outlined.List,null,Modifier.size(38.dp),tint=runCatching{Color(android.graphics.Color.parseColor(item.color))}.getOrDefault(Color.Gray));Text("Add a photo",style=MaterialTheme.typography.bodySmall)}}}}
@Composable private fun Link(label:String,url:String){val context=LocalContext.current;TextButton({runCatching{val uri=Uri.parse(url);require(uri.scheme=="https");context.startActivity(Intent(Intent.ACTION_VIEW,uri))}}){Text(label)}}

@Composable private fun WeatherCard(vm:WardrobeViewModel,now:WeatherNow?){
    val saved by vm.weatherPlace.collectAsStateWithLifecycle()
    var place by remember{mutableStateOf(saved)}
    LaunchedEffect(saved){if(saved.isNotBlank()&&place.isBlank())place=saved}
    Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Weather",style=MaterialTheme.typography.titleMedium)
        Text(now?.line?:"Add your city once. Today's forecast sets warm, mild or cold and the outfits below are ranked for it. No account or key needed.",style=MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.weight(1f)){Field("City",place,{place=it},"Town or city for the forecast")};Button({vm.loadWeather(place)},enabled=place.isNotBlank()){Text("Check")}}
        Text("Forecast by Open-Meteo. It is a ranking input, not a guarantee of comfort.",style=MaterialTheme.typography.bodySmall)
    }}
}

@Composable private fun TodayScreen(vm:WardrobeViewModel,data:WardrobeSnapshot,onAi:(AiChoice)->Unit,onProfile:()->Unit,onAdd:()->Unit){
    val outfits by vm.generated.collectAsStateWithLifecycle();val mode by vm.mode.collectAsStateWithLifecycle();val occasion by vm.occasion.collectAsStateWithLifecycle();val weather by vm.weather.collectAsStateWithLifecycle();val pinned by vm.pinned.collectAsStateWithLifecycle();val season by vm.season.collectAsStateWithLifecycle();val weatherNow by vm.weatherNow.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        item{Header("Wear it well","Outfits from the clothes you own") {IconButton(onProfile){Icon(Icons.Outlined.Settings,"Your preferences")}}}
        item{Select("Outfit mode",mode,listOf("timeless","balanced","trend")){vm.mode.value=it};Spacer(Modifier.height(6.dp));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Box(Modifier.weight(1f)){Select("Occasion",occasion,listOf("any","casual","formal")){vm.occasion.value=it}};Box(Modifier.weight(1f)){Select("Weather",weather,listOf("mild","warm","cold")){vm.weather.value=it}}}}
        item{WeatherCard(vm,weatherNow)}
        item{Select("Season",season,listOf("all","spring","summer","autumn","winter")){vm.season.value=it}}
        if(pinned.isNotEmpty())item{AssistChip({vm.pinned.value=""},label={Text("Around ${data.garments.find{it.id==pinned}?.name?:"an item"} · clear")})}
        if(data.garments.isEmpty())item{Note("A wardrobe that starts with you","Add your own tops, bottoms or one-piece garments, and shoes. Matching works offline with no AI setup.");Spacer(Modifier.height(12.dp));Button(onAdd,Modifier.fillMaxWidth()){Text("Add your first item")}}
        else if(outfits.isEmpty())item{Note("A few more pieces will help","An outfit needs a top and bottom, or a one-piece garment, plus shoes. Cold weather adds outerwear. Check availability or adjust the occasion.")}
        items(outfits.size){index->OutfitCard(vm,outfits[index],data,"Option ${index+1}",onAi,true)}
        item{Text(when(mode){"timeless"->"Timeless: 85% styling principles, 15% your preferences.";"trend"->"Trend focused: 60% styling principles, 15% your preferences, 25% supported trends.";else->"Balanced: 75% styling principles, 15% your preferences, 10% supported trends."},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

@OptIn(ExperimentalLayoutApi::class) @Composable private fun OutfitCard(vm:WardrobeViewModel,outfit:Outfit,data:WardrobeSnapshot,title:String,onAi:(AiChoice)->Unit,swappable:Boolean=false){
    val items=outfit.itemIds.mapNotNull{id->data.garments.find{it.id==id}};val valid=items.size==outfit.itemIds.size&&vm.engine.valid(items,outfit.occasion,outfit.weather)
    Card(shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Row{Text(title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));Text("${outfit.score.toInt()} / 100",fontWeight=FontWeight.Medium)}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){items.take(4).forEach{item->Column(Modifier.weight(1f)){GarmentImage(vm,item,Modifier.fillMaxWidth().height(136.dp).clickable{vm.edit(item)});Text(item.name,style=MaterialTheme.typography.labelMedium,maxLines=2);if(swappable)TextButton({vm.swap(outfit,item.id)},contentPadding=PaddingValues(0.dp)){Text("Swap")}}}}
        if(!valid)Text("A piece is missing or unavailable. Edit the wardrobe or save a new combination.",color=MaterialTheme.colorScheme.error)
        Text(outfit.reasons.joinToString(" "),style=MaterialTheme.typography.bodyMedium)
        Text("Principles ${outfit.foundation.toInt()} · Preferences ${outfit.personal.toInt()} · Trends ${outfit.trend.toInt()}",style=MaterialTheme.typography.labelSmall)
        if(outfit.previewImageId.isNotEmpty())OutlinedButton({vm.preview.value=outfit.previewImageId}){Text("View saved try-on")}
        outfit.trendSources.forEach{source->Text(source["title"]?.toString().orEmpty(),style=MaterialTheme.typography.labelMedium);(source["sources"] as? List<*>)?.filterIsInstance<String>()?.forEach{Link("Trend source",it)}}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){Button({vm.showCollage(outfit)},enabled=valid){Text("Outfit board")};OutlinedButton({vm.saveOutfit(outfit)},enabled=valid){Text("Save")};OutlinedButton({onAi(AiChoice("image",outfit=outfit))},enabled=valid){Icon(Icons.Outlined.Star,null,Modifier.size(16.dp));Text(" Try-on")};TextButton({onAi(AiChoice("text",outfit=outfit))},enabled=valid){Text("AI advice")}}
        Row{TextButton({vm.feedback(outfit,true)},enabled=valid){Text("Would wear")};TextButton({vm.feedback(outfit,false)},enabled=valid){Text("Not my style")}}
    }}
}

@Composable private fun WardrobeScreen(vm:WardrobeViewModel,data:WardrobeSnapshot){var filter by remember{mutableStateOf("all")};val items=data.garments.filter{filter=="all"||it.category==filter}
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp)){Spacer(Modifier.height(20.dp));Header("Your wardrobe","${data.garments.size} pieces · kept on this phone"){FilledTonalIconButton({vm.newItem()}){Icon(Icons.Outlined.Add,"Add clothing")}};Spacer(Modifier.height(14.dp));Select("Show",filter,listOf("all")+vm.engine.rules.categories){filter=it};Spacer(Modifier.height(14.dp))
        if(items.isEmpty())Note("Make space for your own clothes","Photograph, import, or manually add an item. Your wardrobe begins empty.")
        LazyVerticalGrid(GridCells.Fixed(2),verticalArrangement=Arrangement.spacedBy(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=20.dp)){
            items(items,key={it.id}){item->Card(onClick={vm.edit(item)}){GarmentImage(vm,item,Modifier.fillMaxWidth().height(190.dp));Column(Modifier.padding(12.dp)){Text(item.name,style=MaterialTheme.typography.titleSmall);Text("${item.subcategory} · ${item.status}",style=MaterialTheme.typography.bodySmall);Text(if(item.brandConfirmed)item.brand else "Brand unconfirmed",style=MaterialTheme.typography.labelSmall);TextButton({vm.pinned.value=item.id;vm.message.value="Today will build around ${item.name}"},contentPadding=PaddingValues(0.dp)){Text("Build around this")}}}}
        }
    }
}

@OptIn(ExperimentalLayoutApi::class) @Composable private fun Editor(vm:WardrobeViewModel,item:Garment,data:WardrobeSnapshot,onPhoto:(Boolean)->Unit,onAnalysis:(Garment)->Unit,onDetect:(Garment)->Unit){
    var delete by remember{mutableStateOf(false)};val existing=data.garments.find{it.id==item.id};val scope=rememberCoroutineScope()
    Dialog({vm.draft.value=null},properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize()){Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Header(if(existing==null)"Add a piece"else"Edit your piece","Your corrections stay authoritative"){IconButton({vm.draft.value=null}){Icon(Icons.Outlined.Close,"Close")}}
        GarmentImage(vm,item,Modifier.fillMaxWidth().height(250.dp));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({onPhoto(true)},Modifier.weight(1f)){Text("Camera")};OutlinedButton({onPhoto(false)},Modifier.weight(1f)){Text("Import photo")}}
        if(item.imageIds.isNotEmpty())Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){item.imageIds.forEachIndexed{index,id->Column{AsyncImage(vm.repository.photos.file(id),"Photo ${index+1}",Modifier.size(80.dp),contentScale=ContentScale.Fit);TextButton({vm.change("imageIds"){it.copy(imageIds=it.imageIds-id,cutoutId="")}}){Text("Remove")}}}}
        if(existing!=null){Text("Save first so detection matches this item. Detect clothing runs on this phone, free and with no PC.",style=MaterialTheme.typography.bodySmall);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({onDetect(existing);vm.draft.value=null},Modifier.weight(1f),enabled=existing.imageIds.isNotEmpty()&&item==existing){Text("Detect clothing")};OutlinedButton({onAnalysis(existing);vm.draft.value=null},Modifier.weight(1f),enabled=existing.imageIds.isNotEmpty()&&item==existing){Text("AI analysis")}}}
        Field("Name",item.name,{value->vm.change("name"){it.copy(name=value)}})
        Select("Category",item.category,vm.engine.rules.categories){value->vm.change("category"){it.copy(category=value,subcategory=vm.engine.rules.subcategories[value]?.firstOrNull().orEmpty(),manualFields=(it.manualFields+"subcategory").distinct())}}
        Select("Type",item.subcategory,vm.engine.rules.subcategories[item.category]?:emptyList()){value->vm.change("subcategory"){it.copy(subcategory=value)}}
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Box(Modifier.size(28.dp).background(runCatching{Color(android.graphics.Color.parseColor(item.color))}.getOrDefault(Color.Gray),RoundedCornerShape(14.dp)));Box(Modifier.weight(1f)){Field("Main color",item.color,{value->vm.change("color"){it.copy(color=value)}},"Hex color, such as #72734e")}}
        if(item.secondaryColors.isNotEmpty())FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){item.secondaryColors.forEach{color->AssistChip({vm.change("color"){it.copy(color=color)}},label={Text(color)},leadingIcon={Box(Modifier.size(14.dp).background(runCatching{Color(android.graphics.Color.parseColor(color))}.getOrDefault(Color.Gray)))})}}
        Select("Pattern",item.pattern,vm.engine.rules.patterns){value->vm.change("pattern"){it.copy(pattern=value)}};Select("Visible texture",item.texture,vm.engine.rules.textures){value->vm.change("texture"){it.copy(texture=value)}};Text("Texture describes appearance. Only a label or confirmed product page verifies composition.",style=MaterialTheme.typography.bodySmall)
        Select("Fit",item.fit,vm.engine.rules.fits){value->vm.change("fit"){it.copy(fit=value)}};Field("Shape",item.shape,{value->vm.change("shape"){it.copy(shape=value)}},"Silhouette note, for example longline or tapered",singleLine=false)
        Text("Formality · ${listOf("very casual","casual","smart","formal")[item.formality.coerceIn(0,3)]}");Slider(item.formality.toFloat(),{value->vm.change("formality"){it.copy(formality=value.toInt())}},valueRange=0f..3f,steps=2)
        Text("Warmth · ${listOf("light","everyday","warm","heavy")[item.warmth.coerceIn(0,3)]}");Slider(item.warmth.toFloat(),{value->vm.change("warmth"){it.copy(warmth=value.toInt())}},valueRange=0f..3f,steps=2)
        Text("Style tags",style=MaterialTheme.typography.titleSmall);Tags(vm.engine.rules.styles,item.styleTags){value->vm.change("styleTags"){it.copy(styleTags=value)}}
        Select("Season",item.season,listOf("all","spring","summer","autumn","winter")){value->vm.change("season"){it.copy(season=value)}};Select("Availability",item.status,listOf("available","laundry","excluded")){value->vm.change("status"){it.copy(status=value)}}
        Field("Brand (optional)",item.brand,{value->vm.change("brand"){it.copy(brand=value)}});Check("I verified this brand",item.brandConfirmed){value->vm.change("brandConfirmed"){it.copy(brandConfirmed=value)}}
        Field("Fabric composition (optional)",item.composition,{value->vm.change("composition"){it.copy(composition=value)}},"From a label or confirmed exact product");Check("I verified the fabric composition",item.compositionVerified){value->vm.change("compositionVerified"){it.copy(compositionVerified=value)}}
        Field("Exact product URL",item.productUrl,{value->vm.change("productUrl"){it.copy(productUrl=value)}},"Public HTTPS product page");OutlinedButton({vm.importProduct(item.productUrl)},Modifier.fillMaxWidth(),enabled=item.productUrl.startsWith("https://")){Text("Find details")}
        Button({vm.saveDraft()},Modifier.fillMaxWidth(),enabled=item.name.isNotBlank()&&Regex("#[0-9a-fA-F]{6}").matches(item.color)){Text("Save item")};if(existing!=null)TextButton({delete=true},Modifier.fillMaxWidth()){Text("Remove item",color=MaterialTheme.colorScheme.error)}
    }}}
    if(delete)AlertDialog({delete=false},title={Text("Remove ${item.name}?")},text={Text("It will leave your wardrobe and future outfits.")},confirmButton={Button({vm.remove(item);delete=false}){Text("Remove")}},dismissButton={TextButton({delete=false}){Text("Cancel")}})
}

@Composable private fun ProfileEditor(vm:WardrobeViewModel,profile:Profile,onPhoto:(Boolean)->Unit){var colorsText by remember{mutableStateOf(profile.favoriteColors.joinToString(", "))};var budgetText by remember{mutableStateOf(if(profile.shoppingBudget==0.0)""else profile.shoppingBudget.toString())};Dialog({vm.profileDraft.value=null},properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize()){Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
    Header("Your style","Preferences shape outfit rankings"){IconButton({vm.profileDraft.value=null}){Icon(Icons.Outlined.Close,"Close")}}
    Text("Preferred styles");Tags(vm.engine.rules.styles,profile.styles){vm.profileDraft.value=profile.copy(styles=it)}
    Field("Favorite colors",colorsText,{colorsText=it;vm.profileDraft.value=profile.copy(favoriteColors=it.split(',').map{v->v.trim()}.filter{v->Regex("#[0-9a-fA-F]{6}").matches(v)})},"Comma-separated hex colors")
    Select("Preferred fit",profile.fit,vm.engine.rules.fits){vm.profileDraft.value=profile.copy(fit=it)};Field("Sizes",profile.sizes,{vm.profileDraft.value=profile.copy(sizes=it)},"Your sizing notes")
    Field("Combinations you dislike",profile.dislikedCombinations,{vm.profileDraft.value=profile.copy(dislikedCombinations=it)},"Example: striped + checked; #ff0000 + #00ff00",false)
    Field("Shopping region",profile.region,{vm.profileDraft.value=profile.copy(region=it)});Field("Currency",profile.currency,{vm.profileDraft.value=profile.copy(currency=it.take(3).uppercase())});Field("Shopping budget",budgetText,{budgetText=it;vm.profileDraft.value=profile.copy(shoppingBudget=it.toDoubleOrNull()?.coerceAtLeast(0.0)?:0.0)},"Optional purchase budget, separate from AI spending")
    Field("Preferred stores",profile.stores.joinToString(", "),{vm.profileDraft.value=profile.copy(stores=it.split(',').map{v->v.trim()}.filter{v->v.isNotEmpty()})})
    Text("Try-on reference",style=MaterialTheme.typography.titleMedium);Text("Use a generic model or add your own reference. Sending it to AI requires explicit photo consent.")
    if(profile.referenceImageId.isNotEmpty()){AsyncImage(vm.repository.photos.file(profile.referenceImageId),"Your try-on reference",Modifier.fillMaxWidth().height(220.dp),contentScale=ContentScale.Fit);TextButton({vm.profileDraft.value=profile.copy(referenceImageId="")}){Text("Remove reference")}}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton({onPhoto(true)},Modifier.weight(1f)){Text("Camera")};OutlinedButton({onPhoto(false)},Modifier.weight(1f)){Text("Import reference")}}
    Button({vm.saveProfile()},Modifier.fillMaxWidth()){Text("Save preferences")}
}}}}

@Composable private fun OutfitsScreen(vm:WardrobeViewModel,data:WardrobeSnapshot,onAi:(AiChoice)->Unit){LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){item{Header("Saved outfits","Real pieces, ready to wear")};if(data.outfits.isEmpty())item{Note("Keep the combinations you like","Save an outfit from Today. Outfit boards combine the photos of your actual clothing.")};items(data.outfits.size){i->val outfit=data.outfits[i];OutfitCard(vm,outfit,data,outfit.name,onAi,true);TextButton({vm.deleteOutfit(outfit)}){Text("Remove saved outfit")}}}}

@Composable private fun RecommendationsScreen(vm:WardrobeViewModel,data:WardrobeSnapshot,onAi:(AiChoice)->Unit){val products by vm.searchProducts.collectAsStateWithLifecycle();val gaps=vm.engine.gaps(data.garments)
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){item{Header("Make more outfits","Useful additions for what you own")};if(data.garments.isEmpty())item{Note("Start with your wardrobe","Add a few pieces to make recommendations personal. These suggestions describe clothing; prices and availability are never guessed.")}
        items(gaps.size){index->val gap=gaps[index];Card{Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(gap.title,style=MaterialTheme.typography.titleLarge);Text(gap.reason);val matches=data.garments.filter{it.id in gap.matchingItemIds};if(matches.isNotEmpty()){Text("Works with",style=MaterialTheme.typography.labelMedium);Row(Modifier.horizontalScroll(rememberScrollState())){matches.take(6).forEach{item->Column(Modifier.width(84.dp)){GarmentImage(vm,item,Modifier.size(80.dp));Text(item.name,style=MaterialTheme.typography.labelSmall)}}}}
            val hypothetical=Garment("recommendation",gap.title,gap.category,color=gap.color,texture=gap.texture);val examples=vm.engine.generate(data.garments+hypothetical,data.profile,pinned="recommendation").filter{"recommendation" in it.itemIds}.take(2);examples.forEach{outfit->Text("Would enable: "+outfit.itemIds.map{id->if(id=="recommendation")gap.title else data.garments.find{it.id==id}?.name.orEmpty()}.joinToString(" + "),style=MaterialTheme.typography.bodySmall)}
            OutlinedButton({onAi(AiChoice("search",gap=gap))}){Text("Find actual products")}}}}
        if(products.size()>0)item{Text("Retrieved products",style=MaterialTheme.typography.headlineSmall);Text("Check size, price and availability on the retailer’s website.",style=MaterialTheme.typography.bodySmall)}
        items(products.size()){i->val product=products[i].asJsonObject;Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(product.string("name","Product"),style=MaterialTheme.typography.titleMedium);Text(product.string("brand"));Text("Variant not yet confirmed · inspect the exact color and size",style=MaterialTheme.typography.bodySmall);Text("Price: ${product.string("price","Unavailable")} ${product.string("currency")}");Text("Sizes: ${product.string("size","Check retailer")}");Text("Checked: ${product.string("checkedAt","Unknown")}",style=MaterialTheme.typography.bodySmall);Link("View product and available variants",product.string("url"));val matches=data.garments.filter{g->product["matchingItemIds"]?.asJsonArray?.any{it.asString==g.id}==true};if(matches.isNotEmpty())Text("Works with: "+matches.joinToString{it.name});product["exampleOutfits"]?.asJsonArray?.forEach{example->Text("Would enable: "+example.asJsonObject["itemIds"].asJsonArray.joinToString(" + "){id->if(id.asString=="recommendation")product.string("name")else data.garments.find{it.id==id.asString}?.name.orEmpty()},style=MaterialTheme.typography.bodySmall)}}}}
    }
}

@Composable private fun ConnectionsScreen(vm:WardrobeViewModel,onScan:()->Unit,onBackup:()->Unit,onRestore:()->Unit){val state by vm.connectionState.collectAsStateWithLifecycle();val last by vm.lastRefresh.collectAsStateWithLifecycle();val providers by vm.providerList.collectAsStateWithLifecycle();val jobs by vm.jobs.collectAsStateWithLifecycle();val feeds by vm.feeds.collectAsStateWithLifecycle();val trends by vm.trends.collectAsStateWithLifecycle();var pairing by remember{mutableStateOf("")};var disconnect by remember{mutableStateOf(false)}
    LaunchedEffect(vm.repository.connection.paired){vm.loadProviders()}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        item{Header("Your connections","Optional PC sync · private by design")}
        item{Note(state,if(vm.repository.connection.paired)vm.repository.connection.url else "Open the PC dashboard, connect both devices to your Tailscale network, then scan the pairing QR. The PC must be awake and the server running.")}
        if(!vm.repository.connection.paired)item{Button(onScan,Modifier.fillMaxWidth()){Text("Scan pairing QR")};Spacer(Modifier.height(10.dp));Field("Pairing text",pairing,{pairing=it},"Paste the complete pairing text from the PC",false);Button({vm.pair(pairing)},Modifier.fillMaxWidth(),enabled=pairing.isNotBlank()){Text("Pair with PC")}}
        else item{Button({vm.refresh()},Modifier.fillMaxWidth()){Icon(Icons.Outlined.Refresh,null);Text(" Refresh database")};Text("Last successful refresh: $last",style=MaterialTheme.typography.bodySmall);TextButton({disconnect=true}){Text("Disconnect this PC")}}
        item{Text("AI connections",style=MaterialTheme.typography.titleLarge);Text("Matching, photo colours, outfit boards and automatic framing work without AI. Add your own Gemini or OpenAI key, or sign in with your ChatGPT plan: clothing analysis and styling advice then run on this phone. Pair a PC only if you also want your wardrobe there.",style=MaterialTheme.typography.bodySmall)}
        if(providers.isEmpty())item{OutlinedButton({vm.loadProviders()},Modifier.fillMaxWidth()){Text("Load AI connections")}}
        items(providers.size){i->ProviderSetupCard(vm,providers[i])}
        if(!vm.repository.connection.paired)item{Note("Your PC is optional","Pair it to sync and back up the wardrobe and generate try-on previews with a PC subscription AI. Weather, detection, product import, styling advice and clothing analysis work without it.")}
        item{Text("Sources and trends",style=MaterialTheme.typography.titleLarge);Text("${feeds.size()} source updates · ${trends.size} supported trends. Source updates influence outfits only after structured analysis with evidence.",style=MaterialTheme.typography.bodySmall)}
        items(minOf(feeds.size(),6)){i->val feed=feeds[i].asJsonObject;Column{Text(feed.string("title"),style=MaterialTheme.typography.titleSmall);Text(feed.string("publishedAt",feed.string("date")),style=MaterialTheme.typography.labelSmall);Link(feed.string("source","Source article"),feed.string("url"))}}
        if(jobs.isNotEmpty())item{Text("PC requests",style=MaterialTheme.typography.titleLarge)}
        items(jobs.size){i->val job=jobs[i];Card{Column(Modifier.padding(16.dp)){Text("${job.kind.replace('_',' ')} · ${job.provider}",style=MaterialTheme.typography.titleMedium);Text(job.status);if(job.error.isNotBlank())Text(job.error,style=MaterialTheme.typography.bodySmall);if(job.status=="completed")TextButton({vm.openJob(job)}){Text("View result")};if(job.status in listOf("queued","running"))TextButton({vm.cancelJob(job)}){Text("Cancel request")}}}}
        item{Text("Backup and restore",style=MaterialTheme.typography.titleLarge);Text("Includes wardrobe, photos, preferences, saved outfits and styling data. Connection and API credentials are excluded.");Spacer(Modifier.height(8.dp));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onBackup,Modifier.weight(1f)){Text("Export backup")};OutlinedButton(onRestore,Modifier.weight(1f)){Text("Restore backup")}}}
    }
    if(disconnect)AlertDialog({disconnect=false},title={Text("Disconnect your PC?")},text={Text("Wardrobe data remains on this phone. Revoke the device on the PC dashboard to invalidate its token.")},confirmButton={Button({vm.disconnect();disconnect=false}){Text("Disconnect")}},dismissButton={TextButton({disconnect=false}){Text("Cancel")}})
}

@Composable private fun AiDialog(choice:AiChoice,providers:List<ProviderCapability>,phoneProviders:List<ProviderCapability>,hasReference:Boolean,onDismiss:()->Unit,onRun:(String,Boolean)->Unit){
    val eligible=(if(choice.kind in listOf("vision","text"))phoneProviders+providers else providers).distinctBy{it.id}.filter{when(choice.kind){"vision"->it.vision;"image"->it.imageGeneration;"text"->it.text;else->it.webSearch}};var provider by remember{mutableStateOf(eligible.firstOrNull()?.id.orEmpty())};var consent by remember{mutableStateOf(false)};var own by remember{mutableStateOf(false)};val photos=choice.kind in listOf("vision","image")
    AlertDialog(onDismiss,title={Text(when(choice.kind){"image"->"Generate a try-on";"vision"->"Analyze clothing";"search"->"Find matching products";else->"Get styling advice"})},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        if(eligible.isEmpty()){Icon(Icons.Outlined.Lock,null);Text("This feature is locked. Add a compatible key or connect your ChatGPT plan under Connections → AI connections; clothing analysis and styling advice then run on this phone. Image generation and product search still need a paired PC with a checked connection.")}
        else{Select("Provider",provider,eligible.map{it.id}){provider=it};Text(if(phoneProviders.any{it.id==provider})if(choice.kind=="text")"Runs on this phone with the key or plan you saved. Only this outfit and your preferences leave the device." else "Runs on this phone with the key or plan you saved. Only this photo and its attributes leave the device." else "Uses the provider’s configured allowance or spending limit. PC connections can also send selected data to cloud services.")
            if(choice.kind=="image"){Check("Use my reference photo",own,{own=it&&hasReference});if(!hasReference)Text("Add a reference in Your style to use your own photo. Otherwise a generic adult model is used.",style=MaterialTheme.typography.bodySmall);Text("Try-on is an approximate visualization and cannot establish fit.")}
            if(photos)Check("I consent to sending these clothing images${if(own)" and my reference photo"else""} to this provider",consent){consent=it}}
    }},confirmButton={Button({onRun(provider,own)},enabled=provider.isNotEmpty()&&(!photos||consent)){Text("Run request")}},dismissButton={TextButton(onDismiss){Text("Close")}})
}

@Composable private fun ProductReview(vm:WardrobeViewModel,result:JsonObject){val products=result["products"]?.asJsonArray?:JsonArray();var selected by remember{mutableIntStateOf(0)};var image by remember{mutableStateOf("")};var confirmed by remember{mutableStateOf(false)}
    Dialog({vm.productReview.value=null},properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize()){Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Header("Confirm your product","Choose the exact design, color and image"){IconButton({vm.productReview.value=null}){Icon(Icons.Outlined.Close,"Close")}}
        if(products.size()==0)Text("No product details were retrievable. You can keep the URL and edit the clothing attributes manually.")
        products.forEachIndexed{i,element->val product=element.asJsonObject;Row(Modifier.fillMaxWidth().clickable{selected=i;image="";confirmed=false},verticalAlignment=Alignment.CenterVertically){RadioButton(selected==i,{selected=i;image="";confirmed=false});Column{Text(product.string("name","Product"),style=MaterialTheme.typography.titleMedium);Text("Variant: ${product.string("color","Not provided")} · ${product.string("brand")}")}}}
        if(products.size()>0){val product=products[selected].asJsonObject;Text("Composition: ${product.string("composition","Not provided")}");Text("Price: ${product.string("price","Not provided")} ${product.string("currency")}")
            val images=product["images"]?.asJsonArray?:JsonArray();images.take(8).forEach{element->val url=element.asString;Row(Modifier.fillMaxWidth().clickable{image=url;confirmed=false},verticalAlignment=Alignment.CenterVertically){RadioButton(image==url,{image=url;confirmed=false});AsyncImage(url,"Product image option",Modifier.size(120.dp),contentScale=ContentScale.Fit)}}
            TextButton({image=""}){Text("Use details without a website image")};Check("I confirm this exact item, color variant and selected image",confirmed){confirmed=it};Button({vm.acceptProduct(product,image,confirmed)},Modifier.fillMaxWidth(),enabled=confirmed){Text("Use confirmed product")}}
    }}}
}

private fun JsonObject.string(key:String,fallback:String=""):String=get(key)?.takeIf{!it.isJsonNull}?.let{if(it.isJsonPrimitive)it.asString else it.toString()}?:fallback

private fun JsonObject?.flag(key:String,fallback:Boolean=false):Boolean=this?.get(key)?.takeIf{!it.isJsonNull}?.asBoolean?:fallback
private fun JsonObject?.amount(key:String,fallback:Double=0.0):Double=runCatching{this?.get(key)?.takeIf{!it.isJsonNull}?.asDouble?:fallback}.getOrDefault(fallback)
private fun providerName(id:String)=when(id){"gemini"->"Gemini API";"openai"->"OpenAI API";"chatgpt"->"ChatGPT plan";"codex"->"Codex on your PC";"claude"->"Claude Code on your PC";else->id.replaceFirstChar{it.uppercase()}}
private fun capabilitySummary(p:ProviderCapability)="Advice ${if(p.text)"✓"else"locked"} · Analysis ${if(p.vision)"✓"else"locked"} · Search ${if(p.webSearch)"✓"else"locked"} · Images ${if(p.imageGeneration)"✓"else"locked"}"
private fun providerValues(enabled:Boolean,freeOnly:Boolean,confirmed:Boolean,budget:String,photos:Boolean,key:String):Map<String,Any?> = buildMap{put("enabled",enabled);put("freeOnly",freeOnly);put("freeProjectConfirmed",confirmed);put("monthlyBudget",budget.toDoubleOrNull()?:0.0);put("allowPhotos",photos);if(key.isNotBlank())put("apiKey",key.trim())}

@Composable private fun SecretField(label:String,value:String,onChange:(String)->Unit,help:String){
    OutlinedTextField(value,onChange,Modifier.fillMaxWidth(),label={Text(label)},singleLine=true,visualTransformation=PasswordVisualTransformation(),supportingText={Text(help)})
}

private fun chatGptModels(provider:ProviderCapability):List<ChatGptModel> {
    val array=provider.config?.get("models")?.takeIf{it.isJsonArray}?.asJsonArray?:return emptyList()
    return array.mapNotNull{entry->
        val item=entry.takeIf{it.isJsonObject}?.asJsonObject?:return@mapNotNull null
        val id=item["id"]?.takeIf{!it.isJsonNull}?.asString?.takeIf{it.isNotBlank()}?:return@mapNotNull null
        ChatGptModel(
            id=id,
            name=item["name"]?.takeIf{!it.isJsonNull}?.asString?.takeIf{it.isNotBlank()}?:id,
            vision=item["vision"]?.takeIf{!it.isJsonNull}?.asBoolean==true,
            visionKnown=item["visionKnown"]?.takeIf{!it.isJsonNull}?.asBoolean==true
        )
    }
}

@Composable private fun ProviderSetupCard(vm:WardrobeViewModel,provider:ProviderCapability){
    val active by vm.checking.collectAsStateWithLifecycle();val byKey=provider.id in listOf("gemini","openai");val chatgpt=provider.id=="chatgpt";val local=provider.reason=="Runs on this phone"
    var enabled by remember(provider){mutableStateOf(provider.enabled)}
    var photos by remember(provider){mutableStateOf(provider.config.flag("allowPhotos"))}
    var freeOnly by remember(provider){mutableStateOf(provider.config.flag("freeOnly",true))}
    var confirmed by remember(provider){mutableStateOf(provider.config.flag("freeProjectConfirmed"))}
    var budget by remember(provider){mutableStateOf(provider.config.amount("monthlyBudget").takeIf{it>0}?.toInt()?.toString().orEmpty())}
    var key by remember(provider){mutableStateOf("")}
    Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(providerName(provider.id),style=MaterialTheme.typography.titleMedium)
        Text(when{chatgpt->if(provider.authenticated)"Signed in on this phone" else "Not connected";local->"Key encrypted on this phone";provider.hasKey->"Key saved on your PC";byKey->"No key saved yet";provider.authenticated->"Signed in on your PC";else->"Not signed in on your PC"},style=MaterialTheme.typography.bodySmall)
        if(chatgpt){
            Text("Uses your ChatGPT plan through the official Sign in with ChatGPT. Clothing analysis and styling advice run on this phone; this is not the Codex CLI agent on a PC.",style=MaterialTheme.typography.bodySmall)
            if(provider.authenticated){
                val options=chatGptModels(provider);val selected=provider.config?.get("model")?.takeIf{!it.isJsonNull}?.asString.orEmpty();val current=options.find{it.id==selected}
                if(options.isNotEmpty()){
                    Select("Model",selected,options.map{it.id}){vm.selectChatGptModel(it)}
                    Text("Selected: ${current?.name?:selected}",style=MaterialTheme.typography.bodySmall)
                    Text(when{current==null->"Choose a model for this connection.";current.vision->"Vision + text: suitable for photo analysis and advice.";current.visionKnown->"Text only: choose a vision-capable model before analysing photos.";else->"Image support was not reported; if photo analysis fails, choose another model."},style=MaterialTheme.typography.bodySmall)
                    TextButton({vm.refreshChatGptModels()}){Text("Refresh model list")}
                }else{
                    Text("No models loaded yet. Load the list, then choose a vision-capable model for photo analysis.",style=MaterialTheme.typography.bodySmall)
                    OutlinedButton({vm.refreshChatGptModels()},Modifier.fillMaxWidth()){Text("Load models")}
                }
            }
            Button({vm.connectChatGpt()},Modifier.fillMaxWidth()){Text(if(provider.authenticated)"Reconnect ChatGPT plan"else"Sign in with ChatGPT")}
            if(provider.authenticated)TextButton({vm.disconnectChatGpt()}){Text("Sign out")}
        }else if(byKey){
            Check("Enable this connection",enabled){enabled=it}
            SecretField(if(provider.hasKey)"Replace API key (optional)" else "API key",key,{key=it},if(local)"Encrypted with the Android keystore and used only on this phone" else "Stored protected on your PC; this phone keeps an encrypted copy for analysis")
            Check("Free-only usage",freeOnly){freeOnly=it}
            if(provider.id=="gemini")Check("This Gemini project has billing disabled",confirmed){confirmed=it}
            Field("Monthly app budget (USD)",budget,{budget=it.filter{c->c.isDigit()||c=='.'}},"Request allowance, not exact provider billing")
            Check("Allow clothing photos to reach this provider",photos){photos=it}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton({vm.saveProvider(provider.id,providerValues(enabled,freeOnly,confirmed,budget,photos,key),false)},Modifier.weight(1f)){Text("Save")}
                Button({vm.saveProvider(provider.id,providerValues(enabled,freeOnly,confirmed,budget,photos,key),true);key=""},Modifier.weight(1f),enabled=active!=provider.id){Text(if(active==provider.id)"Checking…"else"Save & check")}
            }
            if(provider.hasKey)TextButton({vm.saveProvider(provider.id,mapOf("apiKey" to ""),false)}){Text("Remove saved key")}
        }else{
            Button({vm.checkProvider(provider.id)},enabled=active!=provider.id){Text(if(active==provider.id)"Checking…"else"Check PC connection")}
        }
        Text(capabilitySummary(provider),style=MaterialTheme.typography.labelSmall)
        Text(provider.reason,style=MaterialTheme.typography.bodySmall)
        if(byKey)Text("Reserved this month: ${"%.2f".format(provider.reservedThisMonth)}",style=MaterialTheme.typography.labelSmall)
    }}
}

@OptIn(ExperimentalLayoutApi::class) @Composable private fun ReviewSheet(vm:WardrobeViewModel,state:ReviewState){
    var category by remember(state){mutableStateOf(state.suggestion.category)}
    var subcategory by remember(state){mutableStateOf(state.suggestion.subcategory)}
    var color by remember(state){mutableStateOf(state.suggestion.color)}
    var pattern by remember(state){mutableStateOf(state.suggestion.pattern)}
    var texture by remember(state){mutableStateOf(state.suggestion.texture)}
    var fit by remember(state){mutableStateOf(state.suggestion.fit)}
    var shape by remember(state){mutableStateOf(state.suggestion.shape)}
    val confidence=(state.confidence*100).roundToInt()
    Dialog({vm.dismissReview()},properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize()){Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Header(if(state.source=="detect")"Check what was detected" else "Review AI suggestions",if(state.source=="detect")"Read from the clothing only — the background was excluded" else "Suggestions only; your corrections stay authoritative"){IconButton({vm.dismissReview()}){Icon(Icons.Outlined.Close,"Close")}}
        if(state.cutoutId.isNotEmpty())AsyncImage(vm.repository.photos.file(state.cutoutId),"Detected clothing",Modifier.fillMaxWidth().height(220.dp),contentScale=ContentScale.Fit)
        if(state.source=="detect")Text("Confidence $confidence%"+if(confidence<60)" · low confidence: check each field" else "",style=MaterialTheme.typography.labelLarge,color=if(confidence<60)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if(!state.backgroundExcluded&&state.source in listOf("analysis","phone"))Text("The background was not excluded for this request. Run Detect clothing first for a background-free reading.",style=MaterialTheme.typography.bodySmall)
        Select("Category",category,vm.engine.rules.categories){value->category=value;val types=vm.engine.rules.subcategories[value]?:emptyList();if(subcategory !in types)subcategory=types.firstOrNull().orEmpty()}
        Select("Type",subcategory,vm.engine.rules.subcategories[category]?:emptyList()){subcategory=it}
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Box(Modifier.size(28.dp).background(runCatching{Color(android.graphics.Color.parseColor(color))}.getOrDefault(Color.Gray),RoundedCornerShape(14.dp)));Box(Modifier.weight(1f)){Field("Main colour",color,{color=it},"Hex colour, such as #3d6699")}}
        if(state.suggestion.secondaryColors.isNotEmpty()){Text("Colours also seen",style=MaterialTheme.typography.labelMedium);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){state.suggestion.secondaryColors.forEach{swatch->AssistChip({color=swatch},label={Text(swatch)},leadingIcon={Box(Modifier.size(14.dp).background(runCatching{Color(android.graphics.Color.parseColor(swatch))}.getOrDefault(Color.Gray)))})}}}
        Select("Pattern",pattern,vm.engine.rules.patterns){pattern=it}
        Select("Visible texture",texture,vm.engine.rules.textures){texture=it}
        Select("Fit",fit,vm.engine.rules.fits){fit=it}
        Field("Shape",shape,{shape=it},"From the outline, for example longline, tapered",singleLine=false)
        Text(if(state.source=="detect")"Anything you change here stays protected from later analysis. Brand and fabric are never guessed." else "AI suggestions never overwrite fields you already edited. Confirm brand and fabric only from a label.",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton({vm.dismissReview()},Modifier.weight(1f)){Text("Cancel")};Button({vm.applyReview(state.suggestion.copy(category=category,subcategory=subcategory,color=color,pattern=pattern,texture=texture,fit=fit,shape=shape))},Modifier.weight(1f)){Text("Apply")}}
    }}}
}
