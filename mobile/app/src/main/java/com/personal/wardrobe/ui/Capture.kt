package com.personal.wardrobe.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

@Composable fun CameraDialog(pairing:Boolean=false,onDismiss:()->Unit,onPhoto:(Uri)->Unit,onError:(String)->Unit){
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current
    var permitted by remember{mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){permitted=it}
    var provider by remember{mutableStateOf<ProcessCameraProvider?>(null)};var capture by remember{mutableStateOf<ImageCapture?>(null)};var taking by remember{mutableStateOf(false)}
    LaunchedEffect(Unit){if(!permitted)permission.launch(Manifest.permission.CAMERA)}
    Dialog(onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize()){Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Text(if(pairing)"Scan your pairing QR" else "Photograph your clothing",style=MaterialTheme.typography.headlineSmall)
            Text(if(pairing)"Frame the QR displayed on your PC, then tap Scan." else "Use even light and a simple background. Capture one item at a time.")
            if(permitted)AndroidView(factory={ctx->PreviewView(ctx).also{view->val future=ProcessCameraProvider.getInstance(ctx);future.addListener({try{val cameraProvider=future.get();provider=cameraProvider;val preview=Preview.Builder().build().also{it.setSurfaceProvider(view.surfaceProvider)};val imageCapture=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build();cameraProvider.unbindAll();cameraProvider.bindToLifecycle(lifecycle,CameraSelector.DEFAULT_BACK_CAMERA,preview,imageCapture);capture=imageCapture}catch(error:Exception){onError(error.message?:"Camera unavailable")}},ContextCompat.getMainExecutor(ctx))}},modifier=Modifier.weight(1f).fillMaxWidth())
            else Column(Modifier.weight(1f)){Text("Camera access is off. You can also import a photo from your gallery.");Button(onClick={permission.launch(Manifest.permission.CAMERA)}){Text("Allow camera")}}
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){OutlinedButton(onDismiss,Modifier.weight(1f)){Text("Cancel")};Button(onClick={val c=capture?:return@Button;taking=true;val file=File(context.cacheDir,"capture-${System.currentTimeMillis()}.jpg");c.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),ContextCompat.getMainExecutor(context),object:ImageCapture.OnImageSavedCallback{override fun onImageSaved(result:ImageCapture.OutputFileResults){taking=false;onPhoto(Uri.fromFile(file));onDismiss()};override fun onError(error:ImageCaptureException){taking=false;onError(error.message?:"Photo could not be captured")}})},enabled=capture!=null&&!taking,modifier=Modifier.weight(1f)){Text(if(taking)"Saving…"else if(pairing)"Scan"else"Take photo")}}
        }}
    }
    DisposableEffect(Unit){onDispose{provider?.unbindAll()}}
}

@Composable fun CropDialog(bitmap:Bitmap,onDismiss:()->Unit,onUse:(Float,Float,Float,Float)->Unit){
    var left by remember{mutableFloatStateOf(0f)};var right by remember{mutableFloatStateOf(0f)};var top by remember{mutableFloatStateOf(0f)};var bottom by remember{mutableFloatStateOf(0f)}
    Dialog(onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize()){Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("Keep just the item",style=MaterialTheme.typography.headlineSmall);Text("Crop away the background to improve the color palette. Your original gallery photo is unchanged.")
            Box(Modifier.fillMaxWidth().height(300.dp)){Image(bitmap.asImageBitmap(),"Selected clothing photo",Modifier.fillMaxSize(),contentScale=ContentScale.Fit);Canvas(Modifier.fillMaxSize()){
                val scale=minOf(size.width/bitmap.width,size.height/bitmap.height);val w=bitmap.width*scale;val h=bitmap.height*scale;val x=(size.width-w)/2;val y=(size.height-h)/2;val shade=Color.Black.copy(alpha=.50f)
                drawRect(shade,Offset(x,y),Size(w,h*top));drawRect(shade,Offset(x,y+h*(1-bottom)),Size(w,h*bottom));drawRect(shade,Offset(x,y+h*top),Size(w*left,h*(1-top-bottom)));drawRect(shade,Offset(x+w*(1-right),y+h*top),Size(w*right,h*(1-top-bottom)))}}
            Text("Left crop");Slider(left,{left=it},valueRange=0f..0.4f);Text("Right crop");Slider(right,{right=it},valueRange=0f..0.4f);Text("Top crop");Slider(top,{top=it},valueRange=0f..0.4f);Text("Bottom crop");Slider(bottom,{bottom=it},valueRange=0f..0.4f)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){OutlinedButton(onDismiss,Modifier.weight(1f)){Text("Cancel")};Button({onUse(left,top,right,bottom)},Modifier.weight(1f)){Text("Use photo")}}
        }}
    }
}
