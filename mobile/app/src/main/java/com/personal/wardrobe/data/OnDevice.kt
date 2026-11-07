package com.personal.wardrobe.data

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.personal.wardrobe.domain.DeviceDetection
import com.personal.wardrobe.domain.Segmentation
import com.personal.wardrobe.domain.SegmentationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Free, private clothing detection on the phone itself.
 *
 * The bundled U²-Net model supplies the mask; colour, type and shape are then read by the same pure
 * Kotlin the domain tests cover. No network, no key, no PC — the companion stays optional. Photos are
 * downscaled to 1200 px for memory, and the trimmed transparent cutout is written to private storage
 * under its own SHA-256 id so sync and backups treat it like any other photo.
 */
class OnDevice(private val context:Context,private val photos:Photos){
    private companion object { const val MODEL="u2netp.onnx"; const val SIDE=Segmentation.SIDE; const val MAX=1200 }
    @Volatile private var session:OrtSession?=null

    fun available():Boolean=runCatching{context.assets.open(MODEL).close();true}.getOrDefault(false)

    suspend fun detect(file:File,garmentId:String,revision:Int,sourceImageId:String):JsonObject=withContext(Dispatchers.IO){
        require(file.exists()){"This photo is still transferring"}
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeFile(file.path,bounds)
        require(bounds.outWidth>0&&bounds.outHeight>0){"This photo could not be read"}
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>MAX)sample*=2
        val bitmap=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=sample})
            ?:throw IllegalArgumentException("This photo could not be read")
        try{
            val width=bitmap.width;val height=bitmap.height
            val frame=IntArray(width*height)
            bitmap.getPixels(frame,0,width,0,0,width,height)
            val alpha=mask(frame,width,height)
            val detection=when(val reading=Segmentation.analyze(frame,width,height,alpha)){
                is SegmentationResult.Found->reading.detection
                SegmentationResult.NoGarment->throw IllegalArgumentException("No clothing shape was found; move closer and use a plainer background")
                SegmentationResult.NoColours->throw IllegalArgumentException("The garment colours could not be read; retake the photo in even light")
            }
            result(detection,photos.put(cutout(frame,width,height,alpha,detection)),garmentId,revision,sourceImageId)
        } finally{bitmap.recycle()}
    }

    /**
     * Frame a not-yet-saved photo on the phone.
     *
     * Runs the same bundled mask the detector uses, then returns how far to crop from each edge.
     * Values are fractions capped at 40% per side, matching the PC endpoint, so a small garment
     * still leaves a usable frame. Returns null when no clothing shape is found.
     */
    suspend fun suggestCrop(bitmap:Bitmap):CropSuggestion?=withContext(Dispatchers.IO){
        val scale=min(1f,1600f/max(bitmap.width,bitmap.height))
        val image=if(scale<1)Bitmap.createScaledBitmap(bitmap,(bitmap.width*scale).toInt().coerceAtLeast(1),(bitmap.height*scale).toInt().coerceAtLeast(1),true)else bitmap
        try{
            val width=image.width;val height=image.height
            val frame=IntArray(width*height)
            image.getPixels(frame,0,width,0,0,width,height)
            val box=Segmentation.trim(mask(frame,width,height),width,height)?:return@withContext null
            val pad=0.03
            fun edge(value:Double)=(round(min(max(value-pad,0.0),.40)*1000)/1000.0).toFloat()
            CropSuggestion(
                left=edge(box[0].toDouble()/width),top=edge(box[1].toDouble()/height),
                right=edge(1.0-box[2].toDouble()/width),bottom=edge(1.0-box[3].toDouble()/height)
            )
        }finally{if(image!==bitmap)image.recycle()}
    }

    /** Run the bundled model on a 320×320 copy and scale its mask back to the photo. */
    private fun mask(frame:IntArray,width:Int,height:Int):IntArray {
        val environment=OrtEnvironment.getEnvironment()
        val active=session?:load(environment)
        val input=OnnxTensor.createTensor(
            environment,FloatBuffer.wrap(Segmentation.input(Segmentation.resize(frame,width,height,SIDE,SIDE))),
            longArrayOf(1,3,SIDE.toLong(),SIDE.toLong())
        )
        val output=try{active.run(mapOf(active.inputNames.first() to input))}finally{input.close()}
        val prediction=try{
            @Suppress("UNCHECKED_CAST")
            val values=(output.get(0) as OnnxTensor).value as Array<Array<Array<FloatArray>>>
            values[0][0]
        }finally{output.close()}
        val flat=FloatArray(SIDE*SIDE)
        for(y in 0 until SIDE)for(x in 0 until SIDE)flat[y*SIDE+x]=prediction[y][x]
        return Segmentation.resizeMask(Segmentation.mask(flat),SIDE,SIDE,width,height)
    }

    private fun load(environment:OrtEnvironment):OrtSession {
        val bytes=runCatching{context.assets.open(MODEL).use{it.readBytes()}}
            .getOrElse{throw IOException("The detection model is missing from this install; reinstall Wardrobe")}
        val options=OrtSession.SessionOptions().apply{
            setIntraOpNumThreads(2);setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return environment.createSession(bytes,options).also{session=it}
    }

    /** Transparent PNG trimmed to the garment, with the soft mask as its alpha channel. */
    private fun cutout(frame:IntArray,width:Int,height:Int,alpha:IntArray,detection:DeviceDetection):ByteArray {
        val boxWidth=detection.right-detection.left;val boxHeight=detection.bottom-detection.top
        val pixels=IntArray(boxWidth*boxHeight)
        for(y in 0 until boxHeight)for(x in 0 until boxWidth){
            val source=(detection.top+y)*width+detection.left+x
            pixels[y*boxWidth+x]=((alpha[source].coerceIn(0,255)) shl 24) or (frame[source] and 0x00FFFFFF)
        }
        var bitmap=Bitmap.createBitmap(boxWidth,boxHeight,Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels,0,boxWidth,0,0,boxWidth,boxHeight)
        try{
            var bytes=encode(bitmap)
            var factor=0.7
            // A noisy full-size cutout can exceed the photo store's 8 MB limit; shrink instead of failing.
            while(bytes.size>8_000_000&&factor>=0.4){
                val scaled=Bitmap.createScaledBitmap(bitmap,(boxWidth*factor).toInt().coerceAtLeast(1),(boxHeight*factor).toInt().coerceAtLeast(1),true)
                bitmap.recycle();bitmap=scaled
                bytes=encode(bitmap);factor-=.15
            }
            return bytes
        } finally{bitmap.recycle()}
    }

    private fun encode(bitmap:Bitmap):ByteArray=ByteArrayOutputStream().use{output->
        bitmap.compress(Bitmap.CompressFormat.PNG,100,output);output.toByteArray()
    }

    private fun result(detection:DeviceDetection,cutoutId:String,garmentId:String,revision:Int,sourceImageId:String)=JsonObject().apply{
        addProperty("garmentId",garmentId);addProperty("revision",revision)
        addProperty("category",detection.category);addProperty("subcategory",detection.subcategory)
        addProperty("color",detection.color)
        add("secondaryColors",JsonArray().apply{detection.secondaryColors.forEach{add(it)}})
        addProperty("shape",detection.shape);addProperty("fit",detection.fit)
        addProperty("confidence",detection.confidence)
        add("fieldConfidence",JsonObject().apply{
            addProperty("category",detection.confidence);addProperty("color",0.85)
            addProperty("shape",0.70);addProperty("fit",0.55)
            addProperty("subcategory",if(detection.subcategory.isEmpty())0.0 else 0.45)
        })
        add("metrics",JsonObject().apply{
            addProperty("height",detection.metrics.height);addProperty("width",detection.metrics.width)
            addProperty("aspect",detection.metrics.aspect);addProperty("fill",detection.metrics.fill)
            addProperty("flare",detection.metrics.flare);addProperty("shoulder",detection.metrics.shoulder)
            addProperty("foregroundPixels",detection.metrics.foregroundPixels);addProperty("areaShare",detection.areaShare)
            add("bbox",JsonArray().apply{
                add(detection.left.toDouble()/detection.frameWidth)
                add(detection.top.toDouble()/detection.frameHeight)
                add((detection.right-detection.left).toDouble()/detection.frameWidth)
                add((detection.bottom-detection.top).toDouble()/detection.frameHeight)
            })
            add("frame",JsonObject().apply{addProperty("width",detection.frameWidth);addProperty("height",detection.frameHeight)})
        })
        addProperty("cutoutId",cutoutId);addProperty("sourceImageId",sourceImageId)
        addProperty("backgroundExcluded",true);addProperty("reviewRequired",true)
        addProperty("notes","Detected on this phone from the garment outline with the background removed. Check the type, colour and shape before applying.")
    }
}
