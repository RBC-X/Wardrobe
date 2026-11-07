package com.personal.wardrobe.domain

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhoneReplyTest {
    private fun reply(content:String):String {
        val message=JsonObject().apply{addProperty("role","assistant");addProperty("content",content)}
        val choice=JsonObject().apply{add("message",message)}
        val root=JsonObject().apply{add("choices",com.google.gson.JsonArray().apply{add(choice)})}
        return Gson().toJson(root)
    }

    @Test fun readsAFencedJsonAnswer(){
        val value=PhoneReply.objectOf(reply("```json\n{\"category\":\"top\",\"color\":\"#3d6699\"}\n```"))
        assertEquals("top",value["category"].asString)
        assertEquals("#3d6699",value["color"].asString)
    }

    @Test fun reportsProviderErrors(){
        val body=Gson().toJson(JsonObject().apply{add("error",JsonObject().apply{addProperty("message","Incorrect API key provided")})})
        val error=assertThrows(IllegalArgumentException::class.java){PhoneReply.objectOf(body)}
        assertEquals("Incorrect API key provided",error.message)
    }

    @Test fun rejectsPlainProse(){
        val error=assertThrows(IllegalArgumentException::class.java){PhoneReply.objectOf(reply("I cannot do that"))}
        assertEquals("The AI answer did not contain JSON",error.message)
    }
}
