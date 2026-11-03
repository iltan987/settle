package com.iltan.settle.data.drive

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.Throttler
import okio.buffer

/**
 * One rate cap shared by every upload running at once, and a count of the bytes they sent, for the
 * upload pacer (R23, ADR-014). Okio's [Throttler] makes every chunk body wait its turn; TCP then
 * can't send faster than the bodies are written.
 */
class UploadMeter {
    private val throttler = Throttler()
    private val sent = AtomicLong()
    private val rateLimited = AtomicBoolean()

    /** Bytes per second for all uploads together; null removes the cap. */
    fun setCap(bytesPerSecond: Long?) {
        // A burst of up to 256 KiB keeps writes efficient without overshooting for long.
        throttler.bytesPerSecond(bytesPerSecond ?: 0, WAIT_BYTES, MAX_BURST_BYTES)
    }

    /** Bytes written since the last call. */
    fun takeSent(): Long = sent.getAndSet(0)

    /** Whether Drive asked to slow down since the last call. */
    fun takeRateLimited(): Boolean = rateLimited.getAndSet(false)

    fun onRateLimited() = rateLimited.set(true)

    /**
     * A request body for [length] bytes of [buffer] that obeys the cap and is counted; [onSent]
     * runs once all of it is written.
     */
    fun body(
        buffer: ByteArray,
        length: Int,
        type: MediaType,
        onSent: () -> Unit = {},
    ): RequestBody =
        object : RequestBody() {
            override fun contentType() = type

            override fun contentLength() = length.toLong()

            override fun writeTo(sink: BufferedSink) {
                val out = throttler.sink(counting(sink)).buffer()
                out.write(buffer, 0, length)
                out.emit()
                onSent()
            }
        }

    private fun counting(sink: Sink): Sink =
        object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                sent.addAndGet(byteCount)
            }
        }

    private companion object {
        const val WAIT_BYTES = 8L * 1024
        const val MAX_BURST_BYTES = 256L * 1024
    }
}

/** [body], telling [onSent] once all of it is written. */
class SentBody(private val body: RequestBody, private val onSent: () -> Unit) : RequestBody() {
    override fun contentType() = body.contentType()

    override fun contentLength() = body.contentLength()

    override fun writeTo(sink: BufferedSink) {
        body.writeTo(sink)
        onSent()
    }
}
