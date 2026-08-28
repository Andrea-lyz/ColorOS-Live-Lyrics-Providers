/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity
import io.github.andrealtb.coloroslyrics.provider.core.policy.TrackIdentityPolicy
import io.github.andrealtb.coloroslyrics.provider.reflection.CandidateResolver
import io.github.andrealtb.coloroslyrics.provider.reflection.ReflectionNotFoundException
import java.lang.reflect.Method

object LxLyricModuleResolver {

    fun findLyricModuleClass(
        classLoader: ClassLoader,
        hostPackage: String,
        hostVersion: String? = null
    ): Class<*> {
        val tried = LxPlayerConstants.lyricModuleCandidates(hostPackage)
        for (className in tried) {
            val loaded = runCatching { classLoader.loadClass(className) }.getOrNull()
            if (loaded != null) return loaded
        }
        throw ReflectionNotFoundException(
            targetName = "LyricModule",
            searchCriteria = "class in ${tried.joinToString()}",
            hostVersion = hostVersion
        )
    }

    fun findSetLyricMethod(
        lyricModule: Class<*>,
        hostVersion: String? = null
    ): Method {
        val candidates = lyricModule.declaredMethods.filter { method ->
            method.name == "setLyric" &&
                method.parameterTypes.size >= 3 &&
                method.parameterTypes[0] == String::class.java &&
                method.parameterTypes[1] == String::class.java &&
                method.parameterTypes[2] == String::class.java
        }
        return CandidateResolver.resolveUniqueMethod(
            candidates = candidates,
            targetName = "${lyricModule.name}#setLyric",
            searchCriteria = "setLyric(String, String, String, ...)",
            hostVersion = hostVersion
        )
    }

    fun shouldDropPendingOnTrackChange(
        pendingTrack: TrackIdentity?,
        newHostTrack: TrackIdentity?
    ): Boolean {
        val captured = pendingTrack?.takeUnless { it.isBlank } ?: return false
        return newHostTrack != null && !TrackIdentityPolicy.isSameTrack(captured, newHostTrack)
    }
}
