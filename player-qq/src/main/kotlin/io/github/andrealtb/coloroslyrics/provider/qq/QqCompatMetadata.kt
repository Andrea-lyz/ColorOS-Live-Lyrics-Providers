/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qq

object QqCompatMetadata {
    fun readLyricInfo(builder: Any?): String? {
        if (builder == null) return null
        val built = invokeNoArg(builder, "build") ?: return null
        return invoke(built, "getString", arrayOf(String::class.java), arrayOf("lyricInfo")) as? String
    }

    fun putLyricInfo(builder: Any?, value: String): Boolean {
        if (builder == null) return false
        val method = builder.javaClass.methods.firstOrNull { method ->
            method.name == "putString" &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] == String::class.java
        } ?: return false
        return runCatching {
            method.invoke(builder, QqPlayerConstants.METADATA_KEY_LYRIC_INFO, value)
            true
        }.getOrDefault(false)
    }

    private fun invokeNoArg(target: Any, name: String): Any? {
        val method = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == 0
        } ?: return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun invoke(
        target: Any,
        name: String,
        parameterTypes: Array<Class<*>>,
        args: Array<Any?>
    ): Any? {
        val method = runCatching {
            target.javaClass.getMethod(name, *parameterTypes)
        }.getOrNull() ?: return null
        return runCatching { method.invoke(target, *args) }.getOrNull()
    }
}
