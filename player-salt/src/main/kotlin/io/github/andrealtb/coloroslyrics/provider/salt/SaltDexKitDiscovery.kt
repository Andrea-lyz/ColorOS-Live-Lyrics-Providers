/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.salt

import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.reflection.DexKitBridge
import org.luckypray.dexkit.DexKitBridge as NativeDexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.FieldsMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.ClassDataList

data class SaltModelDiscovery(
    val sourceEnum: ClassData,
    val scrollEnum: ClassData,
    val lyricResult: ClassData,
    val publisher: ClassData
)

object SaltDexKitDiscovery {

    object SaltDexKitFixture {
        const val sourceEnumName = "androidx.media3.ac1"
        const val scrollEnumName = "androidx.media3.bc1"
        const val lyricResultName = "androidx.media3.zb1"
        const val publisherName = "androidx.media3.tv1"
        const val publisherSuspendMethodName = "迉"
    }

    fun discover(apkPath: String, classLoader: ClassLoader): SaltModelDiscovery =
        DexKitBridge.withDexKit(apkPath) { bridge ->
            val sourceEnum = findSingleClassUsingStrings(
                bridge,
                "lyric source enum",
                SaltPlayerConstants.SOURCE_MARKER_EMBEDDED,
                SaltPlayerConstants.SOURCE_MARKER_LYRICS3
            )
            val scrollEnum = findSingleClassUsingStrings(
                bridge,
                "lyric scroll enum",
                SaltPlayerConstants.SCROLL_MARKER_CAN_SCROLL,
                SaltPlayerConstants.SCROLL_MARKER_NOT_SCROLL
            )
            val lyricResult = findLyricResultClass(bridge, sourceEnum, scrollEnum)
            val publisher = findFinalLyricPublisherClass(bridge, lyricResult)
            logDiscovered(sourceEnum, scrollEnum, lyricResult, publisher)
            SaltModelDiscovery(sourceEnum, scrollEnum, lyricResult, publisher)
        }

    private fun findSingleClassUsingStrings(
        bridge: NativeDexKitBridge,
        description: String,
        vararg strings: String
    ): ClassData {
        val classes = bridge.findClass(
            FindClass.create()
                .searchPackages(SaltPlayerConstants.lyricModelPackages().toList())
                .matcher(ClassMatcher.create().usingEqStrings(*strings))
        )
        return requireSingleClass(description, classes)
    }

    private fun findLyricResultClass(
        bridge: NativeDexKitBridge,
        sourceEnum: ClassData,
        scrollEnum: ClassData
    ): ClassData {
        val classes = bridge.findClass(
            FindClass.create()
                .searchPackages(SaltPlayerConstants.lyricModelPackages().toList())
                .matcher(
                    ClassMatcher.create()
                        .fields(
                            FieldsMatcher.create()
                                .addForType(sourceEnum.name)
                                .addForType(scrollEnum.name)
                                .matchType(MatchType.Contains)
                        )
                )
        )
        return requireSingleClass("lyric result class", classes)
    }

    private fun findFinalLyricPublisherClass(
        bridge: NativeDexKitBridge,
        lyricResult: ClassData
    ): ClassData {
        val classes = bridge.findClass(
            FindClass.create()
                .searchPackages(SaltPlayerConstants.lyricModelPackages().toList())
                .matcher(
                    ClassMatcher.create()
                        .fields(
                            FieldsMatcher.create()
                                .addForType(SaltPlayerConstants.SALT_SONG_CLASS)
                                .addForType(lyricResult.name)
                                .matchType(MatchType.Contains)
                        )
                )
        )
        return requireSingleClass("final lyric publisher class", classes)
    }

    private fun requireSingleClass(description: String, classes: ClassDataList): ClassData {
        if (classes.size == 1) return classes[0]
        throw IllegalStateException(
            "Expected one Salt Player $description, found ${classes.size}: $classes"
        )
    }

    internal fun requireSingleClassForTesting(
        description: String,
        candidates: List<String>
    ): String {
        if (candidates.size != 1) {
            throw IllegalStateException(
                "Expected one Salt Player $description, found ${candidates.size}: $candidates"
            )
        }
        return candidates[0]
    }

    private fun logDiscovered(
        sourceEnum: ClassData,
        scrollEnum: ClassData,
        lyricResult: ClassData,
        publisher: ClassData
    ) {
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = "provider/salt",
                area = "reflection",
                event = "SALT_MODEL_DISCOVERED",
                reason = "result=${lyricResult.name} source=${sourceEnum.name} " +
                    "scroll=${scrollEnum.name} publisher=${publisher.name}"
            )
        )
    }
}
