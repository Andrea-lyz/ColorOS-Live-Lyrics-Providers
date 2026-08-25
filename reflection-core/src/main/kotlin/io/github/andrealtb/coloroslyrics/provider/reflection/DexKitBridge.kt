/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.reflection

import java.lang.reflect.Method
import org.luckypray.dexkit.DexKitBridge as NativeDexKitBridge
import org.luckypray.dexkit.result.MethodData

/**
 * Utility wrapper for safe DexKit session lifecycle and handing over results to Java Reflection.
 */
object DexKitBridge {

    private val loadLock = Any()
    @Volatile private var nativeLibraryLoaded = false
    internal var nativeLibraryLoader: (String) -> Unit = System::loadLibrary

    fun ensureNativeLibraryLoaded() {
        if (nativeLibraryLoaded) return
        synchronized(loadLock) {
            if (nativeLibraryLoaded) return
            nativeLibraryLoader("dexkit")
            nativeLibraryLoaded = true
        }
    }

    inline fun <R> withDexKit(apkPath: String, block: (NativeDexKitBridge) -> R): R {
        ensureNativeLibraryLoaded()
        val bridge = NativeDexKitBridge.create(apkPath)
        return bridge.use(block)
    }

    inline fun <R> withDexKit(dexBytes: Array<ByteArray>, block: (NativeDexKitBridge) -> R): R {
        ensureNativeLibraryLoaded()
        val bridge = NativeDexKitBridge.create(dexBytes)
        return bridge.use(block)
    }

    fun handoverMethod(
        classLoader: ClassLoader,
        methodData: MethodData
    ): Method {
        return methodData.getMethodInstance(classLoader)
    }

    internal fun resetNativeLibraryLoaderForTesting(loader: (String) -> Unit = System::loadLibrary) {
        synchronized(loadLock) {
            nativeLibraryLoaded = false
            nativeLibraryLoader = loader
        }
    }
}
