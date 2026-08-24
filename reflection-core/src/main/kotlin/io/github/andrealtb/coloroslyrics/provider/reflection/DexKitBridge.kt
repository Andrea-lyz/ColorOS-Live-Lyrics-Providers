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

    inline fun <R> withDexKit(apkPath: String, block: (NativeDexKitBridge) -> R): R {
        val bridge = NativeDexKitBridge.create(apkPath)
        return bridge.use(block)
    }

    inline fun <R> withDexKit(dexBytes: Array<ByteArray>, block: (NativeDexKitBridge) -> R): R {
        val bridge = NativeDexKitBridge.create(dexBytes)
        return bridge.use(block)
    }

    fun handoverMethod(
        classLoader: ClassLoader,
        methodData: MethodData
    ): Method {
        return methodData.getMethodInstance(classLoader)
    }
}
