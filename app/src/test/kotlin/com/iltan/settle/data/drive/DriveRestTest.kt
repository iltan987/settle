package com.iltan.settle.data.drive

import com.iltan.settle.domain.backup.ChunkResult
import com.iltan.settle.domain.backup.DriveException
import com.iltan.settle.domain.backup.DriveFailure
import com.iltan.settle.domain.backup.NewDriveFile
import com.iltan.settle.domain.backup.RemoteFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DriveRestTest {

    private val server = MockWebServer()
    private val tokens = FakeTokens()
    private lateinit var drive: DriveRest

    private class FakeTokens : DriveTokens {
        var issued = 0
        val invalidated = mutableListOf<String>()

        override suspend fun token() = "t${++issued}"

        override suspend fun invalidate(token: String) {
            invalidated += token
        }
    }

    @Before
    fun setUp() {
        server.start()
        drive = DriveRest(OkHttpClient(), tokens, server.url("/"))
    }

    @After fun tearDown() = server.close()

    private fun respond(code: Int, body: String = "", vararg headers: Pair<String, String>) {
        val response = MockResponse.Builder().code(code).body(body)
        headers.forEach { (name, value) -> response.addHeader(name, value) }
        server.enqueue(response.build())
    }

    private fun error(code: Int, reason: String) =
        respond(code, """{"error":{"code":$code,"errors":[{"reason":"$reason"}]}}""")

    private fun take(): RecordedRequest = server.takeRequest()

    private fun failureOf(block: suspend () -> Unit): DriveFailure =
        try {
            runBlocking { block() }
            fail("Expected a DriveException")
            error("unreachable")
        } catch (e: DriveException) {
            e.failure
        }

    private val session
        get() = server.url("/upload/session/abc").toString()

    @Test
    fun startingAnUploadSendsTheMetadataAndReturnsTheSessionUri() = runBlocking {
        respond(200, headers = arrayOf("Location" to session))
        val file =
            NewDriveFile(
                id = "file1",
                name = "IMG_1.jpg",
                mimeType = "image/jpeg",
                parentId = "folder1",
                appProperties = mapOf("st.id" to "01ABC"),
                description = """{"st.id":"01ABC"}""",
            )

        assertEquals(session, drive.startUpload(file, sizeBytes = 1_000_000))

        val request = take()
        assertEquals("POST", request.method)
        assertEquals("/upload/drive/v3/files", request.url.encodedPath)
        assertEquals("resumable", request.url.queryParameter("uploadType"))
        assertEquals(
            "id,size,md5Checksum,sha256Checksum",
            request.url.queryParameter("fields"),
        )
        assertEquals("Bearer t1", request.headers["Authorization"])
        assertEquals("image/jpeg", request.headers["X-Upload-Content-Type"])
        assertEquals("1000000", request.headers["X-Upload-Content-Length"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("file1", body["id"]!!.jsonPrimitive.content)
        assertEquals("folder1", body["parents"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("01ABC", body["appProperties"]!!.jsonObject["st.id"]!!.jsonPrimitive.content)
    }

    @Test
    fun aChunkCarriesItsByteRangeAndThe308RangeSaysWhereToContinue() = runBlocking {
        respond(308, headers = arrayOf("Range" to "bytes=0-262143"))
        val buffer = ByteArray(300_000) { it.toByte() }

        val result = drive.uploadChunk(session, 0, buffer, 262_144, totalBytes = 1_000_000)

        assertEquals(ChunkResult.Incomplete(262_144), result)
        val request = take()
        assertEquals("PUT", request.method)
        assertEquals("bytes 0-262143/1000000", request.headers["Content-Range"])
        assertEquals(262_144L, request.bodySize)
        assertEquals(buffer.copyOf(262_144).toList(), request.body!!.toByteArray().toList())
    }

    @Test
    fun a308WithoutRangeMeansTheServerHasNothing() = runBlocking {
        respond(308)

        assertEquals(ChunkResult.Incomplete(0), drive.uploadStatus(session, totalBytes = 10))

        val request = take()
        assertEquals("bytes */10", request.headers["Content-Range"])
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun theLastChunkReturnsTheFileWithItsChecksums() = runBlocking {
        respond(
            200,
            """{"id":"file1","size":"10","md5Checksum":"ab","sha256Checksum":"cd","x":1}""",
        )

        val result = drive.uploadChunk(session, 0, ByteArray(10), 10, totalBytes = 10)

        assertEquals(ChunkResult.Done(RemoteFile("file1", 10, "ab", "cd")), result)
    }

    @Test
    fun aMissingSessionIsReportedAsGone() {
        respond(404)
        assertEquals(DriveFailure.SESSION_GONE, failureOf { drive.uploadStatus(session, 10) })
        respond(410)
        assertEquals(DriveFailure.SESSION_GONE, failureOf { drive.uploadStatus(session, 10) })
    }

    @Test
    fun driveErrorReasonsBecomeBackupFailures() {
        error(403, "storageQuotaExceeded")
        assertEquals(DriveFailure.QUOTA_FULL, failureOf { drive.generateIds(1) })
        error(403, "userRateLimitExceeded")
        assertEquals(DriveFailure.RATE_LIMITED, failureOf { drive.generateIds(1) })
        error(429, "rateLimitExceeded")
        assertEquals(DriveFailure.RATE_LIMITED, failureOf { drive.generateIds(1) })
        respond(503)
        assertEquals(DriveFailure.TRANSIENT, failureOf { drive.generateIds(1) })
        error(409, "duplicate")
        assertEquals(DriveFailure.CONFLICT, failureOf { drive.createFolder("f", "media", null) })
        error(400, "badRequest")
        assertEquals(DriveFailure.FATAL, failureOf { drive.generateIds(1) })
    }

    @Test
    fun aRejectedTokenIsDroppedAndTheRequestRetriedOnce() = runBlocking {
        respond(401)
        respond(200, """{"ids":["a","b"]}""")

        assertEquals(listOf("a", "b"), drive.generateIds(2))

        assertEquals(listOf("t1"), tokens.invalidated)
        assertEquals("Bearer t1", take().headers["Authorization"])
        assertEquals("Bearer t2", take().headers["Authorization"])
    }

    @Test
    fun aTokenRejectedTwiceNeedsTheUser() {
        respond(401)
        respond(401)

        assertEquals(DriveFailure.AUTH_NEEDED, failureOf { drive.generateIds(1) })
    }

    @Test
    fun aNetworkFailureIsTransient() {
        server.close()

        assertEquals(DriveFailure.TRANSIENT, failureOf { drive.generateIds(1) })
    }

    @Test
    fun foldersAreLookedUpByNameAndParentWithQuotesEscaped() = runBlocking {
        respond(200, """{"files":[{"id":"folder9"}]}""")
        respond(200, """{"files":[]}""")

        assertEquals("folder9", drive.findFolder("Settle (debug)", null))
        assertNull(drive.findFolder("İltan's", "parent1"))

        assertEquals(
            "name = 'Settle (debug)' and mimeType = 'application/vnd.google-apps.folder' and " +
                "'root' in parents and trashed = false",
            take().url.queryParameter("q"),
        )
        assertEquals(
            "name = 'İltan\\'s' and mimeType = 'application/vnd.google-apps.folder' and " +
                "'parent1' in parents and trashed = false",
            take().url.queryParameter("q"),
        )
    }

    @Test
    fun aFileThatDoesNotExistIsNull() = runBlocking {
        respond(404)

        assertNull(drive.getFile("gone"))
    }

    @Test
    fun aboutReadsTheAccountAndStorageEvenWithoutAnEmail() = runBlocking {
        respond(
            200,
            """{"user":{"emailAddress":"me@example.com"},"storageQuota":{"limit":"100","usage":"40"}}""",
        )
        respond(200, """{"storageQuota":{"usage":"40"}}""")

        assertEquals(DriveAbout("me@example.com", 100, 40), drive.about())
        assertEquals(DriveAbout(null, null, 40), drive.about())
    }
}
