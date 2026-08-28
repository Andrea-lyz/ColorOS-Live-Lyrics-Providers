/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import java.lang.reflect.Method
import org.luckypray.dexkit.DexKitBridge

/** Structural discovery used only by the live official-append hook path. */
object NeteaseLyricHookResolver {

    fun matchesLyricWriteMethod(
        parameterTypeNames: List<String>,
        returnTypeName: String?,
        usingStrings: Collection<String>
    ): Boolean = parameterTypeNames.size == 2 &&
        returnTypeName == Void.TYPE.name &&
        usingStrings.contains(NeteasePlayerConstants.METADATA_KEY_LYRIC_INFO) &&
        isLyricInfoType(parameterTypeNames[0]) &&
        isMusicInfoType(parameterTypeNames[1])

    fun matchesOfficialEncoder(
        parameterTypeNames: List<String>,
        returnTypeName: String?,
        usingStrings: Collection<String>
    ): Boolean = parameterTypeNames.size == 3 &&
        parameterTypeNames.all { it == String::class.java.name } &&
        returnTypeName == String::class.java.name &&
        usingStrings.containsAll(listOf("lyric", "songName", "artist"))

    fun matchesLyricDispatch(
        expectedHandlerClassName: String?,
        actualHandlerClassName: String?,
        what: Int,
        payloadTypeName: String?
    ): Boolean = !expectedHandlerClassName.isNullOrBlank() &&
        actualHandlerClassName == expectedHandlerClassName &&
        what == NeteasePlayerConstants.LYRIC_HANDLER_WHAT &&
        payloadTypeName?.let(::isLyricInfoType) == true

    fun matchesCurrentMusicAccessor(
        parameterTypeNames: List<String>,
        returnTypeName: String?
    ): Boolean = parameterTypeNames.isEmpty() &&
        returnTypeName?.let(::isMusicInfoType) == true

    fun matchesTrackBindMethod(
        parameterTypeNames: List<String>,
        returnTypeName: String?,
        methodName: String?
    ): Boolean = methodName != "handleMessage" &&
        parameterTypeNames.size == 1 &&
        returnTypeName == Void.TYPE.name &&
        isMusicInfoType(parameterTypeNames[0])

    fun resolveLyricWriteMethod(apkPath: String, classLoader: ClassLoader): Method? {
        ensureDexKitLoaded()
        return DexKitBridge.create(apkPath).use { bridge ->
            bridge.findMethod {
                matcher {
                    addUsingString(NeteasePlayerConstants.METADATA_KEY_LYRIC_INFO)
                    paramCount = 2
                    returnType = Void.TYPE.name
                }
            }.mapNotNull { data ->
                runCatching { data.getMethodInstance(classLoader) }.getOrNull()
            }.singleOrNull { method ->
                matchesLyricWriteMethod(
                    parameterTypeNames = method.parameterTypes.map { it.name },
                    returnTypeName = method.returnType.name,
                    usingStrings = listOf(NeteasePlayerConstants.METADATA_KEY_LYRIC_INFO)
                )
            }
        }
    }

    fun resolveOfficialEncoder(apkPath: String, classLoader: ClassLoader): Method? {
        ensureDexKitLoaded()
        return DexKitBridge.create(apkPath).use { bridge ->
            bridge.findMethod {
                matcher {
                    addUsingString("lyric")
                    addUsingString("songName")
                    addUsingString("artist")
                    paramCount = 3
                    returnType = String::class.java.name
                }
            }.mapNotNull { data ->
                runCatching { data.getMethodInstance(classLoader) }.getOrNull()
            }.singleOrNull { method ->
                matchesOfficialEncoder(
                    parameterTypeNames = method.parameterTypes.map { it.name },
                    returnTypeName = method.returnType.name,
                    usingStrings = listOf("lyric", "songName", "artist")
                )
            }
        }
    }

    fun resolveTrackBindMethods(handlerClass: Class<*>): List<Method> =
        handlerClass.declaredMethods.filter { method ->
            matchesTrackBindMethod(
                parameterTypeNames = method.parameterTypes.map { it.name },
                returnTypeName = method.returnType.name,
                methodName = method.name
            )
        }

    fun resolveCurrentMusicAccessor(handlerClass: Class<*>): Method? =
        handlerClass.declaredMethods.singleOrNull { method ->
            matchesCurrentMusicAccessor(
                parameterTypeNames = method.parameterTypes.map { it.name },
                returnTypeName = method.returnType.name
            )
        }?.apply { isAccessible = true }

    private fun isLyricInfoType(name: String): Boolean =
        name == NeteasePlayerConstants.LYRIC_INFO_CLASS || name.endsWith(".LyricInfo")

    private fun isMusicInfoType(name: String): Boolean =
        name == NeteasePlayerConstants.MUSIC_INFO_CLASS || name.endsWith(".MusicInfo")

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
