/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import java.lang.reflect.Method

internal object AppleNativeCalls {
    fun call(any: Any, name: String, vararg args: Any?): Any? = runCatching {
        val method = findMethod(any.javaClass, name, args.size) ?: return@runCatching null
        method.isAccessible = true
        method.invoke(any, *args)
    }.getOrNull()

    fun callString(any: Any, name: String, vararg args: Any?): String? =
        call(any, name, *args) as? String

    fun callBoolean(any: Any, name: String, vararg args: Any?): Boolean? =
        call(any, name, *args) as? Boolean

    fun callLong(any: Any, name: String): Long = when (val value = call(any, name)) {
        is Number -> value.toLong()
        else -> 0L
    }

    fun callInt(any: Any, name: String): Int = when (val value = call(any, name)) {
        is Number -> value.toInt()
        else -> 0
    }

    fun vectorSize(vector: Any): Long = when (val value = call(vector, "size")) {
        is Number -> value.toLong()
        else -> 0L
    }

    fun vectorItem(vector: Any, index: Long): Any? {
        call(vector, "get", index)?.let { return it }
        return call(vector, "get", index.toInt())
    }

    fun unwrapPtr(ptr: Any?): Any? {
        if (ptr == null) return null
        return call(ptr, "get") ?: ptr
    }

    private fun findMethod(type: Class<*>, name: String, parameterCount: Int): Method? {
        val declared = type.declaredMethods.firstOrNull {
            it.name == name && it.parameterCount == parameterCount
        }
        if (declared != null) return declared
        return type.methods.firstOrNull {
            it.name == name && it.parameterCount == parameterCount
        }
    }
}
