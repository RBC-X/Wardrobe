package com.personal.wardrobe.domain

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.net.URI

/**
 * Product details read on the phone from an already-fetched HTML page.
 *
 * JSON-LD Product/ProductGroup objects are preferred, then OpenGraph and product meta tags, then
 * the page title. Every field is a suggestion until the owner confirms the exact variant. Only
 * HTTPS image URLs are kept, and relative image paths are resolved against the final page URL.
 */
object ProductPages {
    private val script=Regex("""<script\b[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL))
    private val metaTag=Regex("""<meta\b[^>]*>""",RegexOption.IGNORE_CASE)
    private val attribute=Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val titleTag=Regex("""<title[^>]*>(.*?)</title>""",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL))
    private val tags=Regex("""<[^>]+>""")
    private val spaces=Regex("""\s+""")
    private val decimalEntity=Regex("""&#(\d+);""")
    private val hexEntity=Regex("""&#x([0-9a-fA-F]+);""")

    fun parse(html:String,pageUrl:String,checkedAt:String):JsonObject {
        val meta=meta(html)
        val objects=mutableListOf<JsonObject>()
        script.findAll(html).take(20).forEach{match->
            val text=match.groupValues[1].trim().removePrefix("<!--").removeSuffix("-->").trim()
            if(text.isEmpty())return@forEach
            val root=runCatching{JsonParser.parseString(text)}.getOrNull()?:return@forEach
            collect(root,objects,0)
        }
        val products=JsonArray()
        if(objects.isEmpty())products.add(product(JsonObject(),meta,html,pageUrl,checkedAt))
        else objects.take(12).forEach{products.add(product(it,meta,html,pageUrl,checkedAt))}
        return JsonObject().apply{
            add("products",products)
            addProperty("sourceUrl",pageUrl)
            addProperty("checkedAt",checkedAt)
        }
    }

    private fun collect(element:JsonElement,out:MutableList<JsonObject>,depth:Int) {
        if(depth>8||out.size>=12)return
        when(element){
            is JsonArray->element.forEach{collect(it,out,depth+1)}
            is JsonObject->{
                val names=when(val type=element["@type"]){
                    is JsonArray->type.mapNotNull{runCatching{it.asString}.getOrNull()}
                    is JsonPrimitive->listOf(runCatching{type.asString}.getOrDefault(""))
                    else->emptyList()
                }
                if(names.any{it=="Product"||it=="ProductGroup"||it.endsWith("/Product")})out.add(element)
                else for(key in listOf("@graph","mainEntity","hasVariant","itemListElement","itemListElement"))element[key]?.let{collect(it,out,depth+1)}
            }
            else->Unit
        }
    }

    private fun product(source:JsonObject,meta:Map<String,String>,html:String,pageUrl:String,checkedAt:String):JsonObject {
        val offer=offer(source)
        val images=linkedSetOf<String>()
        imageValues(source["image"]).forEach{value->resolve(value,pageUrl)?.let{images.add(it)}}
        for(key in listOf("og:image","og:image:secure_url","twitter:image"))meta[key]?.let{value->resolve(value,pageUrl)?.let{images.add(it)}}
        val name=firstNonBlank(text(source["name"]),meta["og:title"].orEmpty(),title(html),"Imported product").take(200)
        return JsonObject().apply{
            addProperty("name",name)
            addProperty("brand",text(source["brand"]).take(100))
            addProperty("color",text(source["color"]).take(100))
            addProperty("size",text(source["size"]).take(100))
            addProperty("composition",text(source["material"]).take(400))
            add("images",JsonArray().apply{images.take(12).forEach{add(it)}})
            addProperty("price",firstNonBlank(text(offer["price"]),text(offer["lowPrice"]),meta["product:price:amount"].orEmpty(),meta["og:price:amount"].orEmpty()).take(30))
            addProperty("currency",firstNonBlank(text(offer["priceCurrency"]),meta["product:price:currency"].orEmpty(),meta["og:price:currency"].orEmpty()).take(8))
            addProperty("availability",text(offer["availability"]).take(100))
            addProperty("url",pageUrl)
            addProperty("checkedAt",checkedAt)
            addProperty("needsConfirmation",true)
        }
    }

    private fun offer(source:JsonObject):JsonObject = when(val value=source["offers"]){
        is JsonArray->value.firstOrNull{it.isJsonObject}?.asJsonObject?:JsonObject()
        is JsonObject->value
        else->JsonObject()
    }

    private fun imageValues(value:JsonElement?):List<String> = when(value){
        null->emptyList()
        is JsonArray->value.flatMap{imageValues(it)}
        is JsonObject->listOfNotNull(
            value["url"]?.let{text(it)}?.takeIf{it.isNotBlank()},
            value["contentUrl"]?.let{text(it)}?.takeIf{it.isNotBlank()}
        )
        is JsonPrimitive->listOfNotNull(runCatching{value.asString}.getOrNull()?.takeIf{it.isNotBlank()})
        else->emptyList()
    }

    private fun text(value:JsonElement?):String = when(value){
        null->""
        is JsonPrimitive->runCatching{value.asString}.getOrDefault("").trim()
        is JsonArray->value.map{text(it)}.filter{it.isNotBlank()}.joinToString(", ")
        is JsonObject->text(value["name"])
        else->""
    }

    private fun meta(html:String):Map<String,String> {
        val values=linkedMapOf<String,String>()
        metaTag.findAll(html).forEach{tag->
            val attributes=linkedMapOf<String,String>()
            attribute.findAll(tag.value).forEach{match->
                val name=match.groupValues[1].lowercase()
                val content=match.groupValues[2].ifEmpty{match.groupValues[3]}
                attributes[name]=content
            }
            val key=attributes["property"]?.takeIf{it.isNotBlank()}?:attributes["name"]?.takeIf{it.isNotBlank()}?:attributes["itemprop"]?.takeIf{it.isNotBlank()}?:return@forEach
            val content=attributes["content"]?:return@forEach
            val normalized=key.lowercase()
            if(normalized !in values)values[normalized]=decode(content)
        }
        return values
    }

    private fun title(html:String):String=titleTag.find(html)?.groupValues?.get(1)?.let{value->
        spaces.replace(tags.replace(decode(value)," ")," ").trim()
    }.orEmpty()

    private fun resolve(value:String,pageUrl:String):String? = runCatching{
        val uri=URI(pageUrl).resolve(decode(value))
        if(uri.scheme?.lowercase()=="https")uri.toString() else null
    }.getOrNull()

    private fun firstNonBlank(vararg values:String):String=values.firstOrNull{it.isNotBlank()}.orEmpty()

    private fun decode(value:String):String {
        if('&' !in value)return value
        var decoded=value
            .replace("&amp;","&")
            .replace("&#39;","'")
            .replace("&apos;","'")
            .replace("&quot;","\"")
            .replace("&lt;","<")
            .replace("&gt;",">")
            .replace("&nbsp;"," ")
        decoded=decimalEntity.replace(decoded){match->
            match.groupValues[1].toIntOrNull()?.let{code->runCatching{String(Character.toChars(code))}.getOrDefault(match.value)}?:match.value
        }
        decoded=hexEntity.replace(decoded){match->
            match.groupValues[1].toIntOrNull(16)?.let{code->runCatching{String(Character.toChars(code))}.getOrDefault(match.value)}?:match.value
        }
        return decoded
    }
}
