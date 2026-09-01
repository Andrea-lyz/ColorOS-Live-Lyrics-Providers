package io.github.andrealtb.coloroslyrics.provider.kuwo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/** Bounded KuWo cover completion that runs only inside the player process. */
internal object KuWoArtworkFetcher {
    private const val MAX_DOWNLOAD_BYTES = 3 * 1024 * 1024
    private const val MAX_SOURCE_EDGE = 8192
    private const val DECODE_EDGE = 768
    private const val PUBLISH_EDGE = 384
    private const val TIMEOUT_MS = 4_000
    private val TRUSTED_HOST = Regex("^img\\d+\\.kuwo\\.cn$", RegexOption.IGNORE_CASE)

    fun fetch(metadata: MediaMetadata): Bitmap? {
        val normalizedUrl = normalizeTrustedHttpsUrl(firstArtworkUri(metadata)) ?: return null
        val connection = URL(normalizedUrl).openConnection() as? HttpURLConnection ?: return null
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.useCaches = true
        connection.setRequestProperty("Connection", "close")
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return null
            val declaredLength = connection.contentLengthLong
            if (declaredLength > MAX_DOWNLOAD_BYTES) return null
            val contentType = connection.contentType
            if (!contentType.isNullOrBlank()
                && !contentType.lowercase(Locale.ROOT).startsWith("image/")) {
                return null
            }
            val bytes = connection.inputStream.use {
                readBounded(it, MAX_DOWNLOAD_BYTES)
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0
                || bounds.outHeight <= 0
                || bounds.outWidth > MAX_SOURCE_EDGE
                || bounds.outHeight > MAX_SOURCE_EDGE) {
                return null
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, DECODE_EDGE)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val largestEdge = maxOf(decoded.width, decoded.height)
            if (largestEdge <= PUBLISH_EDGE) return decoded
            val scale = PUBLISH_EDGE / largestEdge.toFloat()
            val scaled = Bitmap.createScaledBitmap(
                decoded,
                maxOf(1, (decoded.width * scale).toInt()),
                maxOf(1, (decoded.height * scale).toInt()),
                true
            )
            if (scaled !== decoded) decoded.recycle()
            return scaled
        } finally {
            connection.disconnect()
        }
    }

    fun withArtworkBitmap(metadata: MediaMetadata, bitmap: Bitmap): MediaMetadata {
        return MediaMetadata.Builder(metadata)
            .putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
            .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, bitmap)
            .build()
    }

    internal fun normalizeTrustedHttpsUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val parsed = URI(raw)
            val scheme = parsed.scheme?.lowercase(Locale.ROOT)
            val host = parsed.host ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (!TRUSTED_HOST.matches(host)) return null
            if (parsed.userInfo != null || parsed.path.isNullOrBlank()) return null
            if (parsed.port !in intArrayOf(-1, 80, 443)) return null
            URI(
                "https",
                null,
                host,
                -1,
                parsed.path,
                parsed.query,
                null
            ).toASCIIString()
        }.getOrNull()
    }

    internal fun calculateSampleSize(width: Int, height: Int, targetEdge: Int): Int {
        if (width <= 0 || height <= 0 || targetEdge <= 0) return 1
        var sample = 1
        while (maxOf(width / sample, height / sample) > targetEdge && sample <= 64) {
            sample *= 2
        }
        return sample
    }

    internal fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes > 0)
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("KuWo artwork exceeds byte limit")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun firstArtworkUri(metadata: MediaMetadata): String? {
        return KUWO_ARTWORK_URI_KEYS.firstNotNullOfOrNull { key ->
            metadata.getString(key)?.takeIf { it.isNotBlank() }
        }
    }
}
