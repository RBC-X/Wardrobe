package com.personal.wardrobe.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

data class LocationFix(val latitude:Double,val longitude:Double,val place:String)

/** Uses the platform location services so weather does not require Play Services or a key. */
class DeviceLocation(private val context:Context) {
    @SuppressLint("MissingPermission")
    suspend fun current():LocationFix=withContext(Dispatchers.IO){
        val manager=context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: throw IOException("Location is not available on this phone")
        val providers=listOf(LocationManager.NETWORK_PROVIDER,LocationManager.GPS_PROVIDER)
            .filter{provider->runCatching{manager.isProviderEnabled(provider)}.getOrDefault(false)}
        if(providers.isEmpty())throw IOException("Turn on location services to use your local weather")
        val last=providers.mapNotNull{provider->runCatching{manager.getLastKnownLocation(provider)}.getOrNull()}.maxByOrNull{it.time}
        var location=last?.takeIf{System.currentTimeMillis()-it.time<=15*60*1000}
        if(location==null){
            for(provider in providers){
                location=withTimeoutOrNull(12_000){awaitOne(manager,provider)}
                if(location!=null)break
            }
        }
        val fix=location?:throw IOException("We could not get a location fix. Try again, or enter a city.")
        LocationFix(fix.latitude,fix.longitude,label(fix.latitude,fix.longitude))
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitOne(manager:LocationManager,provider:String):Location?=suspendCancellableCoroutine{continuation->
        val listener=object:LocationListener{
            override fun onLocationChanged(location:Location){
                if(continuation.isActive){manager.removeUpdates(this);continuation.resume(location)}
            }
            override fun onProviderDisabled(provider:String){
                if(continuation.isActive){manager.removeUpdates(this);continuation.resume(null)}
            }
            override fun onProviderEnabled(provider:String){}
            @Deprecated("Deprecated in Android")
            override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?){}
        }
        continuation.invokeOnCancellation{manager.removeUpdates(listener)}
        try{
            manager.requestSingleUpdate(provider,listener,Looper.getMainLooper())
        }catch(_:Exception){
            if(continuation.isActive)continuation.resume(null)
        }
    }

    private fun label(latitude:Double,longitude:Double):String{
        if(!Geocoder.isPresent())return "Current location"
        return runCatching{
            val address=Geocoder(context,Locale.getDefault()).getFromLocation(latitude,longitude,1)?.firstOrNull()
            listOfNotNull(address?.locality,address?.subAdminArea,address?.adminArea,address?.countryCode)
                .filter{it.isNotBlank()}.distinct().take(2).joinToString(", ")
        }.getOrNull()?.takeIf{it.isNotBlank()}?:"Current location"
    }
}
