package com.personal.wardrobe.domain

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StylingEngineTest {
    private val engine=StylingEngine(Gson().fromJson(File("shared/rules.json").readText(),Rules::class.java))
    private fun wardrobe()=listOf(Garment("top","Blue shirt",color="#3d6699"),Garment("bottom","Neutral trousers","bottom",color="#444444"),Garment("shoes","Sneakers","shoes",color="#eeeeee"))
    @Test fun emptyInstallHasNoOutfits(){assertTrue(engine.generate(emptyList()).isEmpty())}
    @Test fun offlineOutfitsAreValidAndDeterministic(){val a=engine.generate(wardrobe());assertFalse(a.isEmpty());assertEquals(a,engine.generate(wardrobe()));assertTrue(a.all{engine.valid(wardrobe().filter{g->g.id in it.itemIds})})}
    @Test fun unavailableExcludedAndUnknownPinsAreRejected(){assertTrue(engine.generate(wardrobe().map{if(it.category=="shoes")it.copy(status="laundry")else it}).isEmpty());assertTrue(engine.generate(wardrobe(),pinned="missing").isEmpty())}
    @Test fun weatherAndFormalityAreStructural(){assertTrue(engine.generate(wardrobe(),weather="cold").isEmpty());val coat=Garment("coat","Coat","outerwear",warmth=3);assertFalse(engine.generate(wardrobe()+coat,weather="cold").isEmpty());assertTrue(engine.generate(wardrobe(),occasion="formal").isEmpty())}
    @Test fun unknownBrandAndMaterialStayUnconfirmed(){val g=wardrobe()[0];assertFalse(g.brandConfirmed);assertFalse(g.compositionVerified);assertEquals("",g.composition)}
    @Test fun manualCorrectionsSurviveAnalysis(){val g=wardrobe()[0].copy(manualFields=listOf("color","category"));val result=engine.applyAnalysis(g,mapOf("garmentId" to g.id,"revision" to 1,"color" to "#ff0000","category" to "bottom","texture" to "knit"));assertEquals(g.color,result.color);assertEquals(g.category,result.category);assertEquals("knit",result.texture);assertFalse(result.brandConfirmed)}
    @Test(expected=IllegalArgumentException::class) fun staleAnalysisRejected(){engine.applyAnalysis(wardrobe()[0].copy(revision=2),mapOf("garmentId" to "top","revision" to 1))}
    @Test fun expiresUnsupportedTrends(){val trend=Trend(tags=listOf("casual"),sources=listOf("https://www.vogue.com/example"),confidence=.9,expiresAt="2000-01-01T00:00:00Z");assertEquals(0.0,engine.score(wardrobe(),trends=listOf(trend)).trend,0.0)}
    @Test fun boundedDiverseGeneration(){val many=(0..199).map{Garment("t$it","Top $it")}+wardrobe().filter{it.category!="top"};val result=engine.generate(many);assertTrue(result.size in 1..5);assertEquals(result.size,result.map{it.id}.distinct().size)}
    @Test fun seasonIsAPreferenceNotAProhibition(){val winter=wardrobe().map{it.copy(season="winter")};assertFalse(engine.generate(winter,season="summer").isEmpty());assertTrue(engine.score(winter,season="winter").foundation>engine.score(winter,season="summer").foundation)}
    @Test fun duplicateGapsAreNotSuggested(){val items=wardrobe()+Garment("coat","Coat","outerwear",color="#9b7957");assertTrue(engine.gaps(items).isEmpty())}
}
