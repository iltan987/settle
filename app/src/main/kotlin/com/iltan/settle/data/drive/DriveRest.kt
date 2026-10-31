package com.iltan.settle.data.drive

import com.iltan.settle.domain.backup.ChunkResult
import com.iltan.settle.domain.backup.DriveApi
import com.iltan.settle.domain.backup.DriveException
import com.iltan.settle.domain.backup.DriveFailure
import com.iltan.settle.domain.backup.NewDriveFile
import com.iltan.settle.domain.backup.RemoteFile
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Hands out OAuth access tokens for Drive. */
interface DriveTokens {
    /** A valid token; throws [DriveException] with [DriveFailure.AUTH_NEEDED] if there is none. */
    suspend fun token(): String

    /** Drops a token Drive rejected, so the next [token] call fetches a new one. */
    suspend fun invalidate(token: String)
}

/** The Drive account and its storage, from `about.get`. */
data class DriveAbout(val email: String?, val limitBytes: Long?, val usageBytes: Long?)

/**
 * The Drive v3 REST calls Settle makes, over OkHttp (ADR-010). Requests carry a bearer token; a 401
 * drops the token and retries once. Errors become [DriveException]s with the failure the backup
 * rules act on (developers.google.com/workspace/drive/api/guides/handle-errors).
 */
class DriveRest(
    client: OkHttpClient,
    private val tokens: DriveTokens,
    private val baseUrl: HttpUrl = "https://www.googleapis.com/".toHttpUrl(),
) : DriveApi {

    // A chunk is replayed by the engine after asking the server what it has, never blindly by
    // OkHttp: a resent body could start before the server's offset.
    private val client = client.newBuilder().retryOnConnectionFailure(false).build()

    override suspend fun generateIds(count: Int): List<String> {
        val url = api("drive/v3/files/generateIds").addQueryParameter("count", "$count")
        return decode<GeneratedIds>(send { url(url.build()) }).ids
    }

    override suspend fun findFolder(name: String, parentId: String?): String? {
        val query =
            "name = '${escape(name)}' and mimeType = '$FOLDER' and " +
                "'${escape(parentId ?: "root")}' in parents and trashed = false"
        val url =
            api("drive/v3/files")
                .addQueryParameter("q", query)
                .addQueryParameter("spaces", "drive")
                .addQueryParameter("fields", "files(id)")
                .addQueryParameter("pageSize", "1")
        return decode<FileList>(send { url(url.build()) }).files.firstOrNull()?.id
    }

    override suspend fun createFolder(id: String, name: String, parentId: String?) {
        val body = FileMetadata(id, name, FOLDER, parentId?.let(::listOf))
        val url = api("drive/v3/files").addQueryParameter("fields", "id")
        send { url(url.build()).post(json.encodeToString(body).toRequestBody(JSON)) }.close()
    }

    override suspend fun startUpload(file: NewDriveFile, sizeBytes: Long): String {
        val body =
            FileMetadata(
                id = file.id,
                name = file.name,
                mimeType = file.mimeType,
                parents = listOf(file.parentId),
                appProperties = file.appProperties,
                description = file.description,
            )
        val url =
            api("upload/drive/v3/files")
                .addQueryParameter("uploadType", "resumable")
                .addQueryParameter("fields", FILE_FIELDS)
        val response = send {
            url(url.build())
                .header("X-Upload-Content-Type", file.mimeType)
                .header("X-Upload-Content-Length", "$sizeBytes")
                .post(json.encodeToString(body).toRequestBody(JSON))
        }
        return response.use {
            it.header("Location")
                ?: throw DriveException(DriveFailure.TRANSIENT, "No session URI in the answer")
        }
    }

    override suspend fun uploadChunk(
        sessionUri: String,
        offset: Long,
        buffer: ByteArray,
        length: Int,
        totalBytes: Long,
    ): ChunkResult =
        uploadStep(sessionUri) {
            header("Content-Range", "bytes $offset-${offset + length - 1}/$totalBytes")
                .put(buffer.toRequestBody(OCTET_STREAM, 0, length))
        }

    override suspend fun uploadStatus(sessionUri: String, totalBytes: Long): ChunkResult =
        uploadStep(sessionUri) {
            header("Content-Range", "bytes */$totalBytes").put(ByteArray(0).toRequestBody())
        }

    override suspend fun getFile(id: String): RemoteFile? {
        val url = api("drive/v3/files/$id").addQueryParameter("fields", FILE_FIELDS)
        return try {
            decode<DriveFile>(send { url(url.build()) }).toRemote()
        } catch (e: DriveException) {
            if (e.failure == DriveFailure.NOT_FOUND) null else throw e
        }
    }

    suspend fun about(): DriveAbout {
        val url =
            api("drive/v3/about")
                .addQueryParameter("fields", "user(emailAddress),storageQuota(limit,usage)")
        val about = decode<About>(send { url(url.build()) })
        return DriveAbout(
            email = about.user?.emailAddress,
            limitBytes = about.storageQuota?.limit?.toLongOrNull(),
            usageBytes = about.storageQuota?.usage?.toLongOrNull(),
        )
    }

    /** Moves a file or folder Settle created to the Drive trash; restorable for 30 days. */
    suspend fun trash(id: String) {
        val url = api("drive/v3/files/$id").addQueryParameter("fields", "id")
        send { url(url.build()).patch("""{"trashed":true}""".toRequestBody(JSON)) }.close()
    }

    /**
     * A chunk or status request on a resumable session: 308 with `Range: bytes=0-N` means the
     * server holds N+1 bytes (no Range: none), 200/201 carries the finished file, and 404/410 mean
     * the session is gone (guides/manage-uploads#resumable).
     */
    private suspend fun uploadStep(
        sessionUri: String,
        configure: Request.Builder.() -> Request.Builder,
    ): ChunkResult {
        val response =
            try {
                send(acceptedCodes = setOf(RESUME_INCOMPLETE)) { url(sessionUri).configure() }
            } catch (e: DriveException) {
                if (e.failure == DriveFailure.NOT_FOUND) {
                    throw DriveException(DriveFailure.SESSION_GONE, e.message, e)
                }
                throw e
            }
        if (response.code != RESUME_INCOMPLETE) {
            return ChunkResult.Done(decode<DriveFile>(response).toRemote())
        }
        response.use {
            val range = it.header("Range") ?: return ChunkResult.Incomplete(0)
            val end =
                RANGE.matchEntire(range)?.groupValues?.get(1)?.toLongOrNull()
                    ?: throw DriveException(DriveFailure.TRANSIENT, "Unreadable Range: $range")
            return ChunkResult.Incomplete(end + 1)
        }
    }

    private fun api(path: String): HttpUrl.Builder = baseUrl.newBuilder().addPathSegments(path)

    /**
     * Sends the request with a token and returns a successful response (2xx or one of
     * [acceptedCodes]), which the caller must close.
     */
    private suspend fun send(
        acceptedCodes: Set<Int> = emptySet(),
        configure: Request.Builder.() -> Request.Builder,
    ): Response {
        var retriedAuth = false
        while (true) {
            val token = tokens.token()
            val request =
                Request.Builder().configure().header("Authorization", "Bearer $token").build()
            val response =
                try {
                    client.newCall(request).await()
                } catch (e: IOException) {
                    throw DriveException(DriveFailure.TRANSIENT, e.message, e)
                }
            if (response.isSuccessful || response.code in acceptedCodes) return response
            val reason = response.use { errorReason(it) }
            if (response.code == 401 && !retriedAuth) {
                retriedAuth = true
                tokens.invalidate(token)
                continue
            }
            throw DriveException(
                failureFor(response.code, reason),
                "HTTP ${response.code} ${reason.orEmpty()} for ${request.method} ${request.url.encodedPath}",
            )
        }
    }

    private inline fun <reified T> decode(response: Response): T = response.use {
        try {
            json.decodeFromString<T>(it.body.string())
        } catch (e: IOException) {
            throw DriveException(DriveFailure.TRANSIENT, e.message, e)
        }
    }

    private fun errorReason(response: Response): String? =
        try {
            json
                .decodeFromString<ErrorBody>(response.body.string())
                .error
                .errors
                .firstOrNull()
                ?.reason
        } catch (e: Exception) {
            null
        }

    companion object {
        private const val FOLDER = "application/vnd.google-apps.folder"
        private const val FILE_FIELDS = "id,size,md5Checksum,sha256Checksum"
        private const val RESUME_INCOMPLETE = 308
        private val RANGE = Regex("""bytes=0-(\d+)""")
        private val JSON = "application/json; charset=UTF-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /** Drive query strings quote with `'`; backslash escapes it and itself. */
        private fun escape(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")

        /** What a failed response means for the backup rules, per Drive's error guide. */
        fun failureFor(code: Int, reason: String?): DriveFailure =
            when {
                code == 401 -> DriveFailure.AUTH_NEEDED
                reason == "storageQuotaExceeded" -> DriveFailure.QUOTA_FULL
                code == 429 || reason == "rateLimitExceeded" || reason == "userRateLimitExceeded" ->
                    DriveFailure.RATE_LIMITED
                code == 403 && reason == "insufficientPermissions" -> DriveFailure.AUTH_NEEDED
                code == 404 -> DriveFailure.NOT_FOUND
                code == 410 -> DriveFailure.SESSION_GONE
                code == 409 -> DriveFailure.CONFLICT
                code == 408 || code >= 500 -> DriveFailure.TRANSIENT
                else -> DriveFailure.FATAL
            }
    }
}

/** Runs the call on OkHttp's dispatcher; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        }
    )
}

@Serializable private class GeneratedIds(val ids: List<String>)

@Serializable private class FileList(val files: List<DriveFile> = emptyList())

@Serializable
private class DriveFile(
    val id: String,
    val size: String? = null,
    val md5Checksum: String? = null,
    val sha256Checksum: String? = null,
) {
    fun toRemote() = RemoteFile(id, size?.toLongOrNull(), md5Checksum, sha256Checksum)
}

@Serializable
private class FileMetadata(
    val id: String,
    val name: String,
    val mimeType: String,
    val parents: List<String>? = null,
    val appProperties: Map<String, String>? = null,
    val description: String? = null,
)

@Serializable private class About(val user: User? = null, val storageQuota: Quota? = null)

@Serializable private class User(val emailAddress: String? = null)

@Serializable private class Quota(val limit: String? = null, val usage: String? = null)

@Serializable private class ErrorBody(val error: ErrorDetail)

@Serializable
private class ErrorDetail(val code: Int = 0, val errors: List<ErrorItem> = emptyList())

@Serializable private class ErrorItem(val reason: String? = null)

/** One known token, e.g. right after authorizing, before the account is stored. */
class FixedToken(private val token: String) : DriveTokens {
    override suspend fun token() = token

    override suspend fun invalidate(token: String) {}
}
