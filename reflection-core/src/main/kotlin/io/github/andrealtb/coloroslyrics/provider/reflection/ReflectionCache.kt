/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.reflection

import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe reflection cache bound to a specific ClassLoader.
 * Invalidates when the caller observes a different ClassLoader or host version.
 */
class ReflectionCache(
    classLoader: ClassLoader,
    hostVersion: String? = null
) {
    private var classLoaderRef: WeakReference<ClassLoader> = WeakReference(classLoader)
    @Volatile
    private var cachedHostVersion: String? = hostVersion

    val hostVersion: String?
        get() = cachedHostVersion

    private val classCache = ConcurrentHashMap<String, Class<*>>()
    private val methodCache = ConcurrentHashMap<String, Method>()
    private val fieldCache = ConcurrentHashMap<String, Field>()
    private val constructorCache = ConcurrentHashMap<String, Constructor<*>>()

    fun isValid(currentClassLoader: ClassLoader, currentHostVersion: String? = cachedHostVersion): Boolean {
        val cachedLoader = classLoaderRef.get()
        return cachedLoader != null &&
            cachedLoader === currentClassLoader &&
            cachedHostVersion == currentHostVersion
    }

    @Synchronized
    fun ensureValid(currentClassLoader: ClassLoader, currentHostVersion: String? = cachedHostVersion) {
        if (!isValid(currentClassLoader, currentHostVersion)) {
            clear()
            classLoaderRef = WeakReference(currentClassLoader)
            cachedHostVersion = currentHostVersion
        }
    }

    fun getOrPutClass(key: String, loader: () -> Class<*>): Class<*> {
        return classCache.computeIfAbsent(key) { loader() }
    }

    fun getOrPutMethod(key: String, resolver: () -> Method): Method {
        return methodCache.computeIfAbsent(key) { resolver() }
    }

    fun getOrPutField(key: String, resolver: () -> Field): Field {
        return fieldCache.computeIfAbsent(key) { resolver() }
    }

    fun getOrPutConstructor(key: String, resolver: () -> Constructor<*>): Constructor<*> {
        return constructorCache.computeIfAbsent(key) { resolver() }
    }

    fun clear() {
        classCache.clear()
        methodCache.clear()
        fieldCache.clear()
        constructorCache.clear()
    }
}
