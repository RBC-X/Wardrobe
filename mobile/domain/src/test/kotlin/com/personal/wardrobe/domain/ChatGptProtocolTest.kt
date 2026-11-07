package com.personal.wardrobe.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the ChatGPT-plan wire format without an OpenAI account or Android runtime. */
class ChatGptProtocolTest {
    private val redirect="http://127.0.0.1:43123/auth/callback"
    private val verifier=ChatGptProtocol.codeVerifier(ByteArray(32){it.toByte()})
    private val challenge=ChatGptProtocol.codeChallenge(verifier)

    private fun params(url:String)=ChatGptProtocol.callbackQuery(url)

    @Test fun newRegistrationHasRequiredOAuthParameters() {
        val url=ChatGptProtocol.authorizationUrl(
            clientId=null,hostId="urn:uuid:test-host",redirectUri=redirect,state="state-1",nonce="nonce-1",
            codeChallenge=challenge
        )
        val query=params(url)
        assertEquals("dynamic_agent_client",query["client_id"])
        assertEquals("code",query["response_type"])
        assertEquals(redirect,query["redirect_uri"])
        assertEquals(ChatGptProtocol.SCOPES,query["scope"])
        assertEquals(ChatGptProtocol.API,query["resource"])
        assertEquals("state-1",query["state"])
        assertEquals("nonce-1",query["nonce"])
        assertEquals("urn:uuid:test-host",query["ext_agent_host_id"])
        assertEquals("Wardrobe",query["agent_name_hint"])
        assertEquals("S256",query["code_challenge_method"])
        assertEquals(challenge,query["code_challenge"])
        assertTrue(query["code_challenge"]!!.matches(Regex("[A-Za-z0-9_-]{43}")))
    }

    @Test fun returningSignInUsesIssuedClientAndHintsWithoutNameHint() {
        val url=ChatGptProtocol.authorizationUrl(
            clientId="oaiapp_existing",hostId="urn:uuid:test-host",redirectUri=redirect,state="s",nonce="n",
            codeChallenge=challenge,idTokenHint="id-token",loginHint="person@example.com"
        )
        val query=params(url)
        assertEquals("oaiapp_existing",query["client_id"])
        assertEquals("id-token",query["id_token_hint"])
        assertEquals("person@example.com",query["login_hint"])
        assertNull(query["agent_name_hint"])
    }

    @Test fun callbackDecodesEncodedValues() {
        val query=ChatGptProtocol.callbackQuery("/auth/callback?code=a%2Bb%3D&state=s%2F1&client_id=oaiapp_x")
        assertEquals("a+b=",query["code"])
        assertEquals("s/1",query["state"])
        assertEquals("oaiapp_x",query["client_id"])
    }

    @Test fun tokenAndRefreshFormsMatchTheProtocol() {
        val exchange=ChatGptProtocol.tokenForm("oaiapp_x","code-1",verifier,redirect)
        assertEquals("authorization_code",exchange["grant_type"])
        assertEquals("oaiapp_x",exchange["client_id"])
        assertEquals("code-1",exchange["code"])
        assertEquals(verifier,exchange["code_verifier"])
        assertEquals(redirect,exchange["redirect_uri"])
        assertEquals(ChatGptProtocol.API,exchange["resource"])
        val refresh=ChatGptProtocol.refreshForm("oaiapp_x","refresh-1")
        assertEquals("refresh_token",refresh["grant_type"])
        assertEquals("refresh-1",refresh["refresh_token"])
        assertEquals(ChatGptProtocol.API,refresh["resource"])
    }

    @Test fun parsesTokenResponseAndGrantedScopes() {
        val token=ChatGptProtocol.parseToken("""
            {"access_token":"access-1","refresh_token":"refresh-1","id_token":"id-1",
             "expires_in":3600,"scope":"openid chatgpt.tokens.use.direct email"}
        """.trimIndent())
        assertEquals("access-1",token.access)
        assertEquals("refresh-1",token.refresh)
        assertEquals("id-1",token.idToken)
        assertEquals(3600L,token.expiresIn)
        assertEquals(listOf("openid","chatgpt.tokens.use.direct","email"),token.scopes)
    }

    @Test fun parsesModelsAndFindsVisionCapability() {
        val models=ChatGptProtocol.parseModels("""
            {"models":[
              {"slug":"text-small","display_name":"Text Small","visibility":"list"},
              {"slug":"vision-large","display_name":"Vision Large","input_modalities":["text","image"]},
              {"slug":"hidden-model","visibility":"hidden"}
            ]}
        """.trimIndent())
        assertEquals(listOf("text-small","vision-large"),models.map{it.id})
        assertEquals("Vision Large",models[1].name)
        assertTrue(models[1].vision)
        assertTrue(models[1].visionKnown)
        assertFalse(models[0].vision)
        assertFalse(models[0].visionKnown)
        assertEquals("vision-large",ChatGptProtocol.visionModel(models)?.id)
    }

    @Test fun parsesStreamingTextDeltas() {
        val answer=ChatGptProtocol.parseSse(sequenceOf(
            "event: response.output_text.delta",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"{\\\"explanation\\\":\"}",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"\\\"Linen works.\\\"}\"}",
            "data: {\"type\":\"response.completed\",\"response\":{}}",
            "data: [DONE]"
        ))
        assertEquals("{\"explanation\":\"Linen works.\"}",answer)
    }

    @Test fun fallsBackToCompletedOutputWhenNoDeltasArrive() {
        val answer=ChatGptProtocol.parseSse(sequenceOf(
            "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"{}\"}]}]}}"
        ))
        assertEquals("{}",answer)
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectsUsageLimitFailure() {
        ChatGptProtocol.parseSse(sequenceOf(
            "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"no\"}}}"
        ))
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectsStreamWithoutCompletion() {
        ChatGptProtocol.parseSse(sequenceOf(
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"half\"}"
        ))
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectsTokenWithoutAccessToken() {
        ChatGptProtocol.parseToken("{\"refresh_token\":\"r\"}")
    }

}
