package com.antigravity.meetingrecorder

import android.content.Context
import android.provider.OpenableColumns
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
 * Upload flow (avoids Render's 30 s proxy timeout):
 *  1. POST /upload-audio  → server saves file, returns immediately
 *  2. Poll GET /transcript/{filename} until "ready":true or timeout
 *
 * Works with both:
 *  - [RecordingOutput.LegacyFile]      – opens a FileInputStream
 *  - [RecordingOutput.MediaStoreEntry] – opens via ContentResolver
 *
 * Must be called on a background thread (blocks until upload + poll completes).
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
     * Uploads [output] then polls for transcript.
     * @return [UploadResult.Success] or [UploadResult.Failure].
     */
    fun upload(output: RecordingOutput): UploadResult {
        val (inputStream, filename, fileSize) = openOutput(output)
            ?: return UploadResult.Failure("Cannot open recorded file for upload.")

        return try {
            val uploadResult = performUpload(inputStream, filename, fileSize)
            if (uploadResult is UploadResult.Success && uploadResult.transcript.isEmpty()) {
                val (transcript, analysis) = pollForTranscript(uploadResult.filename)
                uploadResult.copy(
                    transcript = transcript,
                    analysis   = analysis,
                    message    = if (transcript.isNotBlank())
                                     "Audio uploaded and analysed successfully."
                                 else
                                     "Audio uploaded (analysis unavailable)."
                )
            } else {
                uploadResult
            }
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

    /**
     * Opens the recorded output and returns (InputStream, filename, fileSize) or null.
     * fileSize is used to set Content-Length, avoiding chunked transfer encoding
     * which can cause "Stream Closed" errors with some reverse proxies (e.g. Render).
     */
    private fun openOutput(output: RecordingOutput): Triple<InputStream, String, Long>? {
        return try {
            when (output) {
                is RecordingOutput.LegacyFile -> {
                    if (!output.file.exists()) {
                        Log.e(TAG, "File not found: ${output.file}")
                        return null
                    }
                    Triple(FileInputStream(output.file), output.file.name, output.file.length())
                }
                is RecordingOutput.MediaStoreEntry -> {
                    val stream = context.contentResolver.openInputStream(output.uri)
                    if (stream == null) {
                        Log.e(TAG, "Cannot open InputStream for URI: ${output.uri}")
                        return null
                    }
                    // Query ContentResolver for file size
                    val size = context.contentResolver.query(
                        output.uri, arrayOf(OpenableColumns.SIZE), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getLong(0) else -1L
                    } ?: -1L
                    Triple(stream, output.displayName, size)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "openOutput failed", e)
            null
        }
    }

    /** Builds the multipart request with Content-Length and executes it. */
    private fun performUpload(
        inputStream: InputStream,
        filename: String,
        fileSize: Long,
    ): UploadResult {
        // Streaming RequestBody with known content-length avoids chunked encoding.
        // Chunked encoding can cause "Stream Closed" on Render's proxy.
        val streamingBody = object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = fileSize   // -1 if unknown; known avoids chunked
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

        Log.i(TAG, "Uploading '$filename' (${fileSize / 1024} KB) → ${request.url}")

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

    /**
     * Polls GET /transcript/{filename} until ready or timeout.
     * Returns Pair(transcript, analysisJson).
     */
    private fun pollForTranscript(filename: String): Pair<String, String> {
        val baseUrl  = ServerConfig.getBaseUrl(context)
        val url      = "$baseUrl${ServerConfig.TRANSCRIPT_ENDPOINT}/$filename"
        val deadline = System.currentTimeMillis() + ServerConfig.TRANSCRIPT_POLL_TIMEOUT_SECS * 1000L

        Log.i(TAG, "Polling for transcript + analysis: $url")

        while (System.currentTimeMillis() < deadline) {
            try {
                val request  = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                val body     = response.body?.string() ?: "{}"

                if (response.isSuccessful) {
                    val json  = JSONObject(body)
                    val ready = json.optBoolean("ready", false)
                    if (ready) {
                        val transcript   = json.optString("transcript", "")
                        val analysisJson = json.optJSONObject("analysis")?.toString() ?: ""
                        Log.i(TAG, "Ready: transcript=${transcript.length} chars, analysis=${analysisJson.length} chars")
                        return Pair(transcript, analysisJson)
                    }
                    Log.d(TAG, "Not ready yet – waiting ${ServerConfig.TRANSCRIPT_POLL_INTERVAL_MS} ms")
                } else {
                    Log.w(TAG, "Poll HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Poll error (will retry): ${e.message}")
            }
            Thread.sleep(ServerConfig.TRANSCRIPT_POLL_INTERVAL_MS)
        }

        Log.w(TAG, "Poll timed out after ${ServerConfig.TRANSCRIPT_POLL_TIMEOUT_SECS} s")
        return Pair("", "")
    }

    private fun parseSuccess(json: String, fallbackName: String): UploadResult.Success {
        return try {
            val obj = JSONObject(json)
            UploadResult.Success(
                filename   = obj.optString("filename", fallbackName),
                sizeKb     = obj.optDouble("size_kb", 0.0),
                message    = obj.optString("message", "Uploaded successfully"),
                transcript = obj.optString("transcript", ""),
                analysis   = obj.optJSONObject("analysis")?.toString() ?: "",
            )
        } catch (e: Exception) {
            UploadResult.Success(fallbackName, 0.0, "Uploaded successfully", "", "")
        }
    }

    companion object {
        private const val TAG = "AudioUploadManager"
    }
}
