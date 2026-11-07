package com.personal.wardrobe.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.personal.wardrobe.domain.WeatherNow
import com.personal.wardrobe.domain.weatherSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Open-Meteo needs no account, so today's forecast is available with no PC and no key. */
class WeatherClient{
    private val client=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
    private fun get(url:String):JsonObject {
        val request=Request.Builder().url(url).header("Accept","application/json").build()
        client.newCall(request).execute().use{response->
            val body=response.body?.string().orEmpty()
            if(!response.isSuccessful)throw IOException("Weather service unavailable (${response.code})")
            return JsonParser.parseString(body).asJsonObject
        }
    }
    suspend fun forecast(place:String):WeatherNow=withContext(Dispatchers.IO){
        val query=URLEncoder.encode(place.trim(),"UTF-8")
        val results=get("https://geocoding-api.open-meteo.com/v1/search?name=$query&count=1&language=en&format=json")["results"]?.takeIf{it.isJsonArray}?.asJsonArray
        val city=results?.firstOrNull()?.takeIf{it.isJsonObject}?.asJsonObject?:throw IllegalArgumentException("No place called \"${place.trim()}\" was found")
        val latitude=city["latitude"].asDouble;val longitude=city["longitude"].asDouble
        val name=listOfNotNull(city["name"]?.takeIf{!it.isJsonNull}?.asString,city["country_code"]?.takeIf{!it.isJsonNull}?.asString).joinToString(", ")
        val forecast=get("https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude&current=temperature_2m,apparent_temperature,weather_code&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto")
        val current=forecast["current"]?.takeIf{it.isJsonObject}?.asJsonObject?:throw IllegalArgumentException("The forecast could not be read")
        val daily=forecast["daily"]?.takeIf{it.isJsonObject}?.asJsonObject?:throw IllegalArgumentException("The forecast could not be read")
        val temperature=current["temperature_2m"]?.takeIf{!it.isJsonNull}?.asDouble?:throw IllegalArgumentException("The forecast has no temperature")
        val feels=current["apparent_temperature"]?.takeIf{!it.isJsonNull}?.asDouble?:temperature
        val high=daily["temperature_2m_max"]?.takeIf{it.isJsonArray}?.asJsonArray?.firstOrNull()?.asDouble?:temperature
        val low=daily["temperature_2m_min"]?.takeIf{it.isJsonArray}?.asJsonArray?.firstOrNull()?.asDouble?:temperature
        val code=current["weather_code"]?.takeIf{!it.isJsonNull}?.asInt?:0
        WeatherNow(name,temperature,feels,high,low,weatherSummary(code),code,Instant.now().toString())
    }
}
