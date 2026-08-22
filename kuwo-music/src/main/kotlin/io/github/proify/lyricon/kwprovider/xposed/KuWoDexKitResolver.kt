package io.github.proify.lyricon.kwprovider.xposed

import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object KuWoDexKitResolver {
    private const val LYRIC_STREAM_ANCHOR = "settingLyricTransType "
    private const val LYRIC_REQUEST_ANCHOR = " requestLyricTransType "
    private const val LYRIC_CACHE_ANCHOR = " cached "
    private const val LRCX_WORD_PATTERN_ANCHOR = "<(-?\\d+),(-?\\d+)(?:,-?\\d+)?>"
    private const val MULTI_TIMESTAMP_ANCHOR = "]#_#["

    @Volatile
    private var dexKitLoaded = false

    data class Targets(
        val lyricFetchMethod: Method?,
        val lrcxParserMethod: Method?
    )

    fun resolve(apkPath: String?, classLoader: ClassLoader?): Targets? {
        if (apkPath.isNullOrBlank() || classLoader == null) return null
        ensureDexKitLoaded()
        return DexKitBridge.create(apkPath).use { bridge ->
            val lyricFetch = bridge.findMethod {
                searchPackages("cn.kuwo")
                matcher {
                    usingStrings(
                        LYRIC_STREAM_ANCHOR,
                        LYRIC_REQUEST_ANCHOR,
                        LYRIC_CACHE_ANCHOR
                    )
                }
            }.mapNotNull { data ->
                runCatching { data.getMethodInstance(classLoader) }.getOrNull()
            }.singleOrNull(::isLyricFetchMethod)

            val parserMethods = mutableListOf<Method>()
            bridge.findClass {
                matcher {
                    usingStrings(LRCX_WORD_PATTERN_ANCHOR, MULTI_TIMESTAMP_ANCHOR)
                }
            }.forEach { classData ->
                classData.findMethod {
                    matcher {
                        paramTypes(String::class.java)
                    }
                }.mapNotNullTo(parserMethods) { methodData ->
                    runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
                }
            }
            val parser = parserMethods.distinctBy { method ->
                method.declaringClass.name + "#" + method.name
            }.singleOrNull(::isLrcxParserMethod)
            Targets(lyricFetch, parser)
        }
    }

    internal fun isLyricFetchMethod(method: Method): Boolean {
        val parameters = method.parameterTypes
        return Modifier.isStatic(method.modifiers) &&
            parameters.size == 3 &&
            parameters[0].name == "cn.kuwo.base.bean.Music" &&
            parameters[1] == Boolean::class.javaPrimitiveType &&
            parameters[2].name == "cn.kuwo.base.bean.Music" &&
            method.returnType != Void.TYPE
    }

    internal fun isLrcxParserMethod(method: Method): Boolean {
        return !Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
            method.returnType != Void.TYPE &&
            method.returnType != String::class.java &&
            method.returnType.methods.any { candidate ->
                candidate.parameterCount == 0 &&
                    List::class.java.isAssignableFrom(candidate.returnType)
            } &&
            runCatching { method.declaringClass.getDeclaredConstructor() }.isSuccess
    }

    @Synchronized
    private fun ensureDexKitLoaded() {
        if (dexKitLoaded) return
        System.loadLibrary("dexkit")
        dexKitLoaded = true
    }
}
