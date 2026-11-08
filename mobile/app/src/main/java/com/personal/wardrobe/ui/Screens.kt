package com.personal.wardrobe.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.*
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.OutlinedButton as M3OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.gson.*
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.personal.wardrobe.BuildConfig
import com.personal.wardrobe.data.*
import com.personal.wardrobe.domain.*
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.roundToInt

private data class AiChoice(val kind:String,val outfit:Outfit?=null,val garment:Garment?=null,val gap:Gap?=null)
private const val LOCATION_INTRO_KEY="location-intro-seen"
private fun hasLocationPermission(context:Context):Boolean=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED

@Composable fun WardrobeApp(vm:WardrobeViewModel,appearance:String="system",onAppearance:(String)->Unit={}){
    val data by vm.snapshot.collectAsStateWithLifecycle();val busy by vm.busy.collectAsStateWithLifecycle();val message by vm.message.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle();val profile by vm.profileDraft.collectAsStateWithLifecycle()
    val productReview by vm.productReview.collectAsStateWithLifecycle();val review by vm.review.collectAsStateWithLifecycle()
    val collage by vm.collage.collectAsStateWithLifecycle();val preview by vm.preview.collectAsStateWithLifecycle();val advice by vm.advice.collectAsStateWithLifecycle()
    val providers by vm.capabilities.collectAsStateWithLifecycle();val phoneProviders by vm.phoneProviders.collectAsStateWithLifecycle()
    val context=LocalContext.current;val scope=rememberCoroutineScope();val snackbar=remember{SnackbarHostState()}
    val prefs=remember{context.getSharedPreferences("wardrobe-ui",0)}
    var locationGranted by remember{mutableStateOf(hasLocationPermission(context))}
    var showLocationIntro by remember{mutableStateOf(!locationGranted&&!prefs.getBoolean(LOCATION_INTRO_KEY,false))}
    val weatherReady by vm.weatherReady.collectAsStateWithLifecycle()
    val locationRequest=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->
        prefs.edit().putBoolean(LOCATION_INTRO_KEY,true).apply()
        val granted=result[Manifest.permission.ACCESS_FINE_LOCATION]==true||result[Manifest.permission.ACCESS_COARSE_LOCATION]==true
        locationGranted=granted
        if(granted)vm.loadCurrentWeather() else vm.message.value="Location is off. Enter a city for your forecast."
    }
    val lifecycleOwner=LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)locationGranted=hasLocationPermission(context)}
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose{lifecycleOwner.lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(Unit){vm.refreshGreeting()}
    LaunchedEffect(locationGranted,weatherReady,showLocationIntro){
        if(locationGranted&&weatherReady&&!showLocationIntro&&vm.weatherPlace.value.isBlank())vm.loadCurrentWeather()
    }
    fun requestLocation(){
        prefs.edit().putBoolean(LOCATION_INTRO_KEY,true).apply()
        showLocationIntro=false
        if(locationGranted)vm.loadCurrentWeather() else locationRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    fun skipLocation(){
        prefs.edit().putBoolean(LOCATION_INTRO_KEY,true).apply()
        showLocationIntro=false
    }
    var page by remember{mutableIntStateOf(0)};var camera by remember{mutableStateOf("")};var targetReference by remember{mutableStateOf(false)}
    var crop by remember{mutableStateOf<Bitmap?>(null)};var ai by remember{mutableStateOf<AiChoice?>(null)};var restoreUri by remember{mutableStateOf<Uri?>(null)}
    fun readPhoto(uri:Uri){scope.launch{try{crop=withContext(Dispatchers.IO){vm.repository.photos.read(uri)}}catch(error:Exception){vm.message.value=error.message?:"Photo unavailable"}}}
    val gallery=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){it?.let{uri->readPhoto(uri)}}
    val backup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){it?.let{vm.backup(it)}}
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){restoreUri=it}
    val exportImage=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")){uri->if(uri!=null){val file=collage?:preview?.let{vm.repository.photos.file(it)};if(file!=null)vm.action{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use{out->file.inputStream().use{it.copyTo(out)}}?:error("Could not export image")};vm.message.value="Image exported"}}}
    LaunchedEffect(message){if(message.isNotBlank()){snackbar.showSnackbar(message);if(vm.message.value==message)vm.message.value=""}}
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)){
        Scaffold(containerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onBackground,snackbarHost={SnackbarHost(snackbar)},bottomBar={WardrobeNavigationBar(page){page=it}}) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)){
                if(busy)LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp),color=MaterialTheme.colorScheme.secondary,trackColor=MaterialTheme.colorScheme.secondary.copy(alpha=.14f))
                AnimatedContent(
                    targetState=page,
                    modifier=Modifier.fillMaxSize(),
                    transitionSpec={
                        val forward=targetState>initialState
                        val enter=slideInHorizontally(animationSpec=spring(dampingRatio=1f,stiffness=Spring.StiffnessMediumLow)){width->if(forward)width/10 else -width/10}+fadeIn(tween(190))
                        val exit=slideOutHorizontally(animationSpec=tween(170)){width->if(forward)-width/14 else width/14}+fadeOut(tween(130))
                        enter togetherWith exit
                    },
                    label="main navigation",
                ){current->
                    when(current){
                        0->TodayScreen(vm,data,{ai=it},{page=1;vm.newItem()},locationGranted,{requestLocation()})
                        1->WardrobeScreen(vm,data)
                        2->OutfitsScreen(vm,data){ai=it}
                        3->RecommendationsScreen(vm,data){ai=it}
                        else->ConnectionsScreen(vm,{camera="pair"},{backup.launch("Wardrobe-backup.zip")},{restore.launch(arrayOf("application/zip","application/octet-stream"))},{vm.profileDraft.value=data.profile},appearance,onAppearance)
                    }
                }
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
    if(shownImage!=null)Dialog({vm.collage.value=null;vm.preview.value=null},properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){Column(Modifier.systemBarsPadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){Text(if(collage!=null)"Your outfit board"else"Approximate try-on",style=MaterialTheme.typography.headlineSmall);if(preview!=null)Text("Generated visualization. Fit, proportions, colors, and small details can differ from the real clothes.");AsyncImage(shownImage,"Outfit preview",Modifier.fillMaxWidth().weight(1f),contentScale=ContentScale.Fit);Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){OutlinedButton({vm.collage.value=null;vm.preview.value=null},Modifier.weight(1f)){Text("Close")};Button({exportImage.launch("Wardrobe-outfit.jpg")},Modifier.weight(1f)){Text("Export")}}}}}
    if(advice.isNotEmpty())AlertDialog(onDismissRequest={vm.advice.value=""},title={Text("Styling advice")},text={Text(advice,Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton({vm.advice.value=""}){Text("Done")}})
    restoreUri?.let{uri->AlertDialog(onDismissRequest={restoreUri=null},title={Text("Restore your wardrobe?")},text={Text("Matching records in this backup replace the saved versions. Other current items remain. Connection credentials are excluded.")},confirmButton={Button({vm.restore(uri);restoreUri=null}){Text("Restore")}},dismissButton={TextButton({restoreUri=null}){Text("Cancel")}})}
    if(showLocationIntro)LocationIntroDialog({requestLocation()},{skipLocation()})
}

@Composable private fun WardrobeNavigationBar(page:Int,onPage:(Int)->Unit){
    val items=listOf(
        "Today" to Icons.Outlined.Home,
        "Wardrobe" to Icons.AutoMirrored.Outlined.List,
        "Outfits" to Icons.Outlined.FavoriteBorder,
        "Discover" to Icons.Outlined.Search,
        "Connect" to Icons.Outlined.Settings,
    )
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding()){
        Hairline(emphasis=true)
        Row(Modifier.fillMaxWidth().height(58.dp),verticalAlignment=Alignment.CenterVertically){
            items.forEachIndexed{index,(title,icon)->
                val active=page==index
                val tint=if(active)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.62f)
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable{onPage(index)},
                    horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.Center,
                ){
                    Icon(icon,title,Modifier.size(19.dp),tint=tint)
                    Spacer(Modifier.height(4.dp))
                    Text(title,style=MaterialTheme.typography.labelSmall.copy(letterSpacing=.2.sp,fontWeight=if(active)FontWeight.SemiBold else FontWeight.Medium),color=tint,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.width(16.dp).height(1.5.dp).background(if(active)MaterialTheme.colorScheme.primary else Color.Transparent))
                }
            }
        }
    }
}

@Composable private fun Hairline(modifier:Modifier=Modifier,emphasis:Boolean=false){
    Box(modifier.fillMaxWidth().height(1.dp).background(if(emphasis)MaterialTheme.colorScheme.outline.copy(alpha=.55f) else MaterialTheme.colorScheme.outlineVariant))
}

@Composable private fun Kicker(text:String,modifier:Modifier=Modifier,color:Color=Color.Unspecified){
    Text(
        text.uppercase(),
        modifier,
        style=MaterialTheme.typography.labelSmall.copy(letterSpacing=1.5.sp),
        color=if(color==Color.Unspecified)MaterialTheme.colorScheme.secondary else color,
        maxLines=1,
        overflow=TextOverflow.Ellipsis,
    )
}

@Composable private fun TextAction(text:String,enabled:Boolean=true,color:Color=Color.Unspecified,onClick:()->Unit){
    TextButton(
        onClick=onClick,
        enabled=enabled,
        shape=RoundedCornerShape(2.dp),
        contentPadding=PaddingValues(horizontal=0.dp,vertical=0.dp),
    ){
        Text(
            text,
            style=MaterialTheme.typography.labelLarge.copy(letterSpacing=.2.sp),
            color=if(color==Color.Unspecified)MaterialTheme.colorScheme.primary else color,
        )
    }
}

// Material buttons default to full capsules. Wardrobe's are cut square like a tailor's chalk line.
@Composable private fun Button(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,shape:Shape=RoundedCornerShape(2.dp),content:@Composable RowScope.()->Unit){
    M3Button(onClick=onClick,modifier=modifier,enabled=enabled,shape=shape,content=content)
}

@Composable private fun OutlinedButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,shape:Shape=RoundedCornerShape(2.dp),content:@Composable RowScope.()->Unit){
    M3OutlinedButton(onClick=onClick,modifier=modifier,enabled=enabled,shape=shape,content=content)
}

@Composable private fun Masthead(greeting:String){
    val today=remember{java.text.SimpleDateFormat("EEEE d MMMM",java.util.Locale.getDefault()).format(java.util.Date())}
    Column(Modifier.fillMaxWidth()){
        Kicker(today)
        Spacer(Modifier.height(8.dp))
        Text("Wardrobe",style=MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(7.dp))
        Text(greeting,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Hairline(emphasis=true)
    }
}

@Composable private fun ScreenHeader(kicker:String,title:String,subtitle:String="",action:(@Composable ()->Unit)?=null){
    Column(Modifier.fillMaxWidth()){
        Row(verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Kicker(kicker)
                Spacer(Modifier.height(6.dp))
                Text(title,style=MaterialTheme.typography.headlineLarge)
            }
            action?.invoke()
        }
        if(subtitle.isNotBlank()){
            Spacer(Modifier.height(7.dp))
            Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        Hairline(emphasis=true)
    }
}

@Composable private fun Header(title:String,subtitle:String,action:(@Composable ()->Unit)?=null){
    Column(Modifier.fillMaxWidth()){
        Row(verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text(title,style=MaterialTheme.typography.headlineSmall)
                if(subtitle.isNotBlank()){
                    Spacer(Modifier.height(4.dp))
                    Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            action?.invoke()
        }
        Spacer(Modifier.height(14.dp))
        Hairline()
    }
}

@Composable private fun Note(title:String,text:String){
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)){
        Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.secondary))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(vertical=2.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
            Text(title,style=MaterialTheme.typography.titleMedium)
            Text(text,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun Select(label:String,value:String,options:List<String>,onSelect:(String)->Unit){
    var open by remember{mutableStateOf(false)}
    Box{
        Column(Modifier.fillMaxWidth().clickable{open=true}.padding(vertical=6.dp)){
            Text(label.uppercase(),style=MaterialTheme.typography.labelSmall.copy(letterSpacing=1.2.sp),color=MaterialTheme.colorScheme.secondary,maxLines=1,overflow=TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment=Alignment.CenterVertically){
                Text(value.replaceFirstChar{it.uppercase()},Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                Icon(Icons.Outlined.ArrowDropDown,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(7.dp))
            Hairline()
        }
        DropdownMenu(open,{open=false}){options.forEach{option->DropdownMenuItem(text={Text(option.replaceFirstChar{it.uppercase()})},onClick={onSelect(option);open=false})}}
    }
}

@Composable private fun TextTabs(options:List<String>,selected:String,onSelect:(String)->Unit,modifier:Modifier=Modifier,labelFor:(String)->String={it.replaceFirstChar{character->character.uppercase()}}){
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(20.dp)){
        options.forEach{option->
            val active=option==selected
            Column(Modifier.clickable{onSelect(option)}){
                Text(labelFor(option),style=MaterialTheme.typography.titleSmall,color=if(active)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.82f),maxLines=1)
                Spacer(Modifier.height(7.dp))
                Box(Modifier.fillMaxWidth().height(1.5.dp).background(if(active)MaterialTheme.colorScheme.primary else Color.Transparent))
            }
        }
    }
}

@Composable private fun Field(label:String,value:String,onChange:(String)->Unit,help:String="",singleLine:Boolean=true){
    OutlinedTextField(
        value,onChange,Modifier.fillMaxWidth(),
        label={Text(label,style=MaterialTheme.typography.labelMedium)},
        singleLine=singleLine,
        shape=RoundedCornerShape(2.dp),
        textStyle=MaterialTheme.typography.bodyLarge,
        supportingText=if(help.isNotEmpty()){{Text(help)}}else null,
        colors=OutlinedTextFieldDefaults.colors(
            focusedBorderColor=MaterialTheme.colorScheme.primary,
            unfocusedBorderColor=MaterialTheme.colorScheme.outlineVariant,
            focusedContainerColor=Color.Transparent,
            unfocusedContainerColor=Color.Transparent,
            focusedLabelColor=MaterialTheme.colorScheme.primary,
            unfocusedLabelColor=MaterialTheme.colorScheme.onSurfaceVariant,
            cursorColor=MaterialTheme.colorScheme.primary,
        ),
    )
}
@OptIn(ExperimentalLayoutApi::class) @Composable private fun Tags(options:List<String>,selected:List<String>,onChange:(List<String>)->Unit){FlowRow(horizontalArrangement=Arrangement.spacedBy(7.dp)){options.forEach{tag->FilterChip(tag in selected,{onChange(if(tag in selected)selected-tag else selected+tag)},label={Text(tag)})}}}
@Composable private fun Check(label:String,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth().clickable{onChange(!value)},verticalAlignment=Alignment.CenterVertically){Checkbox(value,onChange);Text(label,Modifier.weight(1f))}}
private fun Garment.photoId()=cutoutId.ifEmpty{imageIds.firstOrNull().orEmpty()}
private fun WardrobeViewModel.garmentPhoto(item:Garment):File?=item.photoId().takeIf{it.isNotEmpty()}?.let{runCatching{repository.photos.file(it)}.getOrNull()}?.takeIf{it.exists()}
@Composable private fun GarmentImage(vm:WardrobeViewModel,item:Garment,modifier:Modifier=Modifier){
    val photo=vm.garmentPhoto(item) ?: return
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow),contentAlignment=Alignment.Center){
        AsyncImage(photo,item.name,Modifier.fillMaxSize().padding(12.dp),contentScale=ContentScale.Fit)
    }
}
@Composable private fun Link(label:String,url:String){val context=LocalContext.current;TextButton({runCatching{val uri=Uri.parse(url);require(uri.scheme=="https");context.startActivity(Intent(Intent.ACTION_VIEW,uri))}}){Text(label)}}

@OptIn(ExperimentalLayoutApi::class) @Composable private fun WeatherStrip(vm:WardrobeViewModel,now:WeatherNow?,locating:Boolean,hasLocation:Boolean,onUseLocation:()->Unit){
    val saved by vm.weatherPlace.collectAsStateWithLifecycle()
    var editCity by remember{mutableStateOf(false)}
    var city by remember{mutableStateOf("")}
    fun openCity(){city=if(saved=="Current location") "" else saved;editCity=true}
    Column(Modifier.fillMaxWidth()){
        Spacer(Modifier.height(13.dp))
        Row(verticalAlignment=Alignment.CenterVertically){
            Icon(Icons.Outlined.LocationOn,null,Modifier.size(14.dp),tint=MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(6.dp))
            Text(now?.place?.takeIf{it.isNotBlank()}?:"Your local forecast",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium.copy(letterSpacing=.4.sp),color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(locating)CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=1.5.dp)
            else if(now!=null)TextAction("Change"){openCity()}
        }
        Spacer(Modifier.height(10.dp))
        if(now!=null){
            Row(verticalAlignment=Alignment.Bottom){
                Text("${now.temperature.roundToInt()}°",style=MaterialTheme.typography.displayMedium)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).padding(bottom=4.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
                    Text(now.summary,style=MaterialTheme.typography.titleMedium)
                    Text("Feels like ${now.feelsLike.roundToInt()}°",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Kicker(now.band,Modifier.padding(bottom=6.dp),MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(11.dp))
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(18.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
                Text("High ${now.high.roundToInt()}°",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Low ${now.low.roundToInt()}°",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Open-Meteo",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.7f))
            }
            Spacer(Modifier.height(2.dp))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(20.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
                TextAction("Change city"){openCity()}
                TextAction("Use my location",enabled=!locating,onClick=onUseLocation)
            }
            Text("Your coordinates go to Open-Meteo only for this forecast. They never enter your wardrobe or reach AI.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.8f))
        }else{
            Text("Your forecast sets the warmth for today's looks.",style=MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text("Allow location once, or name a city. Nothing about your wardrobe leaves this phone.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            if(locating){
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=1.5.dp);Text("Finding your local forecast…",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }else{
                FlowRow(horizontalArrangement=Arrangement.spacedBy(20.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    TextAction(if(hasLocation)"Update weather" else "Use my location",onClick=onUseLocation)
                    TextAction("Enter a city"){openCity()}
                }
                if(!hasLocation)Text("Location is off. A city works just as well.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(13.dp))
        Hairline()
    }
    if(editCity)AlertDialog(
        onDismissRequest={editCity=false},
        title={Text("Weather city")},
        text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text("Use a town or city when you prefer not to share your location.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);Field("City",city,{city=it},"Town or city for the forecast")}},
        confirmButton={Button({val value=city.trim();if(value.isNotBlank()){editCity=false;vm.loadWeather(value)}},enabled=city.isNotBlank()){Text("Update weather")}},
        dismissButton={TextButton({editCity=false}){Text("Cancel")}},
    )
}

@Composable private fun LocationIntroDialog(onAllow:()->Unit,onSkip:()->Unit){
    AlertDialog(
        onDismissRequest=onSkip,
        title={Text("Weather where you are")},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("Wardrobe can use your approximate location to fetch today's forecast and choose the right warmth for your looks.",style=MaterialTheme.typography.bodyMedium)
            Text("Your coordinates go to Open-Meteo only for the forecast and are cached on this phone to refresh it. They are not added to your wardrobe, sent to AI, or shared with your PC.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }},
        confirmButton={Button(onAllow){Text("Use my location")}},
        dismissButton={TextButton(onSkip){Text("Not now")}},
    )
}

@Composable private fun TodayScreen(vm:WardrobeViewModel,data:WardrobeSnapshot,onAi:(AiChoice)->Unit,onAdd:()->Unit,hasLocation:Boolean,onUseLocation:()->Unit){
    val outfits by vm.generated.collectAsStateWithLifecycle();val mode by vm.mode.collectAsStateWithLifecycle();val occasion by vm.occasion.collectAsStateWithLifecycle();val weather by vm.weather.collectAsStateWithLifecycle();val pinned by vm.pinned.collectAsStateWithLifecycle();val season by vm.season.collectAsStateWithLifecycle();val weatherNow by vm.weatherNow.collectAsStateWithLifecycle();val greeting by vm.greeting.collectAsStateWithLifecycle();val locating by vm.locating.collectAsStateWithLifecycle()
    val topOutfits=outfits.sortedByDescending{it.score}.take(3)
    val weatherDescription=weatherNow?.let{"today's ${it.band} weather"}?:"today's forecast"
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=20.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
        item{Masthead(greeting)}
        item{WeatherStrip(vm,weatherNow,locating,hasLocation,onUseLocation)}
        if(pinned.isNotEmpty())item{
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){Kicker("Building around");Spacer(Modifier.height(3.dp));Text(data.garments.find{it.id==pinned}?.name?:"An item you picked",style=MaterialTheme.typography.titleSmall)}
                TextAction("Clear"){vm.pinned.value=""}
            }
        }
        if(data.garments.isEmpty())item{
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)){
                Note("A wardrobe that starts with you","Add your own tops, bottoms or one-piece garments, and shoes. Matching works offline, with no AI setup.")
                Button(onAdd,Modifier.fillMaxWidth().height(50.dp),shape=RoundedCornerShape(2.dp)){Text("Add your first piece")}
            }
        }
        else if(topOutfits.isEmpty())item{Note("A few more pieces unlock looks","An outfit needs a top and bottom, or a one-piece garment, plus shoes. Cold weather adds outerwear. Check availability or adjust the occasion.")}
        else{
            item{
                Column(Modifier.fillMaxWidth()){
                    Kicker("Today's edit")
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment=Alignment.Bottom){
                        Text(if(topOutfits.size==1)"One look for today" else "${topOutfits.size} looks, in order",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall)
                        Text("From ${data.garments.size} pieces",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Ranked for $weatherDescription. The first is our recommendation; the rest are alternatives.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(topOutfits.size){index->
                Column(Modifier.fillMaxWidth()){
                    if(index>0){Hairline();Spacer(Modifier.height(18.dp))}
                    LookCard(vm,topOutfits[index],data,"Look 0${index+1}",if(index==0)"Recommended" else "Alternative",onAi,swappable=true,featured=index==0)
                }
            }
        }
        item{
            Column(Modifier.fillMaxWidth()){
                Hairline()
                Spacer(Modifier.height(16.dp))
                Kicker("Refine")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(20.dp)){
                    Box(Modifier.weight(1f)){Select("Style",mode,listOf("timeless","balanced","trend")){vm.mode.value=it}}
                    Box(Modifier.weight(1f)){Select("Season",season,listOf("all","spring","summer","autumn","winter")){vm.season.value=it}}
                }
                Row(horizontalArrangement=Arrangement.spacedBy(20.dp)){
                    Box(Modifier.weight(1f)){Select("Occasion",occasion,listOf("any","casual","formal")){vm.occasion.value=it}}
                    Box(Modifier.weight(1f)){Select("Weather",weather,listOf("mild","warm","cold")){vm.weather.value=it}}
                }
                Spacer(Modifier.height(4.dp))
                val weights=vm.engine.rules.weights[mode]?.takeIf{it.size>=3}
                val weighting=if(weights==null)"weighting follows the shared styling rules" else "${(weights[0]*100).roundToInt()}% styling principles, ${(weights[1]*100).roundToInt()}% your preferences, ${(weights[2]*100).roundToInt()}% supported trends"
                Text(when(mode){"timeless"->"Timeless · ";"trend"->"Trend focused · ";else->"Balanced · "}+weighting,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class) @Composable private fun LookCard(vm:WardrobeViewModel,outfit:Outfit,data:WardrobeSnapshot,title:String,marker:String,onAi:(AiChoice)->Unit,swappable:Boolean=false,featured:Boolean=false,onDelete:(()->Unit)?=null){
    val items=outfit.itemIds.mapNotNull{id->data.garments.find{it.id==id}}
    val valid=items.size==outfit.itemIds.size&&vm.engine.valid(items,outfit.occasion,outfit.weather)
    val tall=if(featured)230 else 124
    val short=if(featured)112 else 86
    val reason=outfit.reasons.joinToString(" ").trim()
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(11.dp)){
        Row(verticalAlignment=Alignment.Bottom){
            Column(Modifier.weight(1f)){
                Kicker(marker)
                Spacer(Modifier.height(4.dp))
                Text(title,style=if(featured)MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            Text("${outfit.score.toInt()}",style=MaterialTheme.typography.headlineSmall)
            Text("/100",Modifier.padding(bottom=3.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val photos=items.filter{vm.garmentPhoto(it)!=null}
        if(photos.isNotEmpty()){
            if(photos.size>3){
                Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
                    Row(horizontalArrangement=Arrangement.spacedBy(2.dp)){photos.take(2).forEach{item->GarmentImage(vm,item,Modifier.weight(1f).height(tall.dp).clickable{vm.edit(item)})}}
                    Row(horizontalArrangement=Arrangement.spacedBy(2.dp)){photos.drop(2).forEach{item->GarmentImage(vm,item,Modifier.weight(1f).height(short.dp).clickable{vm.edit(item)})}}
                }
            }else{
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(2.dp)){photos.forEach{item->GarmentImage(vm,item,Modifier.weight(1f).height(tall.dp).clickable{vm.edit(item)})}}
            }
        }
        if(items.isNotEmpty())Text(items.joinToString(" · "){it.name},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=if(featured)3 else 2,overflow=TextOverflow.Ellipsis)
        if(reason.isNotEmpty())Text(reason,style=if(featured)MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=if(featured)4 else 2,overflow=TextOverflow.Ellipsis)
        if(!valid)Text("A piece is missing or unavailable. Edit the wardrobe or save a new combination.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
        if(featured){
            Text("Principles ${outfit.foundation.toInt()} · Preferences ${outfit.personal.toInt()} · Trends ${outfit.trend.toInt()}",style=MaterialTheme.typography.labelSmall.copy(letterSpacing=.8.sp),color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.85f))
            outfit.trendSources.forEach{source->Text(source["title"]?.toString().orEmpty(),style=MaterialTheme.typography.labelMedium);(source["sources"] as? List<*>)?.filterIsInstance<String>()?.forEach{Link("Trend source",it)}}
        }
        if(outfit.previewImageId.isNotEmpty())TextAction("View saved try-on"){vm.preview.value=outfit.previewImageId}
        FlowRow(horizontalArrangement=Arrangement.spacedBy(18.dp)){
            TextAction("Board",enabled=valid){vm.showCollage(outfit)}
            TextAction("Save",enabled=valid){vm.saveOutfit(outfit)}
            TextAction("Try-on",enabled=valid){onAi(AiChoice("image",outfit=outfit))}
            TextAction("Advice",enabled=valid){onAi(AiChoice("text",outfit=outfit))}
            if(swappable)items.forEach{item->TextAction("Swap ${item.subcategory.ifBlank{item.category}}",enabled=valid){vm.swap(outfit,item.id)}}
            TextAction("Would wear",enabled=valid){vm.feedback(outfit,true)}
            TextAction("Not my style",enabled=valid){vm.feedback(outfit,false)}
            onDelete?.let{remove->TextAction("Remove",color=MaterialTheme.colorScheme.error,onClick=remove)}
        }
    }
}

@Composable private fun WardrobeScreen(vm:WardrobeViewModel,data:WardrobeSnapshot){
    var filter by remember{mutableStateOf("all")}
    val items=data.garments.filter{filter=="all"||it.category==filter}
    Column(Modifier.fillMaxSize()){
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=20.dp)){
            ScreenHeader("Collection","Wardrobe","${data.garments.size} pieces · kept on this phone")
        }
        TextTabs(listOf("all")+vm.engine.rules.categories,filter,{filter=it},Modifier.padding(horizontal=20.dp),labelFor={if(it=="onepiece")"One-piece" else it.replaceFirstChar{character->character.uppercase()}})
        Spacer(Modifier.height(16.dp))
        if(items.isEmpty()){
            Box(Modifier.weight(1f).padding(horizontal=20.dp),contentAlignment=Alignment.TopStart){
                Note(if(filter=="all")"An empty rail" else "Nothing in ${filter}",if(filter=="all")"Photograph, import or add a piece by hand. Your wardrobe begins empty and stays on this phone." else "No pieces are filed under this category yet.")
            }
        }else{
            LazyVerticalGrid(
                GridCells.Fixed(2),
                Modifier.weight(1f),
                contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=24.dp,top=4.dp),
                verticalArrangement=Arrangement.spacedBy(24.dp),
                horizontalArrangement=Arrangement.spacedBy(12.dp),
            ){
                items(items,key={it.id}){item->
                    Column(Modifier.clickable{vm.edit(item)}){
                        if(vm.garmentPhoto(item)!=null){
                            GarmentImage(vm,item,Modifier.fillMaxWidth().height(206.dp))
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(item.name,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text("${item.subcategory} · ${item.status}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                        val brand=item.brand.takeIf{it.isNotBlank()}?.let{if(item.brandConfirmed)it else "$it · Unconfirmed"}
                        if(brand!=null)Text(brand,style=MaterialTheme.typography.labelSmall.copy(letterSpacing=.6.sp),color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.75f),maxLines=1,overflow=TextOverflow.Ellipsis)
                        TextAction("Build around this"){vm.pinned.value=item.id;vm.message.value="Today will build around ${item.name}"}
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)){
            Hairline()
            Box(Modifier.padding(horizontal=20.dp,vertical=10.dp)){
                Button({vm.newItem()},Modifier.fillMaxWidth().height(50.dp),shape=RoundedCornerShape(2.dp)){
                    Icon(Icons.Outlined.Add,null,Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add a piece")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class) @Composable private fun Editor(vm:WardrobeViewModel,item:Garment,data:WardrobeSnapshot,onPhoto:(Boolean)->Unit,onAnalysis:(Garment)->Unit,onDetect:(Garment)->Unit){
    var delete by remember{mutableStateOf(false)};val existing=data.garments.find{it.id==item.id};val scope=rememberCoroutineScope()
    Dialog({vm.draft.value=null},properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Header(if(existing==null)"Add a piece"else"Edit your piece","Your corrections stay authoritative"){IconButton({vm.draft.value=null}){Icon(Icons.Outlined.Close,"Close")}}
        if(vm.garmentPhoto(item)!=null)GarmentImage(vm,item,Modifier.fillMaxWidth().height(250.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({onPhoto(true)},Modifier.weight(1f)){Text("Camera")};OutlinedButton({onPhoto(false)},Modifier.weight(1f)){Text("Import photo")}}
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

@Composable private fun ProfileEditor(vm:WardrobeViewModel,profile:Profile,onPhoto:(Boolean)->Unit){var colorsText by remember{mutableStateOf(profile.favoriteColors.joinToString(", "))};var budgetText by remember{mutableStateOf(if(profile.shoppingBudget==0.0)""else profile.shoppingBudget.toString())};Dialog({vm.profileDraft.value=null},properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
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

@Composable private fun OutfitsScreen(vm:WardrobeViewModel,data:WardrobeSnapshot,onAi:(AiChoice)->Unit){
    val generated by vm.generated.collectAsStateWithLifecycle()
    var tab by remember{mutableStateOf("saved")}
    val saved=data.outfits
    val shown=if(tab=="saved")saved else generated.sortedByDescending{it.score}.take(8)
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=20.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
        item{ScreenHeader("Your collection","Outfits","${saved.size} saved · ${generated.size} generated from what you own")}
        item{TextTabs(listOf("saved","generated"),tab,{tab=it})}
        if(tab=="saved"&&saved.isEmpty())item{Note("No saved looks yet","When a look from Today feels right, save it here. It stays linked to the actual pieces in your wardrobe.")}
        if(tab=="generated"&&generated.isEmpty())item{Note("Nothing generated yet","Add a top and bottom, or a one-piece garment, plus shoes. Matching runs offline.")}
        items(shown.size){index->
            val outfit=shown[index]
            val remove=if(tab=="saved")({vm.deleteOutfit(outfit)})else null
            Column(Modifier.fillMaxWidth()){
                if(index>0){Hairline();Spacer(Modifier.height(16.dp))}
                val title=if(tab=="saved")outfit.name.ifBlank{"Saved look ${index+1}"} else "Look ${(index+1).toString().padStart(2,'0')}"
                LookCard(vm,outfit,data,title,if(tab=="saved")"Saved" else "Generated",onAi,featured=index==0,onDelete=remove)
            }
        }
    }
}

@Composable private fun RecommendationsScreen(vm:WardrobeViewModel,data:WardrobeSnapshot,onAi:(AiChoice)->Unit){
    val products by vm.searchProducts.collectAsStateWithLifecycle()
    val feeds by vm.feeds.collectAsStateWithLifecycle()
    val trends by vm.trends.collectAsStateWithLifecycle()
    val gaps=vm.engine.gaps(data.garments)
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=20.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(22.dp)){
        item{ScreenHeader("Discover","Wardrobe gaps","What would unlock more looks, using only the pieces you own. Prices, stock and trends are never invented.")}
        if(data.garments.isEmpty())item{Note("Start with your wardrobe","Add a few pieces and this page will show what would unlock more looks. Prices and availability are never guessed.")}
        if(data.garments.isNotEmpty())items(gaps.size){index->
            val gap=gaps[index]
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)){
                if(index>0){Hairline();Spacer(Modifier.height(6.dp))}
                Row(verticalAlignment=Alignment.Top){
                    Text((index+1).toString().padStart(2,'0'),Modifier.width(30.dp),style=MaterialTheme.typography.labelSmall.copy(letterSpacing=1.sp),color=MaterialTheme.colorScheme.secondary)
                    Column(Modifier.weight(1f)){
                        Text(gap.title,style=MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(5.dp))
                        Text(gap.reason,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val matches=data.garments.filter{it.id in gap.matchingItemIds&&vm.garmentPhoto(it)!=null}
                if(matches.isNotEmpty()){
                    Kicker("Works with")
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                        matches.take(6).forEach{item->Column(Modifier.width(78.dp)){GarmentImage(vm,item,Modifier.fillMaxWidth().height(96.dp));Spacer(Modifier.height(4.dp));Text(item.name,style=MaterialTheme.typography.labelSmall.copy(letterSpacing=.3.sp),maxLines=2,overflow=TextOverflow.Ellipsis)}}
                    }
                }
                val hypothetical=Garment("recommendation",gap.title,gap.category,color=gap.color,texture=gap.texture)
                val examples=vm.engine.generate(data.garments+hypothetical,data.profile,pinned="recommendation").filter{"recommendation" in it.itemIds}.take(2)
                examples.forEach{outfit->Text("Would enable: "+outfit.itemIds.map{id->if(id=="recommendation")gap.title else data.garments.find{it.id==id}?.name.orEmpty()}.joinToString(" + "),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                TextAction("Find actual products"){onAi(AiChoice("search",gap=gap))}
            }
        }
        if(products.size()>0)item{
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)){
                Hairline()
                Kicker("Retrieved products")
                Text("Check size, price and availability on the retailer’s website.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(products.size()){i->
            val product=products[i].asJsonObject
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(5.dp)){
                if(i>0)Hairline()
                Spacer(Modifier.height(6.dp))
                Text(product.string("name","Product"),style=MaterialTheme.typography.titleMedium)
                Text(listOf(product.string("brand"),product.string("price","Unavailable")+" "+product.string("currency")).filter{it.isNotBlank()}.joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Variant not yet confirmed · inspect the exact colour and size",style=MaterialTheme.typography.labelSmall.copy(letterSpacing=.5.sp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Sizes: ${product.string("size","Check retailer")} · Checked: ${product.string("checkedAt","Unknown")}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                val matches=data.garments.filter{g->product["matchingItemIds"]?.asJsonArray?.any{it.asString==g.id}==true}
                if(matches.isNotEmpty())Text("Works with: "+matches.joinToString{it.name},style=MaterialTheme.typography.bodySmall)
                product["exampleOutfits"]?.asJsonArray?.forEach{example->Text("Would enable: "+example.asJsonObject["itemIds"].asJsonArray.joinToString(" + "){id->if(id.asString=="recommendation")product.string("name")else data.garments.find{it.id==id.asString}?.name.orEmpty()},style=MaterialTheme.typography.bodySmall)}
                Link("View product and available variants",product.string("url"))
            }
        }
        if(feeds.size()>0||trends.size>0)item{
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Hairline()
                Kicker("From your sources")
                Text("${feeds.size()} source updates · ${trends.size} supported trends. A source influences looks only after structured analysis with evidence.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                for(i in 0 until minOf(feeds.size(),6)){
                    val feed=feeds[i].asJsonObject
                    Column(Modifier.fillMaxWidth().padding(top=6.dp)){
                        Text(feed.string("title"),style=MaterialTheme.typography.titleSmall)
                        Text(feed.string("publishedAt",feed.string("date")),style=MaterialTheme.typography.labelSmall.copy(letterSpacing=.6.sp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Link("Read article",feed.string("url"))
                    }
                }
            }
        }
    }
}

@Composable private fun ConnectionsScreen(vm:WardrobeViewModel,onScan:()->Unit,onBackup:()->Unit,onRestore:()->Unit,onProfile:()->Unit,appearance:String,onAppearance:(String)->Unit){
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val last by vm.lastRefresh.collectAsStateWithLifecycle()
    val providers by vm.providerList.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    var pairing by remember{mutableStateOf("")}
    var disconnect by remember{mutableStateOf(false)}
    var expanded by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(vm.repository.connection.paired){vm.loadProviders()}
    val paired=vm.repository.connection.paired
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=20.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(22.dp)){
        item{ScreenHeader("Settings","Connect","Sync, keys and appearance. Private by design.")}
        item{
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                Kicker("PC companion")
                Row(verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.size(7.dp).background(if(paired)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,CircleShape))
                    Spacer(Modifier.width(9.dp))
                    Text(state,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                }
                Text(if(paired)vm.repository.connection.url else "Open the PC dashboard, put both devices on your Tailscale network, then scan the pairing QR. The PC must be awake and the server running.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(!paired){
                    Button(onScan,Modifier.fillMaxWidth().height(48.dp),shape=RoundedCornerShape(2.dp)){Text("Scan pairing QR")}
                    Field("Pairing text",pairing,{pairing=it},"Paste the complete pairing text from the PC",false)
                    Button({vm.pair(pairing)},Modifier.fillMaxWidth().height(48.dp),shape=RoundedCornerShape(2.dp),enabled=pairing.isNotBlank()){Text("Pair with PC")}
                }else{
                    Button({vm.refresh()},Modifier.fillMaxWidth().height(48.dp),shape=RoundedCornerShape(2.dp)){
                        Icon(Icons.Outlined.Refresh,null,Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Refresh database")
                    }
                    Text("Last successful refresh: $last",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    TextAction("Disconnect this PC"){disconnect=true}
                }
            }
        }
        item{
            Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
                Hairline()
                Spacer(Modifier.height(4.dp))
                Kicker("AI connections")
                Text("Matching, photo colours, outfit boards and framing work without AI. Add a Gemini or OpenAI key, or sign in with your ChatGPT plan; analysis and advice then run on this phone. A paired PC covers image generation and product search.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(providers.isEmpty())item{OutlinedButton({vm.loadProviders()},Modifier.fillMaxWidth().height(48.dp),shape=RoundedCornerShape(2.dp)){Text("Load AI connections")}}
        items(providers.size){i->
            val provider=providers[i]
            val open=expanded==provider.id
            Column(Modifier.fillMaxWidth()){
                if(i>0)Hairline()
                Row(Modifier.fillMaxWidth().clickable{expanded=if(open)null else provider.id}.padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){
                        Text(providerName(provider.id),style=MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(providerStatus(provider),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextAction(if(open)"Close" else "Manage"){expanded=if(open)null else provider.id}
                }
                if(open)ProviderSetupCard(vm,provider)
            }
        }
        item{
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                Hairline()
                Kicker("Appearance")
                Text("Wardrobe has separate light and dark designs. Follow the phone, or keep one.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextTabs(listOf("system","light","dark"),appearance,onAppearance,labelFor={if(it=="system")"System" else it.replaceFirstChar{character->character.uppercase()}})
            }
        }
        item{
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Hairline()
                Kicker("Your style")
                Text("Preferred styles, colours, fit, budget and your try-on reference shape how looks are ranked.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextAction("Edit style preferences",onClick=onProfile)
            }
        }
        if(jobs.isNotEmpty())item{
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                Hairline()
                Kicker("PC requests")
                jobs.forEach{job->
                    Column(Modifier.fillMaxWidth().padding(vertical=4.dp)){
                        Text("${job.kind.replace('_',' ')} · ${job.provider}",style=MaterialTheme.typography.titleSmall)
                        Text(job.status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(job.error.isNotBlank())Text(job.error,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                        if(job.status=="completed")TextAction("View result"){vm.openJob(job)}
                        if(job.status in listOf("queued","running"))TextAction("Cancel request"){vm.cancelJob(job)}
                    }
                }
            }
        }
        item{
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                Hairline()
                Kicker("Backup and restore")
                Text("Includes wardrobe, photos, preferences, saved outfits and styling data. Connection and API credentials are excluded.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    OutlinedButton(onBackup,Modifier.weight(1f).height(46.dp),shape=RoundedCornerShape(2.dp)){Text("Export")}
                    OutlinedButton(onRestore,Modifier.weight(1f).height(46.dp),shape=RoundedCornerShape(2.dp)){Text("Restore")}
                }
            }
        }
        item{
            Text("Wardrobe ${BuildConfig.VERSION_NAME} · local-first by design",style=MaterialTheme.typography.labelSmall.copy(letterSpacing=.8.sp),color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.7f))
        }
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
    Dialog({vm.productReview.value=null},properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
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
private fun providerStatus(provider:ProviderCapability)=when{provider.id=="chatgpt"->if(provider.authenticated)"Signed in on this phone" else "Not connected";provider.reason=="Runs on this phone"->if(provider.hasKey)"Key saved on this phone" else "No key saved";provider.hasKey->"Key saved on your PC";provider.authenticated->"Signed in on your PC";else->"Not connected"}
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
    Column(Modifier.fillMaxWidth().padding(top=4.dp,bottom=18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
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
    }
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
    Dialog({vm.dismissReview()},properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
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
