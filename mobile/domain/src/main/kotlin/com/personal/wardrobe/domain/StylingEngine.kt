package com.personal.wardrobe.domain

import java.security.MessageDigest
import java.time.Instant
import kotlin.math.*

class StylingEngine(val rules:Rules) {
    private fun hsv(hex:String):Triple<Double,Double,Double> {
        val rgb=runCatching { listOf(1,3,5).map { hex.substring(it,it+2).toInt(16)/255.0 } }.getOrElse { listOf(.5,.5,.5) }
        val maximum=rgb.max();val minimum=rgb.min();val delta=maximum-minimum
        val hue=if(delta==0.0)0.0 else when(maximum){rgb[0]->((rgb[1]-rgb[2])/delta+if(rgb[1]<rgb[2])6 else 0)/6;rgb[1]->((rgb[2]-rgb[0])/delta+2)/6;else->((rgb[0]-rgb[1])/delta+4)/6}
        return Triple(hue,if(maximum==0.0)0.0 else delta/maximum,maximum)
    }
    fun colorMatch(a:String,b:String):Double {
        val (h1,s1,v1)=hsv(a);val(h2,s2,v2)=hsv(b)
        if(min(s1,s2)<.18)return if(abs(v1-v2)>.18).94 else .82
        val delta=min(abs(h1-h2),1-abs(h1-h2))*360
        return when{delta<=40->.90;delta in 145.0..180.0->.78;delta in 90.0..135.0->.72;else->.62}
    }
    fun valid(items:List<Garment>,occasion:String="any",weather:String="mild",pinned:String=""):Boolean {
        if(items.isEmpty()||items.map{it.id}.distinct().size!=items.size)return false
        val cats=items.map{it.category}
        if(!(("top" in cats&&"bottom" in cats)||"onepiece" in cats)||"shoes" !in cats)return false
        if("onepiece" in cats&&("top" in cats||"bottom" in cats))return false
        if(cats.distinct().size!=cats.size||items.any{it.status!="available"||it.deleted})return false
        if(pinned.isNotEmpty()&&items.none{it.id==pinned})return false
        if(occasion=="formal"&&items.any{it.category!="accessory"&&it.formality<2})return false
        if(weather=="cold"&&"outerwear" !in cats)return false
        if(weather=="warm"&&items.any{it.warmth>=3})return false
        return true
    }
    private fun pairs(items:List<Garment>)=items.indices.flatMap{i->(i+1 until items.size).map{j->items[i] to items[j]}}
    fun score(items:List<Garment>,profile:Profile=Profile(),trends:List<Trend> = emptyList(),mode:String="balanced",occasion:String="any",weather:String="mild",season:String="all"):Outfit {
        val pairs=pairs(items);val color=pairs.sumOf{colorMatch(it.first.color,it.second.color)}/max(1,pairs.size)
        val patterned=items.count{it.pattern!="solid"};val pattern=max(.45,1-.18*max(0,patterned-1))
        val textures=items.map{it.texture}.toSet()-"unknown";val texture=if(textures.size>1).82 else .72
        val formalities=items.filter{it.category!="accessory"}.map{it.formality}
        val coherence=max(.35,1-.18*((formalities.maxOrNull()?:1)-(formalities.minOrNull()?:1)))
        val fits=items.filter{it.category in listOf("top","bottom","onepiece")}.map{it.fit};val proportion=if(fits.distinct().size>1).85 else .75
        val seasonMatch=items.count{season=="all"||it.season in listOf("all",season)}.toDouble()/items.size
        val weightMatch=max(.4,1-.15*(items.maxOf{it.warmth}-items.minOf{it.warmth}))
        val foundation=.36*color+.15*pattern+.14*texture+.17*coherence+.10*proportion+.04*seasonMatch+.04*weightMatch
        var preference=items.sumOf{g->.5+(if(profile.styles.any{it in g.styleTags}).2 else 0.0)+(if(g.color in profile.favoriteColors).1 else 0.0)+(if(profile.fit.isNotEmpty()&&profile.fit==g.fit).1 else 0.0)+(if(g.id in profile.likedItems).1 else 0.0)-(if(g.id in profile.dislikedItems).3 else 0.0)}/items.size
        val attributes=items.flatMap{listOf(it.pattern,it.texture,it.color,it.subcategory)+it.styleTags}.map{it.lowercase()}.toSet()
        for(combination in profile.dislikedCombinations.split(';')){val terms=combination.split('+').map{it.trim().lowercase()}.filter{it.isNotEmpty()}.toSet();if(terms.size>=2&&attributes.containsAll(terms))preference=max(0.0,preference-.3)}
        var trendScore=0.0;val evidence=mutableListOf<Map<String,Any>>()
        for(trend in trends){if(trend.sources.isEmpty()||trend.confidence<.5||runCatching{val now=Instant.now();val observed=if(trend.observedAt.isEmpty())now else Instant.parse(trend.observedAt);observed>now||Instant.parse(trend.expiresAt)<=now||observed.plusSeconds(90L*86400)<=now}.getOrDefault(true)||(season!="all"&&trend.season !in listOf("all",season)))continue
            val matched=items.count{g->trend.tags.any{it in listOf(g.subcategory.lowercase(),g.texture)+g.styleTags}}
            if(matched>0){trendScore=max(trendScore,min(1.0,matched/2.0)*trend.confidence);evidence.add(mapOf("title" to trend.title,"sources" to trend.sources))}}
        val weights=rules.weights[mode]?:listOf(.75,.15,.10)
        val total=100*(weights[0]*foundation+weights[1]*preference+weights[2]*trendScore)
        val reasons=listOf(if(color>=.8)"A coordinated palette with intentional contrast." else "A more expressive color combination.",if(patterned<=1)"One focal pattern keeps the outfit clear." else "Shared colors help multiple patterns connect.",if(textures.size>1)"Different surfaces add texture and depth." else "Similar surfaces give a quieter finish.",if(coherence>=.8)"The pieces have a consistent level of formality." else "This combines relaxed and dressier pieces.")+listOf("Palette: "+items.joinToString{it.name+" "+it.color}+".","Silhouette: "+items.filter{it.category in listOf("top","bottom","onepiece")}.joinToString{it.fit}+"; texture: "+textures.sorted().ifEmpty{listOf("unknown")}.joinToString()+".","Your preferences contribute ${round(preference*100).toInt()}/100; season and fabric-weight appearance are ranking preferences, not guarantees.")
        val id=MessageDigest.getInstance("SHA-256").digest(items.map{it.id}.sorted().joinToString("|").toByteArray()).joinToString(""){"%02x".format(it)}.take(24)
        fun rounded(value:Double)=round(value*10000)/10000
        return Outfit(id,items.map{it.id},rounded(total),rounded(foundation*100),rounded(preference*100),rounded(trendScore*100),reasons,evidence,mode,occasion,weather,season=season)
    }
    fun generate(garments:List<Garment>,profile:Profile=Profile(),trends:List<Trend> = emptyList(),mode:String="balanced",occasion:String="any",weather:String="mild",pinned:String="",limit:Int=5,season:String="all"):List<Outfit> {
        val available=garments.filter{it.status=="available"&&!it.deleted&&(occasion!="formal"||it.category=="accessory"||it.formality>=2)&&(weather!="warm"||it.warmth<3)}.sortedBy{it.id}
        val anchor=available.find{it.id==pinned};if(pinned.isNotEmpty()&&anchor==null)return emptyList()
        val slots=rules.categories.associateWith{c->available.filter{it.category==c}.take(rules.slotLimit)}.toMutableMap()
        if(anchor!=null)slots[anchor.category]=listOf(anchor)
        val candidates=mutableListOf<Outfit>()
        for(template in rules.templates){val categories=template.toMutableList();if(weather=="cold"||anchor?.category=="outerwear")categories.add("outerwear");if(anchor?.category=="accessory")categories.add("accessory")
            var beam=listOf(emptyList<Garment>())
            for(category in categories){beam=beam.flatMap{partial->(slots[category]?:emptyList()).map{partial+it}}.sortedWith(compareByDescending<List<Garment>>{items->pairs(items).sumOf{colorMatch(it.first.color,it.second.color)}}.thenBy{it.joinToString(":"){g->g.id}}).take(rules.beamWidth)}
            beam.filter{valid(it,occasion,weather,pinned)}.forEach{candidates.add(score(it,profile,trends,mode,occasion,weather,season))}}
        val selected=mutableListOf<Outfit>()
        for(candidate in candidates.sortedWith(compareByDescending<Outfit>{it.score}.thenBy{it.id})){if(selected.all{old->candidate.itemIds.toSet().intersect(old.itemIds.toSet()).size.toDouble()/max(candidate.itemIds.size,old.itemIds.size)<.85}){selected.add(candidate);if(selected.size>=limit)break}}
        return selected
    }
    fun gaps(garments:List<Garment>):List<Gap> {
        val available=garments.filter{it.status=="available"&&!it.deleted};val present=available.map{it.category}.toSet()
        val suggestions=listOf(listOf("top","A simple neutral top","#f3eadb"),listOf("bottom","Olive straight-leg trousers","#72734e"),listOf("shoes","Minimal neutral sneakers","#f4f1e8"),listOf("outerwear","A versatile light jacket","#9b7957"))
        return suggestions.mapNotNull{(category,title,color)->val matches=available.filter{it.category!=category&&colorMatch(it.color,color)>=.72};if(available.none{it.category==category&&colorMatch(it.color,color)>=.8}&&(category !in present||matches.size>=3))Gap(category,title,color,"smooth",matches.map{it.id},if(matches.isEmpty())"Add a $category to complete more outfit templates." else "Coordinates with ${matches.size} available pieces.") else null}.sortedWith(compareBy<Gap>{it.category in present}.thenByDescending{it.matchingItemIds.size})
    }
    fun applyAnalysis(garment:Garment,result:Map<String,Any>):Garment {
        if(result["garmentId"]!=garment.id||(result["revision"] as? Number)?.toInt()!=garment.revision)throw IllegalArgumentException("This item changed; request fresh analysis.")
        val locks=garment.manualFields;fun text(field:String,current:String)=if(field in locks)current else result[field] as? String?:current
        // Only accept taxonomy values; a stray model string must not poison matching.
        fun taxonomy(field:String,options:List<String>,current:String)=text(field,current).takeIf{it in options}?:current
        val color=text("color",garment.color);require(Regex("#[0-9a-fA-F]{6}").matches(color))
        val category=taxonomy("category",rules.categories.ifEmpty{listOf(garment.category)},garment.category)
        val subtypes=rules.subcategories[category]?:emptyList()
        val subcategory=text("subcategory",garment.subcategory).takeIf{it in subtypes}?:subtypes.firstOrNull()?:""
        return garment.copy(category=category,subcategory=subcategory,color=color,pattern=taxonomy("pattern",rules.patterns,garment.pattern),texture=taxonomy("texture",rules.textures,garment.texture),fit=taxonomy("fit",rules.fits,garment.fit),shape=text("shape",garment.shape).take(80),styleTags=if("styleTags" in locks)garment.styleTags else (result["styleTags"] as? List<*>)?.filterIsInstance<String>()?.take(20)?:garment.styleTags,provenance=garment.provenance+mapOf("analysis" to result))
    }
}
