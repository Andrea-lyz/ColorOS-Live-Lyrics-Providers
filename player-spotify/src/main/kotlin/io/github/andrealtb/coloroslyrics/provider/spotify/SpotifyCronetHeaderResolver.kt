/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.spotify

import io.github.andrealtb.coloroslyrics.provider.reflection.DexKitBridge
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Resolves Spotify's obfuscated Cronet UrlRequest builder by stable method shape.
 * Spotify does not expose okhttp3.Headers in the host class loader on current builds.
 */
object SpotifyCronetHeaderResolver {
    private const val URL_REQUEST_BUILDER = "org.chromium.net.UrlRequest\$Builder"

    fun resolve(
        apkPaths: Collection<String>,
        classLoader: ClassLoader
    ): List<Method> {
        val urlRequestBuilder = classLoader.loadClass(URL_REQUEST_BUILDER)
        val declaringClasses = LinkedHashSet<Class<*>>()
        apkPaths.asSequence()
            .filter { it.isNotBlank() && File(it).isFile }
            .forEach { apkPath ->
                runCatching {
                    DexKitBridge.withDexKit(apkPath) { bridge ->
                        bridge.findMethod {
                            matcher {
                                name = "addHeader"
                                paramTypes(String::class.java, String::class.java)
                            }
                        }.forEach { data ->
                            runCatching { data.getMethodInstance(classLoader) }
                                .getOrNull()
                                ?.declaringClass
                                ?.let(declaringClasses::add)
                        }
                    }
                }
            }

        return declaringClasses
            .filter { urlRequestBuilder.isAssignableFrom(it) }
            .flatMap { type -> type.declaredMethods.asList() }
            .filter(::isConcreteAddHeaderMethod)
            .distinctBy(::methodKey)
    }

    internal fun isConcreteAddHeaderMethod(method: Method): Boolean =
        method.name == "addHeader" &&
            method.parameterTypes.contentEquals(
                arrayOf(String::class.java, String::class.java)
            ) &&
            !Modifier.isAbstract(method.modifiers) &&
            !Modifier.isAbstract(method.declaringClass.modifiers)

    private fun methodKey(method: Method): String =
        method.declaringClass.name + "#" + method.name + ":" + method.returnType.name
}
