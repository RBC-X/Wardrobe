package com.personal.wardrobe.domain

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Reads an OpenAI-compatible chat reply; the model is never trusted to be well formed. */
object PhoneReply {
    fun content(body:String):String {
        val root=runCatching{JsonParser.parseString(body).asJsonObject}.getOrElse{throw IllegalArgumentException("The AI service returned an unreadable reply")}
        root["error"]?.takeIf{it.isJsonObject}?.asJsonObject?.get("message")?.takeIf{!it.isJsonNull}?.asString?.let{throw IllegalArgumentException(it)}
        val choice=root["choices"]?.takeIf{it.isJsonArray}?.asJsonArray?.firstOrNull()?.takeIf{it.isJsonObject}?.asJsonObject?:throw IllegalArgumentException("The AI service returned no answer")
        val message=choice["message"]?.takeIf{it.isJsonObject}?.asJsonObject?:throw IllegalArgumentException("The AI service returned no answer")
        val text=message["content"]?.takeIf{!it.isJsonNull}?.asString.orEmpty().trim()
        if(text.isEmpty())throw IllegalArgumentException("The AI service returned an empty answer")
        return text
    }

    /** Tolerates fenced code blocks and surrounding prose, then insists on a JSON object. */
    fun objectOf(body:String):JsonObject {
        var text=content(body)
        if(text.startsWith("```"))text=text.substringAfter('\n').substringBeforeLast("```").trim()
        val start=text.indexOf('{');val end=text.lastIndexOf('}')
        require(start>=0&&end>start){"The AI answer did not contain JSON"}
        return runCatching{JsonParser.parseString(text.substring(start,end+1)).asJsonObject}.getOrElse{throw IllegalArgumentException("The AI answer was not valid JSON")}
    }
}
