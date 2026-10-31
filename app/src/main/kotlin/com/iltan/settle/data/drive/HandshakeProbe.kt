package com.iltan.settle.data.drive

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Times a TCP handshake with Drive's host: the network's round trip, without Google's server time
 * in it. Drive's own smallest request varied between 200 and 500 ms on the phone, which hides the
 * queueing the upload pacer looks for; a round trip to the host varied by about 5 ms (ADR-014).
 */
class HandshakeProbe(private val host: String = "www.googleapis.com", private val port: Int = 443) {
    @Volatile private var address: InetAddress? = null

    /** Milliseconds, or null if the host couldn't be reached. */
    suspend fun millis(): Long? =
        withContext(Dispatchers.IO) {
            try {
                val target = address ?: InetAddress.getByName(host).also { address = it }
                Socket().use { socket ->
                    val started = System.nanoTime()
                    socket.connect(InetSocketAddress(target, port), TIMEOUT_MILLIS)
                    (System.nanoTime() - started) / 1_000_000
                }
            } catch (e: IOException) {
                // Look the host up again next time, in case the network changed.
                address = null
                null
            }
        }

    private companion object {
        const val TIMEOUT_MILLIS = 3_000
    }
}
