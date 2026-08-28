/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import io.github.andrealtb.coloroslyrics.provider.reflection.DexKitBridge
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier

/**
 * Resolves Spotify's R8-relocated OkHttp Headers container without relying on its
 * version-specific p.* name. The immutable container keeps alternating header
 * name/value strings in its sole instance String[] field.
 */
object SpotifyShadedHeadersResolver {
    fun resolve(
        apkPaths: Collection<String>,
        classLoader: ClassLoader
    ): List<Constructor<*>> = apkPaths.asSequence()
        .filter { it.isNotBlank() && File(it).isFile }
        .flatMap { apkPath ->
            runCatching {
                DexKitBridge.withDexKit(apkPath) { bridge ->
                    bridge.findMethod {
                        matcher {
                            name = "<init>"
                            paramTypes(Array<String>::class.java)
                        }
                    }.mapNotNull { data ->
                        runCatching { data.getConstructorInstance(classLoader) }.getOrNull()
                    }
                }
            }.getOrDefault(emptyList()).asSequence()
        }
        .filter(::isShadedHeadersConstructor)
        .distinctBy { constructor ->
            constructor.declaringClass.name + "#" + constructor.parameterCount
        }
        .toList()

    internal fun isShadedHeadersConstructor(constructor: Constructor<*>): Boolean {
        if (!constructor.parameterTypes.contentEquals(arrayOf(Array<String>::class.java))) {
            return false
        }
        val type = constructor.declaringClass
        if (Modifier.isAbstract(type.modifiers) || !Iterable::class.java.isAssignableFrom(type)) {
            return false
        }
        val stringArrayFields = type.declaredFields.count { field ->
            !Modifier.isStatic(field.modifiers) && field.type == Array<String>::class.java
        }
        val hasIterator = type.declaredMethods.any { method ->
            method.name == "iterator" && method.parameterCount == 0
        }
        val hasStringLookup = type.declaredMethods.any { method ->
            method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                method.returnType == String::class.java
        }
        return stringArrayFields == 1 && hasIterator && hasStringLookup
    }
}
