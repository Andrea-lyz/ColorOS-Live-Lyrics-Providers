/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import android.media.MediaMetadata
import android.media.session.MediaSession
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugConfig
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.core.config.YukiHookDebugSource
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeModeResolver
import io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity

/** Composition root for profile selection, platform metadata, and diagnostics. */
class NeteasePlayerHooker(
    private val hostPackage: String
) : YukiBaseHooker() {

    @Volatile
    private var debugConfigAnnounced = false

    @Volatile
    private var debugConfigSkipLogged = false

    private lateinit var coordinator: NeteaseLyricSessionCoordinator
    private var constructedSession: NeteaseConstructedLyricSession? = null
    private var officialHooks: NeteaseOfficialLyricHooks? = null

    override fun onHook() {
        val profile = NeteaseRuntimeProfile.resolve(hostPackage, processName)
        if (profile == null) {
            NeteaseDiagnostics.info(
                area = "bootstrap",
                event = "PROCESS_SKIPPED",
                process = processName,
                reason = hostPackage
            )
            return
        }
        if (!applyRuntimeAndDebug()) return
        coordinator = NeteaseLyricSessionCoordinator(hostPackage, processName)
        if (profile == NeteaseRuntimeProfile.CONSTRUCTED) {
            constructedSession = NeteaseConstructedLyricSession(processName, coordinator)
        }
        NeteaseDiagnostics.info(
            area = "bootstrap",
            event = "PROCESS_READY",
            process = processName,
            reason = profile.name
        )
        hookMediaSession(profile)
        when (profile) {
            NeteaseRuntimeProfile.CONSTRUCTED -> NeteaseDiagnostics.info(
                area = "bootstrap",
                event = "CONSTRUCTED_PROFILE_READY",
                process = processName,
                reason = "netease-9.0.40-play"
            )
            NeteaseRuntimeProfile.OFFICIAL_APPEND -> {
                officialHooks = NeteaseOfficialLyricHooks(
                    hostPackage = hostPackage,
                    processName = processName,
                    apkPath = appInfo.sourceDir,
                    classLoader = requireNotNull(appClassLoader),
                    coordinator = coordinator,
                    onRuntimeEntry = { applyRuntimeAndDebug() }
                ).also { it.install() }
            }
        }
        hookApplicationDebugConfig()
    }

    private fun applyRuntimeAndDebug(): Boolean {
        RuntimeModeResolver.notifyXposedHookActive()
        val hostContext = appContext
        val resolution = RuntimeModeResolver.resolve(hostContext)
        if (!resolution.mode.isSupported) {
            NeteaseDiagnostics.warn(
                area = "bootstrap",
                event = "HOOK_DISABLED",
                process = resolution.processName,
                reason = resolution.markerSource,
                mode = resolution.mode
            )
            return false
        }
        if (hostContext == null) {
            if (!debugConfigSkipLogged) {
                debugConfigSkipLogged = true
                NeteaseDiagnostics.info(
                    area = "bootstrap",
                    event = "DEBUG_CONFIG_SKIPPED",
                    process = processName,
                    reason = "no-host-context"
                )
            }
            return true
        }
        val debug = ProviderDebugConfig.applyDiagnostics(
            mode = resolution.mode,
            provider = ProviderId.NETEASE,
            rootSource = YukiHookDebugSource.create(hostContext)
        )
        if (debugConfigAnnounced) return true
        debugConfigAnnounced = true
        NeteaseDiagnostics.info(
            area = "bootstrap",
            event = "DEBUG_CONFIG_APPLIED",
            process = processName,
            reason = debug.reason,
            mode = resolution.mode
        )
        if (debug.enabled) {
            NeteaseDiagnostics.debug(
                area = "bootstrap",
                event = "DEBUG_LOGGING_ENABLED",
                process = processName,
                reason = debug.reason,
                mode = resolution.mode
            )
        }
        return true
    }

    private fun hookMediaSession(profile: NeteaseRuntimeProfile) {
        "android.media.session.MediaSession".toClass().resolve().apply {
            firstMethod {
                name = "setMetadata"
                parameters(MediaMetadata::class.java)
            }.hook {
                before {
                    if (NeteaseLyricInfoPublisher.isSelfPublishing()) return@before
                    val session = instance as? MediaSession ?: return@before
                    val metadata = args[0] as? MediaMetadata ?: return@before
                    val prepared = NeteaseLyricInfoPublisher.prepareHostMetadata(
                        session,
                        metadata,
                        hostPackage
                    )
                    args[0] = prepared
                    observeMetadata(prepared, profile)
                }
            }
        }
        logHooked("MEDIA_SESSION_HOOKED", "android.media.session.MediaSession#setMetadata")
    }

    private fun observeMetadata(
        metadata: MediaMetadata,
        profile: NeteaseRuntimeProfile
    ) {
        val title = firstNonBlank(
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        ) ?: return
        val payloadSongId = NeteaseLyricInfoPayloadEncoder.extractJsonString(
            metadata.getString(NeteasePlayerConstants.METADATA_KEY_LYRIC_INFO).orEmpty(),
            "songId"
        )
        val current = coordinator.bindTrack(
            TrackIdentity(
                id = firstNonBlank(
                    metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                    payloadSongId
                ),
                title = title,
                artist = firstNonBlank(
                    metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                ),
                album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            ),
            "metadata"
        )
        if (profile == NeteaseRuntimeProfile.CONSTRUCTED) {
            current.track?.let { track ->
                constructedSession?.request(track, current.generation)
            }
        }
    }

    private fun hookApplicationDebugConfig() {
        runCatching {
            "android.app.Application".toClass().resolve().firstMethod {
                name = "onCreate"
            }.hook {
                after { applyRuntimeAndDebug() }
            }
        }.onFailure {
            NeteaseDiagnostics.warn(
                area = "bootstrap",
                event = "DEBUG_CONFIG_RETRY_MISSING",
                process = processName,
                message = it.message
            )
        }
    }

    private fun logHooked(event: String, target: String) {
        NeteaseDiagnostics.info(
            area = "hook",
            event = event,
            process = processName,
            session = target,
            message = target
        )
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }
}
