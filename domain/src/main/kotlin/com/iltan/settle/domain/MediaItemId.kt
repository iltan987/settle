package com.iltan.settle.domain

import java.security.SecureRandom

/**
 * Permanent identity of a media item (ADR-001).
 *
 * A ULID: 48-bit millisecond timestamp followed by 80 random bits, written as 26 Crockford base32
 * characters. IDs sort by creation time, need no coordination, and survive the item moving between
 * phone and cloud. The same value is stored in the Drive file's appProperties.
 */
@JvmInline
value class MediaItemId private constructor(val value: String) {

    /** Milliseconds since the epoch encoded in the first 10 characters. */
    val timestampMillis: Long
        get() = value.take(TIME_CHARS).fold(0L) { acc, c -> (acc shl 5) or decode(c).toLong() }

    override fun toString(): String = value

    companion object {
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val LENGTH = 26
        private const val TIME_CHARS = 10
        private const val MAX_TIMESTAMP = (1L shl 48) - 1
        private val random = SecureRandom()

        fun generate(
            nowMillis: Long,
            randomBytes: ByteArray = ByteArray(10).also(random::nextBytes),
        ): MediaItemId {
            require(nowMillis in 0..MAX_TIMESTAMP) { "Timestamp out of ULID range: $nowMillis" }
            require(randomBytes.size == 10) { "A ULID needs exactly 80 random bits" }
            val chars = CharArray(LENGTH)
            var time = nowMillis
            for (i in TIME_CHARS - 1 downTo 0) {
                chars[i] = ALPHABET[(time and 31).toInt()]
                time = time shr 5
            }
            // 80 random bits are exactly 16 base32 characters.
            var buffer = 0
            var bits = 0
            var index = TIME_CHARS
            for (byte in randomBytes) {
                buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
                bits += 8
                while (bits >= 5) {
                    bits -= 5
                    chars[index++] = ALPHABET[(buffer shr bits) and 31]
                }
            }
            return MediaItemId(String(chars))
        }

        /** Parses a stored ID, accepting lower case. Returns null if it is not a valid ULID. */
        fun parseOrNull(text: String): MediaItemId? {
            val upper = text.uppercase()
            if (upper.length != LENGTH || upper.any { it !in ALPHABET }) return null
            // The first character may only use 3 bits, or the timestamp overflows 48 bits.
            if (decode(upper[0]) > 7) return null
            return MediaItemId(upper)
        }

        private fun decode(c: Char): Int = ALPHABET.indexOf(c)
    }
}
