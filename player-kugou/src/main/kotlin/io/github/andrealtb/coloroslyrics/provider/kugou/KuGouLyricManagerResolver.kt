/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import java.lang.reflect.Method
import org.luckypray.dexkit.DexKitBridge

object KuGouLyricManagerResolver {
    const val CLASS_NAME = KuGouPlayerConstants.LYRIC_MANAGER_CLASS
    const val FAILURE_STRING = KuGouPlayerConstants.LOAD_FAILURE_STRING

    fun matchesLoadMethod(
        className: String?,
        parameterTypes: List<Class<*>?>,
        usingStrings: Collection<String>
    ): Boolean {
        if (className != CLASS_NAME) return false
        if (!usingStrings.contains(FAILURE_STRING)) return false
        if (parameterTypes.size != 2) return false
        return parameterTypes[0] == String::class.java &&
            parameterTypes[1] == Boolean::class.javaPrimitiveType
    }

    fun resolveLoadMethod(apkPath: String, classLoader: ClassLoader): Method? {
        ensureDexKitLoaded()
        return DexKitBridge.create(apkPath).use { bridge ->
            val methodData = bridge
                .findClass {
                    matcher {
                        className = CLASS_NAME
                    }
                }
                .findMethod {
                    matcher {
                        addUsingString(FAILURE_STRING)
                        paramTypes(String::class.java, Boolean::class.javaPrimitiveType)
                    }
                }.singleOrNull()
            methodData?.getMethodInstance(classLoader)
        }
    }

    @Volatile
    private var dexKitLoaded = false

    private fun ensureDexKitLoaded() {
        if (dexKitLoaded) return
        synchronized(this) {
            if (dexKitLoaded) return
            System.loadLibrary("dexkit")
            dexKitLoaded = true
        }
    }
}
