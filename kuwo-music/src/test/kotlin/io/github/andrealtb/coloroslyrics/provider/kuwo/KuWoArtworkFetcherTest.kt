package io.github.andrealtb.coloroslyrics.provider.kuwo

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KuWoArtworkFetcherTest {
    @Test
    fun trustedHttpCoverIsUpgradedWithoutChangingPathOrQuery() {
        assertEquals(
            "https://img2.kuwo.cn/star/albumcover.jpg?token=signed",
            KuWoArtworkFetcher.normalizeTrustedHttpsUrl(
                "http://img2.kuwo.cn/star/albumcover.jpg?token=signed")
        )
    }

    @Test
    fun nonKuWoOrCredentialedUrisAreRejected() {
        assertNull(KuWoArtworkFetcher.normalizeTrustedHttpsUrl("https://example.com/cover.jpg"))
        assertNull(KuWoArtworkFetcher.normalizeTrustedHttpsUrl("https://user@img1.kuwo.cn/a.jpg"))
        assertNull(KuWoArtworkFetcher.normalizeTrustedHttpsUrl("file:///sdcard/cover.jpg"))
    }

    @Test
    fun decodeSampleAndByteLimitAreBounded() {
        assertEquals(8, KuWoArtworkFetcher.calculateSampleSize(4000, 3000, 768))
        assertEquals(
            4,
            KuWoArtworkFetcher.readBounded(
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                4
            ).size
        )
    }

    @Test(expected = IOException::class)
    fun oversizedArtworkStreamIsRejected() {
        KuWoArtworkFetcher.readBounded(ByteArrayInputStream(ByteArray(5)), 4)
    }
}
