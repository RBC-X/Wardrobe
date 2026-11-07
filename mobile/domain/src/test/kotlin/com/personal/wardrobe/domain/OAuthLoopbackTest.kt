package com.personal.wardrobe.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Drives the real loopback socket with a fake browser request. */
class OAuthLoopbackTest {
    @Test(timeout=5000)
    fun receivesRealLoopbackCallbackAndReturnsHttpOk() {
        val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        val status=AtomicReference("")
        try{
            val browser=thread(start=true,isDaemon=true){
                Socket("127.0.0.1",server.localPort).use{socket->
                    socket.getOutputStream().write(
                        "GET /auth/callback?code=code%2B1&state=state-9&client_id=oaiapp_x HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                            .toByteArray(Charsets.UTF_8)
                    )
                    socket.getOutputStream().flush()
                    val reader=BufferedReader(InputStreamReader(socket.getInputStream(),Charsets.UTF_8))
                    status.set(reader.readLine().orEmpty())
                }
            }
            val query=OAuthLoopback.receive(server,5000)
            assertEquals("code+1",query["code"])
            assertEquals("state-9",query["state"])
            assertEquals("oaiapp_x",query["client_id"])
            browser.join(2000)
            assertTrue(status.get().startsWith("HTTP/1.1 200"))
        }finally{server.close()}
    }
}
