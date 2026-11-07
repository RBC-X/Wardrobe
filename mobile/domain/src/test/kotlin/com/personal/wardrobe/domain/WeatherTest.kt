package com.personal.wardrobe.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherTest {
    @Test fun bandsFollowPerceivedTemperature(){
        assertEquals("cold",weatherBand(8.9))
        assertEquals("cold",weatherBand(9.0))
        assertEquals("mild",weatherBand(9.1))
        assertEquals("mild",weatherBand(23.9))
        assertEquals("warm",weatherBand(24.0))
    }

    @Test fun summariesCoverTheCodesPeopleSeeMost(){
        assertEquals("Clear",weatherSummary(0))
        assertEquals("Rain",weatherSummary(63))
        assertEquals("Snow",weatherSummary(75))
        assertEquals("Thunderstorm",weatherSummary(95))
        assertEquals("Mixed conditions",weatherSummary(1234))
    }

    @Test fun lineShowsTheNumbersThatMatter(){
        val now=WeatherNow("York, GB",12.4,10.6,14.2,7.8,"Overcast",3,"")
        assertEquals("mild",now.band)
        assertEquals("Overcast · 12°, feels 11° · high 14° / low 8° · York, GB",now.line)
    }
}
