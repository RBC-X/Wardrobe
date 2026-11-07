package com.personal.wardrobe.domain

import kotlin.math.roundToInt

/** One day of conditions, reduced to what outfit ranking actually needs. */
data class WeatherNow(
    val place:String, val temperature:Double, val feelsLike:Double, val high:Double, val low:Double,
    val summary:String, val code:Int=0, val fetchedAt:String=""
){
    /** Ranking only understands three bands; the numbers stay visible in the card. */
    val band:String get()=weatherBand(feelsLike)
    val line:String get()="${summary} · ${temperature.roundToInt()}°, feels ${feelsLike.roundToInt()}° · high ${high.roundToInt()}° / low ${low.roundToInt()}° · $place"
}

fun weatherBand(feelsLike:Double):String=when{feelsLike>=24->"warm";feelsLike<=9->"cold";else->"mild"}

/** WMO weather codes as returned by Open-Meteo. */
fun weatherSummary(code:Int):String=when(code){
    0->"Clear";1->"Mostly clear";2->"Partly cloudy";3->"Overcast";45,48->"Fog"
    51,53,55,56,57->"Drizzle";61,63,65,66,67->"Rain";71,73,75,77->"Snow"
    80,81,82->"Showers";85,86->"Snow showers";95,96,99->"Thunderstorm";else->"Mixed conditions"
}
