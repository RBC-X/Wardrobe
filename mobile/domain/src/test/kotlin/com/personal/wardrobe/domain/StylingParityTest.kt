package com.personal.wardrobe.domain

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StylingParityTest {
    @Test fun matchesPythonFixture(){
        val gson=Gson();val rules=gson.fromJson(File("shared/rules.json").readText(),Rules::class.java);val fixture=JsonParser.parseString(File("shared/styling-parity.json").readText()).asJsonObject
        val garments=fixture["garments"].asJsonArray.map{gson.fromJson(it,Garment::class.java)};val actual=StylingEngine(rules).generate(garments,season="winter",weather="cold");val expected=fixture["outfits"].asJsonArray
        assertEquals(expected.size(),actual.size)
        actual.forEachIndexed{i,outfit->val e=expected[i].asJsonObject;assertEquals(e["id"].asString,outfit.id);assertEquals(e["itemIds"].asJsonArray.map{it.asString},outfit.itemIds);for(key in listOf("score","foundation","personal","trend")){val value=when(key){"score"->outfit.score;"foundation"->outfit.foundation;"personal"->outfit.personal;else->outfit.trend};assertEquals(e[key].asDouble,value,.0001)}}
    }
}
