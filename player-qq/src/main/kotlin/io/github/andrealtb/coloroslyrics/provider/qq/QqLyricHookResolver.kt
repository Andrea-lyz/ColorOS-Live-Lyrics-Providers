/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qq

import java.lang.reflect.Method
import io.github.andrealtb.coloroslyrics.provider.reflection.CandidateResolver
import io.github.andrealtb.coloroslyrics.provider.reflection.DexKitBridge

object QqLyricHookResolver {
    fun matchesSeedlingMethod(
        parameterTypeNames: List<String>,
        usingStrings: Collection<String>
    ): Boolean {
        if (parameterTypeNames.size != 3) return false
        if (!usingStrings.contains(QqPlayerConstants.SEEDLING_LYRIC_KEY)) return false
        if (!usingStrings.contains(QqPlayerConstants.SEEDLING_TRANS_KEY)) return false
        val builder = parameterTypeNames[0]
        val songInfo = parameterTypeNames[1]
        val lyric = parameterTypeNames[2]
        return builder.contains("MediaMetadataCompat") &&
            builder.contains("Builder") &&
            (songInfo.contains("SongInfo") || songInfo.endsWith(".SongInfo")) &&
            (lyric == QqPlayerConstants.LYRIC_ENGINE_DOCUMENT || lyric.endsWith(".k"))
    }

    fun matchesOnLoadSuc(
        className: String?,
        methodName: String?,
        parameterTypeNames: List<String>
    ): Boolean {
        if (className != QqPlayerConstants.REMOTE_LYRIC_CONTROLLER) return false
        if (methodName != "onLoadSuc") return false
        return parameterTypeNames.size == 1 &&
            (parameterTypeNames[0] == QqPlayerConstants.LYRIC_LOAD_BEAN ||
                parameterTypeNames[0].endsWith(".b"))
    }

    fun resolveSeedlingMethod(apkPath: String, classLoader: ClassLoader): Method? {
        return DexKitBridge.withDexKit(apkPath) { bridge ->
            val methods = bridge.findMethod {
                matcher {
                    addUsingString(QqPlayerConstants.SEEDLING_LYRIC_KEY)
                    addUsingString(QqPlayerConstants.SEEDLING_TRANS_KEY)
                    paramCount = 3
                }
            }.mapNotNull { data ->
                runCatching { data.getMethodInstance(classLoader) }.getOrNull()
            }
            val candidates = methods.filter { method ->
                matchesSeedlingMethod(
                    parameterTypeNames = method.parameterTypes.map { it.name },
                    usingStrings = listOf(
                        QqPlayerConstants.SEEDLING_LYRIC_KEY,
                        QqPlayerConstants.SEEDLING_TRANS_KEY
                    )
                )
            }
            CandidateResolver.resolveUniqueMethod(candidates, "QQ seedling lyricInfo writer")
        }
    }

    fun resolveOnLoadSuc(classLoader: ClassLoader, apkPath: String): Method? {
        val named = runCatching {
            val controller = classLoader.loadClass(QqPlayerConstants.REMOTE_LYRIC_CONTROLLER)
            val bean = classLoader.loadClass(QqPlayerConstants.LYRIC_LOAD_BEAN)
            controller.getDeclaredMethod("onLoadSuc", bean)
        }.getOrNull()
        if (named != null) return named
        return DexKitBridge.withDexKit(apkPath) { bridge ->
            val methods = bridge.findMethod {
                searchPackages("com.tencent.qqmusicplayerprocess.servicenew.mediasession")
                matcher {
                    name = "onLoadSuc"
                    paramCount = 1
                }
            }
            val candidates = methods.mapNotNull { methodData ->
                runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
            }
            CandidateResolver.resolveUniqueMethod(candidates, "QQ RemoteLyricController#onLoadSuc")
        }
    }
}
