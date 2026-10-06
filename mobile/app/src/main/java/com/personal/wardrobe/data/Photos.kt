package com.personal.wardrobe.data

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

data class ImportedPhoto(val imageId:String,val colors:List<String>)
class Photos(private val context:Context){
    private val directory=File(context.filesDir,"media").apply{mkdirs()}
    fun file(id:String):File {require(Regex("[a-f0-9]{64}").matches(id));return File(directory,id)}
    fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    fun put(bytes:ByteArray,expected:String?=null):String {
        require(bytes.size<=8_000_000){"Photo is too large"};val id=hash(bytes);require(expected==null||id==expected){"Photo checksum mismatch"}
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth>0&&bounds.outHeight>0&&bounds.outWidth.toLong()*bounds.outHeight<=12_000_000){"Unsupported or oversized image"}
        val target=file(id);if(!target.exists()){val temp=File.createTempFile("$id-",".tmp",directory);try{temp.writeBytes(bytes);check(temp.renameTo(target)||target.exists()){"Could not save photo"}}finally{temp.delete()}};return id
    }
    fun read(uri:Uri):Bitmap {
        val bytes=context.contentResolver.openInputStream(uri)?.use{input->val output=ByteArrayOutputStream();val buffer=ByteArray(16384);var total=0;while(true){val count=input.read(buffer);if(count<0)break;total+=count;require(total<=20_000_000){"Choose a photo smaller than 20 MB"};output.write(buffer,0,count)};output.toByteArray()}?:throw IllegalArgumentException("Cannot open photo")
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds);require(bounds.outWidth>0&&bounds.outHeight>0){"Unsupported image"}
        var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2000)sample*=2
        val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})?:throw IllegalArgumentException("Cannot decode photo")
        val exif=ExifInterface(ByteArrayInputStream(bytes));val rotation=exif.rotationDegrees
        if(rotation==0&&!exif.isFlipped)return decoded
        return Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,Matrix().apply{if(exif.isFlipped)postScale(-1f,1f);postRotate(rotation.toFloat())},true).also{if(it!==decoded)decoded.recycle()}
    }
    fun import(bitmap:Bitmap,left:Float=0f,top:Float=0f,right:Float=0f,bottom:Float=0f):ImportedPhoto {
        val x=(bitmap.width*left).toInt().coerceIn(0,bitmap.width-1);val y=(bitmap.height*top).toInt().coerceIn(0,bitmap.height-1)
        val width=(bitmap.width*(1-left-right)).toInt().coerceIn(1,bitmap.width-x);val height=(bitmap.height*(1-top-bottom)).toInt().coerceIn(1,bitmap.height-y)
        val cropped=Bitmap.createBitmap(bitmap,x,y,width,height)
        val scale=minOf(1f,1600f/maxOf(cropped.width,cropped.height));val image=if(scale<1)Bitmap.createScaledBitmap(cropped,(cropped.width*scale).toInt(),(cropped.height*scale).toInt(),true)else cropped
        val output=ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.JPEG,90,output)
        val colors=colors(image);val id=put(output.toByteArray());if(image!==bitmap)image.recycle();if(cropped!==image&&cropped!==bitmap)cropped.recycle();return ImportedPhoto(id,colors)
    }
    fun colors(bitmap:Bitmap):List<String>{
        val histogram=mutableMapOf<Int,Int>();val step=maxOf(1,maxOf(bitmap.width,bitmap.height)/80)
        for(y in bitmap.height/8 until bitmap.height*7/8 step step)for(x in bitmap.width/8 until bitmap.width*7/8 step step){val c=bitmap.getPixel(x,y);if(Color.alpha(c)<128)continue;val key=((Color.red(c)/32)shl 6)or((Color.green(c)/32)shl 3)or(Color.blue(c)/32);histogram[key]=(histogram[key]?:0)+1}
        return histogram.entries.sortedByDescending{it.value}.take(3).map{(bin,_)->"#%02x%02x%02x".format(((bin shr 6)and 7)*32+16,((bin shr 3)and 7)*32+16,(bin and 7)*32+16)}
    }
    fun collage(items:List<com.personal.wardrobe.domain.Garment>):File {
        val width=1000;val cell=width/2;val height=((items.size+1)/2)*600+100
        val canvasBitmap=Bitmap.createBitmap(width,height.coerceAtLeast(700),Bitmap.Config.ARGB_8888);val canvas=Canvas(canvasBitmap);canvas.drawColor(Color.rgb(246,243,235))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(36,76,64);textSize=38f};canvas.drawText("Wardrobe · Outfit board",35f,60f,paint)
        for((index,item)in items.withIndex()){val id=item.cutoutId.ifEmpty{item.imageIds.firstOrNull().orEmpty()};if(id.isEmpty())continue;val source=file(id);if(!source.exists())continue;val options=BitmapFactory.Options().apply{inSampleSize=2};val image=BitmapFactory.decodeFile(source.path,options)?:continue
            val scale=minOf((cell-40f)/image.width,510f/image.height);val w=image.width*scale;val h=image.height*scale;val left=(index%2)*cell+(cell-w)/2;val top=100f+(index/2)*600
            canvas.drawBitmap(image,null,RectF(left,top,left+w,top+h),null);paint.textSize=24f;canvas.drawText(item.name.take(32),((index%2)*cell+20).toFloat(),top+550,paint);image.recycle()}
        val target=File(context.cacheDir,"outfit-${System.currentTimeMillis()}.jpg");target.outputStream().use{canvasBitmap.compress(Bitmap.CompressFormat.JPEG,92,it)};canvasBitmap.recycle();return target
    }
    fun files():List<File> = directory.listFiles()?.filter{Regex("[a-f0-9]{64}").matches(it.name)}?:emptyList()
}
