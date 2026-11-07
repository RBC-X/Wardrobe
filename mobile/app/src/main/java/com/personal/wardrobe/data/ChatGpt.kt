package com.personal.wardrobe.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.personal.wardrobe.domain.ChatGptModel
import com.personal.wardrobe.domain.ChatGptProtocol
import com.personal.wardrobe.domain.Garment
import com.personal.wardrobe.domain.OAuthLoopback
import com.personal.wardrobe.domain.Outfit
import com.personal.wardrobe.domain.Profile
import com.personal.wardrobe.domain.Rules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Official "Sign in with ChatGPT" for a local, open-source app.
 *
 * The phone opens the system browser, receives the loopback callback, validates the ID token against
 * OpenAI's JWKS, and stores the OAuth credentials encrypted with the Android keystore. Inference then
 * uses the user's ChatGPT plan through the Responses API; no PC, API key, or ChatGPT password is used.
 *
 * This is plan usage, not the Codex CLI agent: it cannot run commands or edit files.
 */
class ChatGpt(private val context:Context,private val secrets:Secrets) {
    private companion object {
        const val AUTH="https://auth.openai.com"
        const val API="https://api.openai.com/v1"
        const val DISCOVERY="$AUTH/.well-known/openid-configuration"
        const val CLIENT="dynamic_agent_client"
        const val SESSION="chatgpt-session"
        const val HOST="chatgpt-host"
        const val MODELS="chatgpt-models"
        const val MODEL="chatgpt-model"
        const val SCOPES="openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        const val PLAN_SCOPE="chatgpt.tokens.use.direct"
    }
    private data class Token(val access:String,val refresh:String,val id:String,val expiresAt:Long,val scopes:List<String>)
    private data class Session(
        val clientId:String="",val hostId:String="",val accessToken:String="",val refreshToken:String="",
        val idToken:String="",val email:String="",val subject:String="",val expiresAt:Long=0,val scopes:List<String> = emptyList()
    )
    private val gson=com.google.gson.Gson()
    private val client=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(120,TimeUnit.SECONDS).callTimeout(300,TimeUnit.SECONDS).build()
    private val random=SecureRandom()
    private val mutex=Mutex()
    @Volatile private var config:JsonObject?=null
    @Volatile private var keys:JsonArray?=null
    @Volatile private var models:List<ChatGptModel>?=null

    fun available(id:String)=id=="chatgpt"&&session()!=null
    fun account():String=session()?.email.orEmpty()
    fun providers():List<ProviderCapability> {
        val signed=available("chatgpt")
        val options=modelOptions()
        val selected=selectedModel()
        val current=options.find{it.id==selected}
        val vision=signed&&(current==null||!current.visionKnown||current.vision)
        val config=JsonObject().apply{
            addProperty("model",selected)
            add("models",JsonArray().apply{options.forEach{model->add(JsonObject().apply{
                addProperty("id",model.id);addProperty("name",model.name)
                addProperty("vision",model.vision);addProperty("visionKnown",model.visionKnown)
            })}})
        }
        return listOf(ProviderCapability(id="chatgpt",enabled=signed,authenticated=signed,text=signed,vision=vision,hasKey=signed,
            config=config,reason=if(signed)"Signed in on this phone" else "Sign in with your ChatGPT plan on this phone"))
    }

    fun modelOptions():List<ChatGptModel> = models ?: savedModels()
    fun selectedModel():String {
        val options=modelOptions()
        val saved=secrets.get(MODEL)
        if(saved.isNotBlank()&&options.any{it.id==saved})return saved
        return (options.firstOrNull{it.vision}?:options.firstOrNull())?.id.orEmpty()
    }
    fun selectModel(id:String) {
        require(modelOptions().any{it.id==id}){"Choose a model from the loaded ChatGPT list"}
        secrets.set(MODEL,id)
    }

    /** Browser sign-in with PKCE, state and nonce; blocks until the loopback callback arrives. */
    suspend fun signIn():String=withContext(Dispatchers.IO) {
        val previous=session()
        val host=hostId()
        val verifier=random(48)
        val challenge=ChatGptProtocol.codeChallenge(verifier)
        val state=random(24)
        val nonce=random(24)
        val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        try{
            val redirect="http://127.0.0.1:${server.localPort}/auth/callback"
            val url=ChatGptProtocol.authorizationUrl(
                clientId=previous?.clientId?.takeIf{it.isNotBlank()},
                hostId=host,redirectUri=redirect,state=state,nonce=nonce,codeChallenge=challenge,
                idTokenHint=previous?.idToken?.takeIf{it.isNotBlank()},
                loginHint=previous?.email?.takeIf{it.isNotBlank()}
            )
            try{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
            catch(error:Exception){throw IllegalArgumentException("No browser is available to finish ChatGPT sign-in")}
            val callback=receive(server)
            require(callback["state"]==state){"ChatGPT sign-in returned an unexpected state"}
            callback["error"]?.let{error->
                throw IllegalArgumentException((callback["error_description"]?:error).take(240))
            }
            val code=callback["code"]?.takeIf{it.isNotBlank()}?:throw IllegalArgumentException("ChatGPT sign-in did not return an authorization code")
            val issued=callback["client_id"]?.takeIf{it.isNotBlank()}?:previous?.clientId
            require(issued!=null&&issued!=CLIENT){"ChatGPT sign-in did not finish client registration"}
            require(previous==null||callback["client_id"]==null||callback["client_id"]==previous.clientId){"ChatGPT returned a different account registration"}
            val token=token(ChatGptProtocol.tokenForm(issued,code,verifier,redirect))
            val idToken=token.id.ifBlank{throw IllegalArgumentException("ChatGPT sign-in returned no identity token")}
            val claims=validateIdToken(idToken,issued,nonce)
            require(PLAN_SCOPE in token.scopes){"ChatGPT plan usage was not granted; connect again and allow it in the browser"}
            val email=claims["email"]?.takeIf{!it.isJsonNull}?.asString.orEmpty()
            val subject=claims["sub"]?.takeIf{!it.isJsonNull}?.asString.orEmpty()
            save(Session(issued,host,token.access,token.refresh,idToken,email,subject,token.expiresAt,token.scopes))
            models=null
            "ChatGPT plan connected${if(email.isNotBlank())" as $email" else ""}"
        }finally{runCatching{server.close()}}
    }

    /** Revoke the renewable session when possible, then remove local credentials. */
    suspend fun signOut():Boolean=withContext(Dispatchers.IO) {
        val current=session()
        val revoked=if(current==null)true else runCatching{
            val endpoint=config()["revocation_endpoint"]?.takeIf{!it.isJsonNull}?.asString?.takeIf{it.isNotBlank()}?:throw IllegalArgumentException("No revocation endpoint")
            postForm(endpoint,mapOf("token" to current.refreshToken,"token_type_hint" to "refresh_token","client_id" to current.clientId))
        }.isSuccess
        secrets.clear(SESSION);secrets.clear(MODELS);secrets.clear(MODEL);models=null
        revoked
    }

    suspend fun analyze(item:Garment,image:File,rules:Rules,backgroundExcluded:Boolean):JsonObject=withContext(Dispatchers.IO) {
        val access=accessToken()
        val model=model(access,true)
        val prompt="""
Analyze only the garment in this photo. Ignore the background, hangers, hands and other objects.
Return one JSON object with exactly these keys:
"category" (one of: ${rules.categories.joinToString(", ")}),
"subcategory" (closest type for that category; ${rules.subcategories.entries.joinToString("; "){(category,values)->"$category: ${values.joinToString(", ")}"}}),
"color" (dominant garment colour as #rrggbb),
"pattern" (one of: ${rules.patterns.joinToString(", ")}),
"texture" (visible surface only, one of: ${rules.textures.joinToString(", ")}),
"fit" (one of: ${rules.fits.joinToString(", ")}),
"shape" (silhouette in at most 8 words),
"styleTags" (up to 5 of: ${rules.styles.joinToString(", ")}),
"possibleBrand" (empty unless a label is clearly readable),
"confidence" (0 to 1),
"notes" (one short sentence).
Never guess an exact brand or fabric composition from appearance. The supplied image is already background-free and trimmed when possible.
""".trimIndent()
        val content=JsonArray().apply{
            add(JsonObject().apply{addProperty("type","input_text");addProperty("text",prompt)})
            add(JsonObject().apply{addProperty("type","input_image");addProperty("image_url",dataUrl(image))})
        }
        val answer=jsonObject(response(access,model,content,analysisSchema(rules)))
        answer.addProperty("garmentId",item.id)
        answer.addProperty("revision",item.revision)
        answer.addProperty("backgroundExcluded",backgroundExcluded)
        val confidence=runCatching{answer["confidence"]?.takeIf{!it.isJsonNull}?.asDouble?:0.0}.getOrDefault(0.0)
        answer.addProperty("confidence",confidence.coerceIn(0.0,1.0))
        answer
    }

    suspend fun style(outfit:Outfit,items:List<Garment>,profile:Profile):JsonObject=withContext(Dispatchers.IO) {
        val access=accessToken()
        val model=model(access,false)
        val prompt="""
Explain in two or three sentences why this outfit works for the occasion, weather and mode.
Be specific about colour harmony, silhouette, texture and formality. Do not invent a brand or fabric,
and do not give shopping advice. Return one JSON object with exactly "itemIds" (the supplied ids)
and "explanation" (at most 90 words).
Outfit: ${gson.toJson(outfit)}
Items: ${gson.toJson(items)}
Profile: ${gson.toJson(profile)}
""".trimIndent()
        val content=JsonArray().apply{add(JsonObject().apply{addProperty("type","input_text");addProperty("text",prompt)})}
        val answer=jsonObject(response(access,model,content,styleSchema))
        val explanation=answer["explanation"]?.takeIf{!it.isJsonNull}?.asString?.trim().orEmpty()
        require(explanation.isNotEmpty()){"ChatGPT returned no advice"}
        answer.add("itemIds",JsonArray().apply{outfit.itemIds.forEach{add(it)}})
        answer.addProperty("explanation",explanation.take(600))
        answer
    }

    private suspend fun accessToken():String=mutex.withLock {
        var current=session()?:throw IllegalArgumentException("Sign in with your ChatGPT plan first")
        if(current.expiresAt>Instant.now().epochSecond+60)return@withLock current.accessToken
        val refreshed=runCatching{
            token(ChatGptProtocol.refreshForm(current.clientId,current.refreshToken))
        }.getOrElse{error->
            if(error.message?.contains("invalid_grant",true)==true)secrets.clear(SESSION)
            throw IllegalArgumentException(error.message?:"Your ChatGPT session could not be renewed; sign in again")
        }
        current=current.copy(
            accessToken=refreshed.access,
            refreshToken=refreshed.refresh.ifBlank{current.refreshToken},
            idToken=refreshed.id.ifBlank{current.idToken},
            expiresAt=refreshed.expiresAt,
            scopes=refreshed.scopes.ifEmpty{current.scopes}
        )
        save(current)
        current.accessToken
    }

    suspend fun refreshModels():List<ChatGptModel> = withContext(Dispatchers.IO){
        val list=fetchModels(accessToken())
        if(list.isNotEmpty())persistModels(list)
        list
    }

    private suspend fun model(access:String,needVision:Boolean):String {
        var options=modelOptions()
        if(options.isEmpty()){
            options=runCatching{fetchModels(access)}.getOrDefault(emptyList())
            if(options.isNotEmpty())persistModels(options)
        }
        val selected=options.find{it.id==secrets.get(MODEL)}
        if(needVision&&selected!=null&&selected.visionKnown&&!selected.vision)
            throw IllegalArgumentException("Choose a vision-capable ChatGPT model in Connections before analysing a photo")
        val choice=when{
            needVision->selected?.takeIf{!it.visionKnown||it.vision}?:options.firstOrNull{it.vision}?:selected
            else->selected?:options.firstOrNull()
        }
        return choice?.id?.takeIf{it.isNotBlank()}?:"gpt-6.1-sol"
    }

    private fun fetchModels(access:String):List<ChatGptModel> {
        val root=getJson("$API/models",access)
        return ChatGptProtocol.parseModels(gson.toJson(root))
    }

    private fun persistModels(list:List<ChatGptModel>){
        models=list
        secrets.set(MODELS,gson.toJson(list))
        val saved=secrets.get(MODEL)
        if(saved.isBlank()||list.none{it.id==saved})secrets.set(MODEL,(list.firstOrNull{it.vision}?:list.first()).id)
    }

    private fun savedModels():List<ChatGptModel> {
        val text=secrets.get(MODELS)
        if(text.isBlank())return emptyList()
        return runCatching{gson.fromJson(text,Array<ChatGptModel>::class.java).toList()}.getOrDefault(emptyList())
    }

    private fun response(access:String,model:String,content:JsonArray,schema:JsonObject):String {
        val body=JsonObject().apply{
            addProperty("model",model)
            add("input",JsonArray().apply{add(JsonObject().apply{addProperty("role","user");add("content",content)})})
            addProperty("store",false);addProperty("stream",true)
            add("text",JsonObject().apply{
                add("format",JsonObject().apply{
                    addProperty("type","json_schema");addProperty("name","wardrobe");addProperty("strict",true)
                    add("schema",schema)
                })
            })
        }
        val request=Request.Builder().url("$API/responses").header("Authorization","Bearer "+access)
            .post(gson.toJson(body).toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).execute().use{response->
            if(!response.isSuccessful)throw IllegalArgumentException(responseError(response.body?.string().orEmpty(),response.code))
            val source=response.body?.source()?:throw IllegalArgumentException("ChatGPT returned no response")
            ChatGptProtocol.parseSse(generateSequence{source.readUtf8Line()})
        }
    }

    private fun failure(event:JsonObject):String {
        val response=event["response"]?.takeIf{it.isJsonObject}?.asJsonObject
        val error=response?.get("error")?.takeIf{it.isJsonObject}?.asJsonObject
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
            item.takeIf{it.isJsonObject}?.asJsonObject?.get("content")?.takeIf{it.isJsonArray}?.asJsonArray?.forEach{part->
                val piece=part.takeIf{it.isJsonObject}?.asJsonObject
                if(piece?.get("type")?.takeIf{!it.isJsonNull}?.asString=="output_text"){
                    piece["text"]?.takeIf{!it.isJsonNull}?.asString?.let{text.append(it)}
                }
            }
        }
        return text.toString()
    }

    private fun token(form:Map<String,String>):Token {
        val parsed=runCatching{ChatGptProtocol.parseToken(postForm("$AUTH/api/accounts/oauth/token",form))}
            .getOrElse{throw IllegalArgumentException(it.message?:"ChatGPT sign-in failed")}
        return Token(parsed.access,parsed.refresh,parsed.idToken,Instant.now().epochSecond+parsed.expiresIn,parsed.scopes)
    }

    private fun validateIdToken(idToken:String,clientId:String,nonce:String):JsonObject {
        val parts=idToken.split('.')
        require(parts.size==3){"ChatGPT returned an invalid identity token"}
        val header=decodeJson(parts[0])
        val payload=decodeJson(parts[1])
        require(header["alg"]?.takeIf{!it.isJsonNull}?.asString=="RS256"){"ChatGPT used an unsupported token signature"}
        val kid=header["kid"]?.takeIf{!it.isJsonNull}?.asString
        val jwk=signingKeys().firstOrNull{key->
            val item=key.takeIf{it.isJsonObject}?.asJsonObject?:return@firstOrNull false
            kid==null||item["kid"]?.takeIf{!it.isJsonNull}?.asString==kid
        }?.asJsonObject?:throw IllegalArgumentException("ChatGPT's signing key was not found")
        val modulus=BigInteger(1,decodeUrl(jwk["n"]?.asString?:throw IllegalArgumentException("Invalid ChatGPT signing key")))
        val exponent=BigInteger(1,decodeUrl(jwk["e"]?.asString?:throw IllegalArgumentException("Invalid ChatGPT signing key")))
        val publicKey=KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus,exponent))
        val verifier=Signature.getInstance("SHA256withRSA").apply{
            initVerify(publicKey);update((parts[0]+"."+parts[1]).toByteArray())
        }
        require(verifier.verify(decodeUrl(parts[2]))){"ChatGPT's identity token signature did not verify"}
        val issuer=payload["iss"]?.takeIf{!it.isJsonNull}?.asString
        val expected=config()["issuer"]?.takeIf{!it.isJsonNull}?.asString
        require(issuer==AUTH||(expected!=null&&issuer==expected)){"ChatGPT's identity token came from an unexpected issuer"}
        val audiences=when(val aud=payload["aud"]){
            is JsonArray->aud.mapNotNull{runCatching{it.asString}.getOrNull()}
            is JsonElement->listOfNotNull(runCatching{aud.asString}.getOrNull())
            else->emptyList()
        }
        require(clientId in audiences){"ChatGPT's identity token was issued for a different app"}
        require(payload["nonce"]?.takeIf{!it.isJsonNull}?.asString==nonce){"ChatGPT sign-in nonce did not match"}
        require(runCatching{payload["exp"]?.asLong?:0L}.getOrDefault(0L)>Instant.now().epochSecond){"ChatGPT sign-in expired"}
        return payload
    }

    private fun decodeJson(value:String):JsonObject=runCatching{JsonParser.parseString(String(decodeUrl(value),Charsets.UTF_8)).asJsonObject}
        .getOrElse{throw IllegalArgumentException("ChatGPT returned an invalid identity token")}

    private fun decodeUrl(value:String):ByteArray {
        val padded=value.padEnd((value.length+3)/4*4,'=')
        return Base64.getUrlDecoder().decode(padded)
    }

    private fun signingKeys():JsonArray {
        keys?.let{return it}
        val uri=config()["jwks_uri"]?.takeIf{!it.isJsonNull}?.asString?.takeIf{it.isNotBlank()}
            ?:throw IllegalArgumentException("ChatGPT's signing keys are unavailable")
        val root=getJson(uri)
        val value=root["keys"]?.takeIf{it.isJsonArray}?.asJsonArray?:throw IllegalArgumentException("ChatGPT's signing keys are unavailable")
        return value.also{keys=it}
    }

    private fun config():JsonObject {
        config?.let{return it}
        val root=getJson(DISCOVERY)
        return root.also{config=it}
    }

    private fun getJson(url:String,access:String?=null):JsonObject {
        val builder=Request.Builder().url(url).header("Accept","application/json")
        access?.let{builder.header("Authorization","Bearer "+it)}
        client.newCall(builder.build()).execute().use{response->
            val body=response.body?.string().orEmpty()
            if(!response.isSuccessful)throw IllegalArgumentException(responseError(body,response.code))
            return runCatching{JsonParser.parseString(body).asJsonObject}
                .getOrElse{throw IllegalArgumentException("OpenAI returned an unreadable response")}
        }
    }

    private fun postForm(url:String,fields:Map<String,String>):String {
        val form=FormBody.Builder().apply{fields.forEach{(name,value)->add(name,value)}}.build()
        val request=Request.Builder().url(url).header("Accept","application/json").post(form).build()
        client.newCall(request).execute().use{response->
            val body=response.body?.string().orEmpty()
            if(!response.isSuccessful)throw IllegalArgumentException(responseError(body,response.code))
            return body
        }
    }

    private fun responseError(body:String,code:Int):String {
        val root=runCatching{JsonParser.parseString(body).asJsonObject}.getOrNull()
        val error=root?.get("error")
        val message=when(error){
            is JsonObject->error["message"]?.takeIf{!it.isJsonNull}?.asString
            is JsonElement->runCatching{error.asString}.getOrNull()
            else->null
        }
        val detail=message?:root?.get("error_description")?.takeIf{!it.isJsonNull}?.asString?:root?.get("detail")?.takeIf{!it.isJsonNull}?.asString
        return detail?.take(240)?.takeIf{it.isNotBlank()}?:"OpenAI request failed (HTTP $code)"
    }

    private fun receive(server:ServerSocket):Map<String,String> = OAuthLoopback.receive(server,300_000)

    private fun session():Session?=secrets.get(SESSION).takeIf{it.isNotBlank()}
        ?.let{runCatching{gson.fromJson(it,Session::class.java)}.getOrNull()}
        ?.takeIf{it.accessToken.isNotBlank()}

    private fun save(value:Session){secrets.set(SESSION,gson.toJson(value))}

    private fun hostId():String {
        val saved=secrets.get(HOST)
        if(saved.isNotBlank())return saved
        return ("urn:uuid:"+UUID.randomUUID()).also{secrets.set(HOST,it)}
    }

    private fun random(bytes:Int):String=ByteArray(bytes).also{random.nextBytes(it)}
        .let{Base64.getUrlEncoder().withoutPadding().encodeToString(it)}

    private fun encode(value:String)=URLEncoder.encode(value,Charsets.UTF_8.name())

    private fun dataUrl(file:File):String {
        val original=BitmapFactory.decodeFile(file.path)?:throw IllegalArgumentException("This photo cannot be read")
        val scale=min(1f,1024f/max(original.width,original.height))
        val scaled=if(scale<1)Bitmap.createScaledBitmap(original,(original.width*scale).toInt().coerceAtLeast(1),(original.height*scale).toInt().coerceAtLeast(1),true)else original
        val flat=Bitmap.createBitmap(scaled.width,scaled.height,Bitmap.Config.ARGB_8888)
        Canvas(flat).apply{drawColor(Color.WHITE);drawBitmap(scaled,0f,0f,null)}
        val output=ByteArrayOutputStream();flat.compress(Bitmap.CompressFormat.JPEG,85,output)
        val encoded=Base64.getEncoder().encodeToString(output.toByteArray())
        if(flat!==original)flat.recycle();if(scaled!==original&&scaled!==flat)scaled.recycle();original.recycle()
        return "data:image/jpeg;base64,$encoded"
    }

    private fun jsonObject(text:String):JsonObject {
        var value=text.trim()
        if(value.startsWith("```"))value=value.substringAfter('\n').substringBeforeLast("```").trim()
        val start=value.indexOf('{');val end=value.lastIndexOf('}')
        require(start>=0&&end>start){"ChatGPT did not return JSON"}
        return runCatching{JsonParser.parseString(value.substring(start,end+1)).asJsonObject}
            .getOrElse{throw IllegalArgumentException("ChatGPT returned invalid JSON")}
    }

    private fun analysisSchema(rules:Rules):JsonObject=JsonObject().apply{
        addProperty("type","object")
        add("properties",JsonObject().apply{
            add("category",enumProperty(rules.categories));addPropertyObject("subcategory","string");addPropertyObject("color","string")
            add("pattern",enumProperty(rules.patterns));add("texture",enumProperty(rules.textures));add("fit",enumProperty(rules.fits))
            addPropertyObject("shape","string")
            add("styleTags",JsonObject().apply{addProperty("type","array");add("items",JsonObject().apply{addProperty("type","string")})})
            addPropertyObject("possibleBrand","string");addPropertyObject("confidence","number");addPropertyObject("notes","string")
        })
        add("required",JsonArray().apply{
            listOf("category","subcategory","color","pattern","texture","fit","shape","styleTags","possibleBrand","confidence","notes").forEach{add(it)}
        })
        addProperty("additionalProperties",false)
    }

    private val styleSchema=JsonObject().apply{
        addProperty("type","object")
        add("properties",JsonObject().apply{
            add("itemIds",JsonObject().apply{addProperty("type","array");add("items",JsonObject().apply{addProperty("type","string")})})
            addPropertyObject("explanation","string")
        })
        add("required",JsonArray().apply{add("itemIds");add("explanation")})
        addProperty("additionalProperties",false)
    }

    private fun enumProperty(values:List<String>)=JsonObject().apply{
        addProperty("type","string");add("enum",JsonArray().apply{values.forEach{add(it)}})
    }

    private fun JsonObject.addPropertyObject(name:String,type:String){add(name,JsonObject().apply{addProperty("type",type)})}
}
