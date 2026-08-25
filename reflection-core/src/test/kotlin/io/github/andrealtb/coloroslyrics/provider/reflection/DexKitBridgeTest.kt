package io.github.andrealtb.coloroslyrics.provider.reflection

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Test
import kotlin.concurrent.thread
import kotlin.test.assertEquals

class DexKitBridgeTest {
    @After fun restore() = DexKitBridge.resetNativeLibraryLoaderForTesting()

    @Test fun nativeLibraryIsLoadedOnceAcrossThreads() {
        val calls = AtomicInteger()
        val start = CountDownLatch(1)
        DexKitBridge.resetNativeLibraryLoaderForTesting { name ->
            assertEquals("dexkit", name)
            calls.incrementAndGet()
        }
        val workers = List(8) { thread { start.await(); DexKitBridge.ensureNativeLibraryLoaded() } }
        start.countDown()
        workers.forEach(Thread::join)
        assertEquals(1, calls.get())
    }
}
