package com.antigravity.meetingrecorder

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Handles multipart audio upload to the FastAPI backend via OkHttp.
 *
 * Works with both:
 *  - [RecordingOutput.LegacyFile]      – opens a FileInputStream
 *  - [RecordingOutput.MediaStoreEntry] – opens via ContentResolver
 *
 * Must be called on a background thread (blocks until upload completes).
 */
class AudioUploadManager(private val context: Context) {

    private val mediaType = "audio/mp4".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(ServerConfig.CONNECT_TIMEOUT_SECS, TimeUnit.SECONDS)
        .readTimeout(ServerConfig.READ_TIMEOUT_SECS, TimeUnit.SECONDS)
        .writeTimeout(ServerConfig.WRITE_TIMEOUT_SECS, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Uploads the given [output] to [ServerConfig.BASE_URL][ServerConfig.UPLOAD_ENDPOINT].
     * @return [UploadResult.Success] or [UploadResult.Failure].
     */
    fun upload(output: RecordingOutput): UploadResult {
        val (inputStream, filename) = openOutput(output)
            ?: return UploadResult.Failure("Cannot open recorded file for upload.")

        return try {
            performUpload(inputStream, filename)
        } catch (e: ConnectException) {
            Log.w(TAG, "Connection refused: ${e.message}")
            UploadResult.Failure("Cannot reach server. Is the backend running?", isNetworkError = true)
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Timeout: ${e.message}")
            UploadResult.Failure("Upload timed out. Check your network connection.", isNetworkError = true)
        } catch (e: IOException) {
            Log.e(TAG, "IO error during upload", e)
            UploadResult.Failure("Network error: ${e.message}", isNetworkError = true)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected upload error", e)
            UploadResult.Failure("Upload failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    /** Opens the recorded output and returns (InputStream, filename) or null on failure. */
    private fun openOutput(output: RecordingOutput): Pair<InputStream, String>? {
        return try {
            when (output) {
                is RecordingOutput.LegacyFile -> {
                    if (!output.file.exists()) {
                        Log.e(TAG, "File not found: ${output.file}")
                        return null
                    }
                    FileInputStream(output.file) to output.file.name
                }
                is RecordingOutput.MediaStoreEntry -> {
                    val stream = context.contentResolver.openInputStream(output.uri)
                    if (stream == null) {
                        Log.e(TAG, "Cannot open InputStream for URI: ${output.uri}")
                        return null
                    }
                    stream to output.displayName
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "openOutput failed", e)
            null
        }
    }

    /** Builds the multipart request and executes it. */
    private fun performUpload(inputStream: InputStream, filename: String): UploadResult {
        // Streaming RequestBody – does NOT load the entire file into memory
        val streamingBody = object : RequestBody() {
            override fun contentType() = mediaType
            override fun writeTo(sink: BufferedSink) {
                inputStream.source().use { source -> sink.writeAll(source) }
            }
        }

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, streamingBody)
            .build()

        val request = Request.Builder()
            .url("${ServerConfig.getBaseUrl(context)}${ServerConfig.UPLOAD_ENDPOINT}")
            .post(multipartBody)
            .build()

        Log.i(TAG, "Uploading '$filename' → ${request.url}")

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            Log.d(TAG, "Response ${response.code}: $body")

            return if (response.isSuccessful) {
                parseSuccess(body, filename)
            } else {
                val detail = runCatching {
                    JSONObject(body).getString("detail")
                }.getOrDefault("HTTP ${response.code}")
                UploadResult.Failure("Server rejected file: $detail")
            }
        }
    }

    private fun parseSuccess(json: String, fallbackName: String): UploadResult.Success {
        return try {
            val obj = JSONObject(json)
            UploadResult.Success(
                filename   = obj.optString("filename", fallbackName),
                sizeKb     = obj.optDouble("size_kb", 0.0),
                message    = obj.optString("message", "Uploaded successfully"),
                transcript = obj.optString("transcript", ""),
            )
        } catch (e: Exception) {
            UploadResult.Success(fallbackName, 0.0, "Uploaded successfully", "")
        }
    }

    companion object {
        private const val TAG = "AudioUploadManager"
    }
}
