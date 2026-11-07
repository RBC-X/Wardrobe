package com.personal.wardrobe.domain

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

/** A model advertised by the ChatGPT-plan connection. */
data class ChatGptModel(val id:String="",val name:String="",val vision:Boolean=false,val visionKnown:Boolean=false)

/** Token fields returned by the OAuth token endpoint. */
data class ChatGptToken(val access:String,val refresh:String,val idToken:String,val expiresIn:Long,val scopes:List<String>)

/**
 * Pure Sign in with ChatGPT wire format.
 *
 * The Android client owns the browser, keystore and HTTP calls; this object owns the parts that can
 * be tested without an OpenAI account: authorization URL construction, loopback query parsing,
 * code/refresh forms, token/model parsing and Responses SSE decoding.
 */
object ChatGptProtocol {
    const val AUTH="https://auth.openai.com"
    const val API="https://api.openai.com/v1"
    const val CLIENT="dynamic_agent_client"
    const val SCOPES="openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"

    fun codeVerifier(random:ByteArray):String=Base64.getUrlEncoder().withoutPadding().encodeToString(random)
    fun codeChallenge(verifier:String):String=Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
    )

    fun authorizationUrl(
        clientId:String?,hostId:String,redirectUri:String,state:String,nonce:String,codeChallenge:String,
        agentName:String="Wardrobe",idTokenHint:String?=null,loginHint:String?=null,base:String=AUTH
    ):String {
        val returning=!clientId.isNullOrBlank()
        val parameters=linkedMapOf(
            "client_id" to (clientId?.takeIf{it.isNotBlank()}?:CLIENT),
            "response_type" to "code",
            "redirect_uri" to redirectUri,
            "scope" to SCOPES,
            "resource" to API,
            "state" to state,
            "nonce" to nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to codeChallenge,
            "ext_agent_host_id" to hostId
        )
        if(returning){
            idTokenHint?.takeIf{it.isNotBlank()}?.let{parameters["id_token_hint"]=it}
            loginHint?.takeIf{it.isNotBlank()}?.let{parameters["login_hint"]=it}
        }else parameters["agent_name_hint"]=agentName
        return "$base/api/accounts/authorize?"+parameters.entries.joinToString("&"){(name,value)->name+"="+encode(value)}
    }

    fun callbackQuery(target:String):Map<String,String> {
        val query=target.substringAfter('?',"")
        if(query.isBlank())return emptyMap()
        return query.split('&').filter{it.isNotBlank()}.associate{part->
            val name=URLDecoder.decode(part.substringBefore('='),Charsets.UTF_8.name())
            val value=URLDecoder.decode(part.substringAfter('=',""),Charsets.UTF_8.name())
            name to value
        }
    }

    fun tokenForm(clientId:String,code:String,verifier:String,redirectUri:String)=mapOf(
        "grant_type" to "authorization_code","client_id" to clientId,"code" to code,
        "code_verifier" to verifier,"redirect_uri" to redirectUri,"resource" to API
    )

    fun refreshForm(clientId:String,refreshToken:String)=mapOf(
        "grant_type" to "refresh_token","client_id" to clientId,
        "refresh_token" to refreshToken,"resource" to API
    )

    fun parseToken(body:String):ChatGptToken {
        val root=runCatching{JsonParser.parseString(body).asJsonObject}
            .getOrElse{throw IllegalArgumentException("ChatGPT token response was not JSON")}
        val access=root["access_token"]?.takeIf{!it.isJsonNull}?.asString?.takeIf{it.isNotBlank()}
            ?:throw IllegalArgumentException("ChatGPT returned no access token")
        val expires=runCatching{root["expires_in"]?.takeIf{!it.isJsonNull}?.asLong?:3600L}.getOrDefault(3600L)
        val scope=root["scope"]?.takeIf{!it.isJsonNull}?.asString.orEmpty()
        return ChatGptToken(
            access=access,
            refresh=root["refresh_token"]?.takeIf{!it.isJsonNull}?.asString.orEmpty(),
            idToken=root["id_token"]?.takeIf{!it.isJsonNull}?.asString.orEmpty(),
            expiresIn=expires,
            scopes=scope.split(' ').filter{it.isNotBlank()}
        )
    }

    /**
     * Reads `models` (or `data`) from /v1/models and records whether image support is known.
     * Unknown modality data is not treated as proof that a model is text-only.
     */
    fun parseModels(body:String):List<ChatGptModel> {
        val root=runCatching{JsonParser.parseString(body).asJsonObject}.getOrNull()?:return emptyList()
        val array=root["models"]?.takeIf{it.isJsonArray}?.asJsonArray
            ?:root["data"]?.takeIf{it.isJsonArray}?.asJsonArray
            ?:return emptyList()
        return array.mapNotNull{entry->
            val item=entry.takeIf{it.isJsonObject}?.asJsonObject?:return@mapNotNull null
            val visibility=item["visibility"]?.takeIf{!it.isJsonNull}?.asString
            if(visibility!=null&&visibility!="list")return@mapNotNull null
            val id=(item["slug"]?:item["id"])?.takeIf{!it.isJsonNull}?.asString?.takeIf{it.isNotBlank()}
                ?:return@mapNotNull null
            val name=item["display_name"]?.takeIf{!it.isJsonNull}?.asString?.takeIf{it.isNotBlank()}
                ?:item["name"]?.takeIf{!it.isJsonNull}?.asString?.takeIf{it.isNotBlank()}?:id
            val (known,vision)=visionSupport(item)
            ChatGptModel(id=id,name=name,vision=vision,visionKnown=known)
        }.distinctBy{it.id}
    }

    fun visionModel(models:List<ChatGptModel>):ChatGptModel?=models.firstOrNull{it.vision}?:models.firstOrNull()

    /** Decodes Responses SSE until `response.completed`; failures become readable exceptions. */
    fun parseSse(lines:Sequence<String>):String {
        val text=StringBuilder()
        var completed=false
        var completedBody:JsonObject?=null
        for(line in lines){
            val trimmed=line.trimStart()
            if(!trimmed.startsWith("data:"))continue
            val data=trimmed.removePrefix("data:").trim()
            if(data.isEmpty()||data=="[DONE]")continue
            val event=runCatching{JsonParser.parseString(data).asJsonObject}.getOrNull()?:continue
            when(event["type"]?.takeIf{!it.isJsonNull}?.asString){
                "response.output_text.delta"->text.append(event["delta"]?.takeIf{!it.isJsonNull}?.asString.orEmpty())
                "response.failed"->throw IllegalArgumentException(failure(event))
                "response.incomplete"->throw IllegalArgumentException("The ChatGPT response was incomplete")
                "response.completed"->{completed=true;completedBody=event["response"]?.takeIf{it.isJsonObject}?.asJsonObject}
            }
        }
        require(completed){"The ChatGPT response did not complete"}
        if(text.isBlank())completedBody?.let{text.append(outputText(it))}
        require(text.isNotBlank()){"ChatGPT returned no text"}
        return text.toString()
    }

    private fun failure(event:JsonObject):String {
        val response=event["response"]?.takeIf{it.isJsonObject}?.asJsonObject
        val error=response?.get("error")?.takeIf{it.isJsonObject}?.asJsonObject
            ?:event["error"]?.takeIf{it.isJsonObject}?.asJsonObject
        val message=error?.get("message")?.takeIf{!it.isJsonNull}?.asString
        val code=error?.get("code")?.takeIf{!it.isJsonNull}?.asString
        return when(code){
            "subscription_sharing_usage_limit_exceeded"->"Your ChatGPT plan usage limit is reached; try again later"
            "subscription_sharing_usage_unavailable"->"ChatGPT plan usage is unavailable for this account right now"
            else->message?.take(240)?.takeIf{it.isNotBlank()}?:"ChatGPT could not complete the request"
        }
    }

    private fun outputText(response:JsonObject):String {
        val output=response["output"]?.takeIf{it.isJsonArray}?.asJsonArray?:return ""
        val text=StringBuilder()
        output.forEach{item->
            val content=item.takeIf{it.isJsonObject}?.asJsonObject?.get("content")
            when(content){
                is JsonArray->content.forEach{part->
                    val piece=part.takeIf{it.isJsonObject}?.asJsonObject
                    if(piece?.get("type")?.takeIf{!it.isJsonNull}?.asString=="output_text"){
                        piece["text"]?.takeIf{!it.isJsonNull}?.asString?.let{text.append(it)}
                    }
                }
                is JsonElement->runCatching{content.asString}.getOrNull()?.let{text.append(it)}
            }
        }
        return text.toString()
    }

    private fun visionSupport(item:JsonObject):Pair<Boolean,Boolean> {
        var known=false
        var vision=false
        fun mark(value:Boolean?){if(value!=null){known=true;vision=vision||value}}
        for(key in listOf("vision","supports_vision")){
            item[key]?.takeIf{it.isJsonPrimitive&&it.asJsonPrimitive.isBoolean}?.asBoolean?.let(::mark)
        }
        val capabilities=item["capabilities"]?.takeIf{it.isJsonObject}?.asJsonObject
        if(capabilities!=null){
            for(key in listOf("vision","image","images","image_input")){
                capabilities[key]?.takeIf{it.isJsonPrimitive&&it.asJsonPrimitive.isBoolean}?.asBoolean?.let(::mark)
            }
        }
        fun imageArray(value:JsonElement?):Boolean? {
            val array=value?.takeIf{it.isJsonArray}?.asJsonArray?:return null
            return array.any{part->part.takeIf{it.isJsonPrimitive}?.asString?.equals("image",true)==true}
        }
        mark(imageArray(item["input_modalities"]))
        mark(imageArray(item["modalities"]))
        mark(imageArray(item["architecture"]?.takeIf{it.isJsonObject}?.asJsonObject?.get("input_modalities")))
        return known to vision
    }

    private fun encode(value:String)=URLEncoder.encode(value,Charsets.UTF_8.name())
}
