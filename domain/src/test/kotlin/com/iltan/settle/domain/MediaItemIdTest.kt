package com.iltan.settle.domain

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MediaItemIdTest {

    @Test
    fun `encodes timestamp and randomness as 26 Crockford base32 characters`() {
        val id = MediaItemId.generate(nowMillis = 1_761_830_400_000L, randomBytes = ByteArray(10))

        id.value shouldMatch Regex("[0-9A-HJKMNP-TV-Z]{26}")
        id.value.takeLast(16) shouldBe "0000000000000000"
        id.timestampMillis shouldBe 1_761_830_400_000L
    }

    @Test
    fun `all-ones random bits encode as the last alphabet character`() {
        val id = MediaItemId.generate(nowMillis = 0, randomBytes = ByteArray(10) { 0xFF.toByte() })

        id.value shouldBe "0000000000" + "Z".repeat(16)
    }

    @Test
    fun `ids sort by creation time`() {
        val earlier = MediaItemId.generate(nowMillis = 1_000)
        val later = MediaItemId.generate(nowMillis = 2_000)

        (earlier.value < later.value) shouldBe true
    }

    @Test
    fun `two ids in the same millisecond differ`() {
        MediaItemId.generate(nowMillis = 5) shouldNotBe MediaItemId.generate(nowMillis = 5)
    }

    @Test
    fun `parse round-trips and accepts lower case`() {
        val id = MediaItemId.generate(nowMillis = 123_456_789)

        MediaItemId.parseOrNull(id.value.lowercase()) shouldBe id
    }

    @Test
    fun `parse rejects wrong length, invalid characters and overflowing timestamps`() {
        MediaItemId.parseOrNull("01ARZ3NDEKTSV4RRFFQ69G5FA") shouldBe null
        MediaItemId.parseOrNull("01ARZ3NDEKTSV4RRFFQ69G5FAU") shouldBe null
        MediaItemId.parseOrNull("81ARZ3NDEKTSV4RRFFQ69G5FAV") shouldBe null
        MediaItemId.parseOrNull("01ARZ3NDEKTSV4RRFFQ69G5FAV") shouldNotBe null
    }

    @Test
    fun `generate rejects timestamps beyond 48 bits`() {
        assertThrows<IllegalArgumentException> { MediaItemId.generate(nowMillis = 1L shl 48) }
    }
}
