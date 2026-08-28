/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import java.lang.reflect.Method
import io.github.andrealtb.coloroslyrics.provider.reflection.CandidateResolver
import io.github.andrealtb.coloroslyrics.provider.reflection.DexKitBridge

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
        return DexKitBridge.withDexKit(apkPath) { bridge ->
            val candidates = bridge
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
                }.mapNotNull { methodData ->
                    runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
                }
            CandidateResolver.resolveUniqueMethod(candidates, "$CLASS_NAME#loadLyric")
        }
    }
}
