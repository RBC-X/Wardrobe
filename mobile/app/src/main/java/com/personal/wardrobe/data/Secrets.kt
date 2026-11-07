package com.personal.wardrobe.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small encrypted store for credentials the phone uses without the PC. */
class Secrets(context:Context){
    private val preferences=context.getSharedPreferences("secrets",Context.MODE_PRIVATE)
    private fun alias(name:String)="wardrobe-secret-$name"
    private fun key(name:String):SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(alias(name),null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder(alias(name),KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun get(name:String):String {
        val encoded=preferences.getString(name,null)?:return ""
        return runCatching{
            val parts=encoded.split(":")
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(name),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8)
        }.getOrDefault("")
    }
    fun set(name:String,value:String){
        if(value.isBlank()){clear(name);return}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(name));val encrypted=cipher.doFinal(value.toByteArray())
        preferences.edit().putString(name,Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(encrypted,Base64.NO_WRAP)).apply()
    }
    fun clear(name:String){preferences.edit().remove(name).apply();runCatching{KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(alias(name))}}
}
