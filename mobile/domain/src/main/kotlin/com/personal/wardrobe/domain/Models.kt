package com.personal.wardrobe.domain

data class Garment(
    val id:String, val name:String, val category:String="top", val subcategory:String="T-shirt",
    val color:String="#808080", val secondaryColors:List<String> = emptyList(), val pattern:String="solid", val texture:String="unknown",
    val fit:String="regular", val warmth:Int=1, val formality:Int=1, val styleTags:List<String> = listOf("casual"), val season:String="all",
    val status:String="available", val imageIds:List<String> = emptyList(), val cutoutId:String="", val brand:String="", val brandConfirmed:Boolean=false,
    val productUrl:String="", val composition:String="", val compositionVerified:Boolean=false, val manualFields:List<String> = emptyList(),
    val provenance:Map<String,Any> = emptyMap(), val revision:Int=1, val updatedAt:String="", val deleted:Boolean=false
)
data class Profile(val id:String="profile", val styles:List<String> = listOf("casual"), val favoriteColors:List<String> = emptyList(),val fit:String="regular",val sizes:String="",val dislikedCombinations:String="",val likedItems:List<String> = emptyList(),val dislikedItems:List<String> = emptyList(),val region:String="",val currency:String="USD",val shoppingBudget:Double=0.0,val stores:List<String> = emptyList(),val referenceImageId:String="",val revision:Int=1)
data class Trend(val id:String="",val title:String="",val tags:List<String> = emptyList(),val sources:List<String> = emptyList(),val confidence:Double=0.0,val season:String="all",val observedAt:String="",val expiresAt:String="")
data class Rules(val schemaVersion:Int=1,val version:String="1.0.0",val categories:List<String> = emptyList(),val subcategories:Map<String,List<String>> = emptyMap(),val styles:List<String> = emptyList(),val patterns:List<String> = emptyList(),val textures:List<String> = emptyList(),val fits:List<String> = emptyList(),val weights:Map<String,List<Double>> = emptyMap(),val principles:Map<String,String> = emptyMap(),val templates:List<List<String>> = emptyList(),val beamWidth:Int=64,val slotLimit:Int=24)
data class Outfit(val id:String,val itemIds:List<String>,val score:Double=0.0,val foundation:Double=0.0,val personal:Double=0.0,val trend:Double=0.0,val reasons:List<String> = emptyList(),val trendSources:List<Map<String,Any>> = emptyList(),val mode:String="balanced",val occasion:String="any",val weather:String="mild",val revision:Int=1,val name:String="Saved outfit",val previewImageId:String="",val season:String="all")
data class Gap(val category:String,val title:String,val color:String,val texture:String,val matchingItemIds:List<String>,val reason:String,val kind:String="wardrobe_gap")
