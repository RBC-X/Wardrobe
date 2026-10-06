package com.personal.wardrobe.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Connection(private val context:Context){
    private val preferences=context.getSharedPreferences("connection",Context.MODE_PRIVATE)
    private val alias="wardrobe-device-token"
    var url:String
        get()=preferences.getString("url","")?:""
        set(value){preferences.edit().putString("url",value).apply()}
    val paired:Boolean get()=url.isNotBlank()&&preferences.contains("token")
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey()
    }
    var token:String
        get(){val encoded=preferences.getString("token",null)?:return "";return runCatching{val parts=encoded.split(":");val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8)}.getOrDefault("")}
        set(value){val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());val encrypted=cipher.doFinal(value.toByteArray());preferences.edit().putString("token",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(encrypted,Base64.NO_WRAP)).apply()}
    fun disconnect(){preferences.edit().clear().apply()}
}

class ApiException(val status:Int,message:String):IOException(message)

class ApiClient(private val connection:Connection){
    val gson=Gson()
    private val client=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS).writeTimeout(45,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    fun validateUrl(value:String):String {
        val url=value.trim().trimEnd('/').toHttpUrlOrNull()?:throw IllegalArgumentException("Enter your Tailscale HTTPS address")
        require(url.isHttps&&url.host.endsWith(".ts.net")&&url.username.isEmpty()&&url.password.isEmpty()&&url.port==443&&url.encodedPath=="/"&&url.query==null&&url.fragment==null){"Use your private https://PC.network.ts.net address"}
        return value.trim().trimEnd('/')
    }
    private fun request(path:String,method:String,body:RequestBody?,authenticated:Boolean=true,base:String=connection.url):Request {
        require(path.startsWith("/v1/")&&!path.contains(".."))
        val builder=Request.Builder().url(validateUrl(base)+path).method(method,body)
        if(authenticated){val token=connection.token;require(token.isNotBlank()){ "Pair your phone with the PC first" };builder.header("Authorization","Bearer $token")}
        return builder.build()
    }
    suspend fun json(path:String,method:String="GET",body:Any?=null,authenticated:Boolean=true,base:String=connection.url):com.google.gson.JsonObject=withContext(Dispatchers.IO){
        val payload=body?.let{gson.toJson(it).toRequestBody("application/json".toMediaType())}?:if(method!="GET")"{}".toRequestBody("application/json".toMediaType())else null
        client.newCall(request(path,method,payload,authenticated,base)).execute().use{response->val raw=response.body?.string()?:"{}";if(!response.isSuccessful){val message=runCatching{gson.fromJson(raw,com.google.gson.JsonObject::class.java)["detail"].asString}.getOrDefault("PC request failed (${response.code})");throw ApiException(response.code,message)};gson.fromJson(raw,com.google.gson.JsonObject::class.java)}
    }
    suspend fun upload(id:String,bytes:ByteArray)=withContext(Dispatchers.IO){client.newCall(request("/v1/media/$id","PUT",bytes.toRequestBody("application/octet-stream".toMediaType()))).execute().use{if(!it.isSuccessful)throw IOException("Photo upload failed (${it.code})")};Unit}
    suspend fun download(id:String):ByteArray=withContext(Dispatchers.IO){require(Regex("[a-f0-9]{64}").matches(id));client.newCall(request("/v1/media/$id","GET",null)).execute().use{response->if(!response.isSuccessful)throw IOException("Photo download failed (${response.code})");val input=response.body?.byteStream()?:throw IOException("Empty photo response");val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(16384);var total=0;while(true){val count=input.read(buffer);if(count<0)break;total+=count;require(total<=8_000_000){"Photo is too large"};output.write(buffer,0,count)};output.toByteArray()}}
}
