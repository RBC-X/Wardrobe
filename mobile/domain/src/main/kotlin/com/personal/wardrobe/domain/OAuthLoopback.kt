package com.personal.wardrobe.domain

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * One-shot HTTP loopback callback used by the system browser during ChatGPT sign-in.
 * Kept free of Android types so a JUnit test can drive it with a real local socket.
 */
object OAuthLoopback {
    const val DEFAULT_HTML="<!doctype html><meta charset=\"utf-8\"><title>Wardrobe</title><p>ChatGPT sign-in finished. You can return to Wardrobe.</p>"

    fun receive(server:ServerSocket,timeoutMs:Int=300_000,html:String=DEFAULT_HTML):Map<String,String> {
        server.soTimeout=timeoutMs
        val socket=try{server.accept()}catch(_:SocketTimeoutException){
            throw IllegalArgumentException("ChatGPT sign-in timed out; try again")
        }
        socket.use{connection->
            val reader=BufferedReader(InputStreamReader(connection.getInputStream(),Charsets.UTF_8))
            val request=reader.readLine().orEmpty()
            val target=request.split(' ').getOrNull(1).orEmpty()
            val bytes=html.toByteArray(Charsets.UTF_8)
            val response="HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"+html
            connection.getOutputStream().apply{write(response.toByteArray(Charsets.UTF_8));flush()}
            return ChatGptProtocol.callbackQuery(target)
        }
    }
}
