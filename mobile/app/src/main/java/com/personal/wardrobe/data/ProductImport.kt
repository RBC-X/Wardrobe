package com.personal.wardrobe.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.JsonObject
import com.personal.wardrobe.domain.ProductPages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Product pages and their images fetched by the phone itself, with no PC and no AI key.
 *
 * Only public HTTPS hosts are accepted, every redirect is re-checked, responses are size-capped,
 * and images are re-encoded before entering private photo storage. DNS can still change between
 * the check and the connection; this guard is meant to keep the app off private/Tailscale hosts,
 * not to replace a server-side proxy for hostile input.
 */
class ProductImport(private val photos:Photos) {
    private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).build()
    private data class Fetched(val url:String,val body:ByteArray)

    suspend fun product(url:String):JsonObject=withContext(Dispatchers.IO){
        val page=fetch(url,2_000_000){kind->kind.contains("html")}
        val parsed=ProductPages.parse(page.body.toString(Charsets.UTF_8),page.url,Instant.now().toString())
        // The review sheet previews these URLs directly, so never hand it a private image host.
        parsed["products"]?.takeIf{it.isJsonArray}?.asJsonArray?.forEach{element->
            val product=element.takeIf{it.isJsonObject}?.asJsonObject?:return@forEach
            val images=product["images"]?.takeIf{it.isJsonArray}?.asJsonArray?:return@forEach
            val public=com.google.gson.JsonArray()
            images.forEach{image->
                val value=runCatching{image.asString}.getOrNull()
                if(value!=null&&runCatching{validate(value)}.isSuccess)public.add(value)
            }
            product.add("images",public)
        }
        parsed
    }

    suspend fun image(url:String):String=withContext(Dispatchers.IO){
        val remote=fetch(url,8_000_000){kind->kind.startsWith("image/")}
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(remote.body,0,remote.body.size,bounds)
        require(bounds.outWidth>0&&bounds.outHeight>0&&bounds.outWidth.toLong()*bounds.outHeight<=12_000_000){"This product image is unsupported or too large"}
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1600)sample*=2
        val decoded=BitmapFactory.decodeByteArray(remote.body,0,remote.body.size,BitmapFactory.Options().apply{inSampleSize=sample})
            ?:throw IllegalArgumentException("This product image could not be decoded")
        try{
            val output=ByteArrayOutputStream()
            decoded.compress(Bitmap.CompressFormat.JPEG,90,output)
            photos.put(output.toByteArray())
        }finally{decoded.recycle()}
    }

    private fun fetch(input:String,maxBytes:Int,accept:(String)->Boolean):Fetched {
        var current=validate(input)
        repeat(4){
            val request=Request.Builder().url(current.toString())
                .header("User-Agent","Wardrobe/1.0 (personal wardrobe companion)")
                .header("Accept","text/html,application/xhtml+xml,image/*;q=0.8")
                .build()
            client.newCall(request).execute().use{response->
                val location=response.header("Location")
                if(response.code in 300..399&&!location.isNullOrBlank()){
                    current=validate(current.resolve(location).toString())
                    return@use
                }
                require(response.isSuccessful){"Product page unavailable (HTTP ${response.code})"}
                val kind=response.header("Content-Type").orEmpty().lowercase()
                require(accept(kind)){"This link is not a product page or image"}
                return Fetched(current.toString(),readLimited(response.body?.byteStream(),maxBytes))
            }
        }
        throw IllegalArgumentException("Too many redirects")
    }

    private fun validate(value:String):URI {
        val uri=runCatching{URI(value)}.getOrElse{throw IllegalArgumentException("Enter a complete https product URL")}
        require(uri.scheme?.lowercase()=="https"){"Product links must use https"}
        val host=uri.host?.lowercase()?.trimEnd('.')?.takeIf{it.isNotBlank()}?:throw IllegalArgumentException("This link has no shop host")
        require(host!="localhost"&&!host.endsWith(".local")&&!host.endsWith(".internal")&&!host.endsWith(".ts.net")){"Private network links are blocked"}
        val addresses=runCatching{InetAddress.getAllByName(host)}.getOrElse{throw IllegalArgumentException("This shop could not be found")}
        require(addresses.isNotEmpty()&&addresses.all{publicAddress(it)}){"Private network links are blocked"}
        return uri
    }

    private fun publicAddress(address:InetAddress):Boolean {
        if(address.isAnyLocalAddress||address.isLoopbackAddress||address.isLinkLocalAddress||address.isSiteLocalAddress||address.isMulticastAddress)return false
        val bytes=address.address
        if(bytes.size==4){
            val first=bytes[0].toInt() and 0xFF
            val second=bytes[1].toInt() and 0xFF
            if(first==0||first==127||first>=224)return false
            if(first==100&&second in 64..127)return false
        }
        if(bytes.size==16&&(bytes[0].toInt() and 0xFE)==0xFC)return false
        return true
    }

    private fun readLimited(stream:InputStream?,maxBytes:Int):ByteArray {
        if(stream==null)throw IllegalArgumentException("The page returned no content")
        val output=ByteArrayOutputStream()
        val buffer=ByteArray(16384)
        var total=0
        stream.use{input->
            while(true){
                val count=input.read(buffer)
                if(count<0)break
                total+=count
                require(total<=maxBytes){"This page or image is too large"}
                output.write(buffer,0,count)
            }
        }
        return output.toByteArray()
    }
}
