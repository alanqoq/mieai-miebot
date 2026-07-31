package com.mieai.bot.media

import com.mieai.bot.history.PendingImage
import com.mieai.bot.history.StoredImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ImageDownloader(
    private val requestTimeout: Duration = Duration.ofSeconds(30),
) : AutoCloseable {
    private val executor: ExecutorService = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "mieai-image-http").apply { isDaemon = true }
    }
    private val client = HttpClient.newBuilder()
        .executor(executor)
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun download(image: PendingImage, maxBase64Bytes: Long): StoredImage? {
        if (maxBase64Bytes <= 0) return null
        val uri = runCatching { URI.create(image.url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.userInfo != null) return null
        val rawLimit = (maxBase64Bytes / 4L) * 3L + 2L
        val request = HttpRequest.newBuilder(uri).timeout(requestTimeout).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            if (response.statusCode() !in 200..299) return null
            val contentLength = response.headers().firstValueAsLong("Content-Length")
            if (contentLength.isPresent && contentLength.asLong > rawLimit) return null
            val bytes = ByteArrayOutputStream(minOf(rawLimit, 64 * 1024L).toInt()).use { output ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = body.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > rawLimit) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            val base64 = Base64.getEncoder().encodeToString(bytes)
            if (base64.toByteArray(Charsets.US_ASCII).size.toLong() > maxBase64Bytes) return null
            val headerMime = response.headers().firstValue("Content-Type").orElse(null)?.substringBefore(';')
            return StoredImage(image.sequence, detectMime(bytes, headerMime, image.declaredMimeType), base64)
        }
    }

    private fun detectMime(bytes: ByteArray, headerMime: String?, declaredMime: String?): String {
        headerMime?.lowercase()?.takeIf { it.startsWith("image/") }?.let { return it }
        declaredMime?.lowercase()?.takeIf { it.startsWith("image/") }?.let { return it }
        return when {
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
            ) -> "image/png"
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() ->
                "image/jpeg"
            bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII).startsWith("GIF") -> "image/gif"
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "image/bmp"
            else -> "application/octet-stream"
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
