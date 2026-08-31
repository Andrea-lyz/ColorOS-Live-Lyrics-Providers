/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticEvent
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticHasher
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderHookRuntime
import java.lang.ref.WeakReference
import java.lang.reflect.Method

class QishuiOfficialLyricsHook(
    private val authorityProvider: () -> QishuiTrackAuthority?,
    private val onPublication: (QishuiPublication, Long) -> Unit
) {
    private data class PlaybackRefreshTarget(
        val remoteControl: WeakReference<Any>,
        val remoteControlContext: Any,
        val mediaId: String
    )

    private var installed = false
    private var playbackRefreshTarget: PlaybackRefreshTarget? = null
    private var updatePlaybackStateMethod: Method? = null

    fun install(runtime: ProviderHookRuntime, classLoader: ClassLoader?): Boolean {
        if (installed || classLoader == null) return installed
        val type = runCatching {
            classLoader.loadClass(QishuiPlayerConstants.CORE_REMOTE_CONTROL_CLASS)
        }.getOrElse {
            logFailure("CORE_REMOTE_CONTROL_MISSING", it)
            return false
        }
        val methods = type.declaredMethods.filter { method ->
            method.name == "update" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes.firstOrNull()?.name?.endsWith("RemoteControlContext") == true
        }
        if (methods.isEmpty()) {
            StructuredDiagnostics.logWarning(
                DiagnosticEvent(
                    component = QishuiPlayerConstants.COMPONENT,
                    area = "hook",
                    event = "CORE_REMOTE_CONTROL_UPDATE_MISSING"
                )
            )
            return false
        }
        methods.forEachIndexed { index, method ->
            runtime.hook(
                method,
                "qishui.internal.CoreRemoteControl#update$index"
            ) {
                after {
                    val remoteControlContext = args.firstOrNull() ?: return@after
                    val playable =
                        QishuiInternalLyricsDecoder.resolvePlayable(remoteControlContext)
                            ?: return@after
                    val id = QishuiInternalLyricsDecoder.playableId(playable) ?: return@after
                    rememberPlaybackRefreshTarget(
                        instanceOrNull,
                        remoteControlContext,
                        id
                    )
                    val authority = authorityProvider() ?: return@after
                    if (authority.track.id?.trim() != id.trim()) return@after
                    val publication = QishuiInternalLyricsDecoder.decode(
                        playable,
                        authority.track
                    ) ?: return@after
                    onPublication(publication, authority.generation)
                }
            }
        }
        installed = true
        StructuredDiagnostics.logInfo(
            DiagnosticEvent(
                component = QishuiPlayerConstants.COMPONENT,
                area = "hook",
                event = "INTERNAL_LYRIC_HOOK_INSTALLED",
                reason = QishuiPlayerConstants.CORE_REMOTE_CONTROL_CLASS +
                    "#update methods=" + methods.size
            )
        )
        return true
    }

    @Synchronized
    fun refreshPlaybackState(authority: QishuiTrackAuthority, reason: String): Boolean {
        val target = playbackRefreshTarget ?: return false
        val remoteControl = target.remoteControl.get() ?: return false
        val authorityId = authority.track.id?.trim()?.takeIf(String::isNotEmpty) ?: return false
        if (target.mediaId != authorityId) return false
        val livePlayable = QishuiInternalLyricsDecoder.resolvePlayable(
            target.remoteControlContext
        ) ?: return false
        if (QishuiInternalLyricsDecoder.playableId(livePlayable)?.trim() != authorityId) {
            return false
        }
        val method = updatePlaybackStateMethod
            ?.takeIf { it.declaringClass.isAssignableFrom(remoteControl.javaClass) }
            ?: findUpdatePlaybackStateMethod(
                remoteControl.javaClass,
                target.remoteControlContext.javaClass
            )?.also { updatePlaybackStateMethod = it }
            ?: return false
        val refreshed = runCatching {
            method.invoke(remoteControl, target.remoteControlContext)
        }.isSuccess
        if (refreshed) {
            StructuredDiagnostics.logInfo(
                DiagnosticEvent(
                    component = QishuiPlayerConstants.COMPONENT,
                    area = "session",
                    event = "HOST_PLAYBACK_STATE_REFRESHED",
                    generation = authority.generation,
                    trackHash = DiagnosticHasher.sha256(authority.track.buildStableKey()),
                    reason = reason
                )
            )
        }
        return refreshed
    }

    @Synchronized
    private fun rememberPlaybackRefreshTarget(
        remoteControl: Any?,
        remoteControlContext: Any,
        mediaId: String
    ) {
        if (remoteControl == null || mediaId.isBlank()) return
        playbackRefreshTarget = PlaybackRefreshTarget(
            remoteControl = WeakReference(remoteControl),
            remoteControlContext = remoteControlContext,
            mediaId = mediaId.trim()
        )
    }

    private fun findUpdatePlaybackStateMethod(
        owner: Class<*>,
        contextType: Class<*>
    ): Method? = generateSequence(owner as Class<*>?) { it.superclass }
        .firstNotNullOfOrNull { type ->
            type.declaredMethods.firstOrNull { method ->
                method.name == "updatePlaybackState" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isAssignableFrom(contextType)
            }
        }
        ?.apply { isAccessible = true }

    private fun logFailure(event: String, throwable: Throwable) {
        StructuredDiagnostics.logError(
            DiagnosticEvent(
                component = QishuiPlayerConstants.COMPONENT,
                area = "hook",
                event = event,
                reason = throwable.javaClass.simpleName
            ),
            throwable = throwable
        )
    }
}
