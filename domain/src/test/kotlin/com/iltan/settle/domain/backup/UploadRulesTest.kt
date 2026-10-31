package com.iltan.settle.domain.backup

import com.iltan.settle.domain.MediaItemId
import com.iltan.settle.domain.scan.MediaKind
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class UploadRulesTest {

    private val local = LocalDigest(10, "aa", "bb")

    @Test
    fun onlyEqualSizeSha256AndMd5Verify() {
        verify(local, RemoteFile("f", 10, "bb", "aa")) shouldBe Verification.MATCH
        verify(local, RemoteFile("f", 10, "BB", "AA")) shouldBe Verification.MATCH
    }

    @Test
    fun md5AloneNeverVerifies() {
        verify(local, RemoteFile("f", 10, "bb", null)) shouldBe Verification.PENDING
        verify(local, RemoteFile("f", null, null, null)) shouldBe Verification.PENDING
    }

    @Test
    fun anyDifferentValueIsAMismatchEvenWhileOthersArePending() {
        verify(local, RemoteFile("f", 11, "bb", "aa")) shouldBe Verification.MISMATCH
        verify(local, RemoteFile("f", 10, "bc", null)) shouldBe Verification.MISMATCH
        verify(local, RemoteFile("f", null, null, "ab")) shouldBe Verification.MISMATCH
    }

    @Test
    fun backoffDoublesUpTo32Seconds() {
        (1..8).map { backoffMillis(it, jitterMillis = 0) } shouldBe
            listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 32_000, 32_000, 32_000)
        backoffMillis(1, jitterMillis = 500) shouldBe 1_500
        backoffMillis(1_000, jitterMillis = 0) shouldBe 32_000
    }

    private fun item(path: String = "DCIM/Camera/", specialFormat: Int = 0) =
        BackupItem(
            id = MediaItemId.generate(nowMillis = 1),
            kind = MediaKind.VIDEO,
            displayName = "VID_1.mp4",
            mimeType = "video/mp4",
            relativePath = path,
            takenAtMillis = 1_792_000_000_000,
            width = 1920,
            height = 1080,
            specialFormat = specialFormat,
        )

    @Test
    fun appPropertiesCarryTheItemIdentity() {
        val motion = item(specialFormat = 2)
        val properties = appPropertiesFor(motion)

        properties["st.v"] shouldBe "1"
        properties["st.id"] shouldBe motion.id.value
        properties["st.kind"] shouldBe "video"
        properties["st.taken"] shouldBe "1792000000000"
        properties["st.dim"] shouldBe "1920x1080"
        properties["st.path"] shouldBe "DCIM/Camera/"
        properties["st.sf"] shouldBe "2"
        appPropertiesFor(item()) shouldNotContainKey "st.sf"
    }

    @Test
    fun aPathTooLongForDrivesLimitStaysOnlyInTheDescription() {
        val long = "Pictures/" + "ğ".repeat(60) + "/"
        val withLong = item(path = long)

        val properties = appPropertiesFor(withLong)
        val description = Json.parseToJsonElement(descriptionFor(withLong)).jsonObject

        properties shouldNotContainKey "st.path"
        properties.forEach { (key, value) ->
            (key.toByteArray().size + value.toByteArray().size <= APP_PROPERTY_MAX_BYTES) shouldBe
                true
        }
        description["st.path"]?.jsonPrimitive?.content shouldBe long
        description["st.name"]?.jsonPrimitive?.content shouldBe "VID_1.mp4"
        description shouldContainKey "st.id"
    }

    @Test
    fun theMonthFolderFollowsThePhonesTimeZone() {
        // 2026-10-31 23:30 UTC is already November in Istanbul (UTC+3).
        val lateOctoberUtc = 1_793_489_400_000L

        mediaFolderPath("Settle", lateOctoberUtc, ZoneOffset.UTC) shouldBe
            listOf("Settle", "media", "2026", "10")
        mediaFolderPath("Settle", lateOctoberUtc, ZoneId.of("Europe/Istanbul")) shouldBe
            listOf("Settle", "media", "2026", "11")
    }
}
