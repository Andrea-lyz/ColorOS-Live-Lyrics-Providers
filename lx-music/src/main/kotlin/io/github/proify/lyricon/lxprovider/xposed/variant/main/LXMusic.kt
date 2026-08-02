/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lxprovider.xposed.variant.main

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import io.github.proify.extensions.android.copy
import io.github.proify.lyricon.lxprovider.xposed.Constants
import io.github.proify.lyricon.lxprovider.xposed.Metadata
import io.github.proify.lyricon.lxprovider.xposed.MetadataCache
import io.github.proify.lyricon.lxprovider.xposed.variant.main.Converter.toSong
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

open class LXMusic(private vararg val lyricModuleClasses: String) :
    YukiBaseHooker() {
    private companion object {
        private const val TAG = "LXMusicHooker"
        private const val ACTION_TOGGLE_TRANSLATION =
            "io.github.andrealtb.lockscreenlyrics.action.TOGGLE_TRANSLATION"
        private const val TRANSLATION_ACTION_NAME = "翻译"
    }

    private var isPlaying = false
    private var lastSyncedPosition = 0L
    private var lastUpdateTimeMillis = 0L
    private var playbackRate = 1f
    private var isDisplayTranslation = false
    private var isDisplayRoma = false
    private var lastRichLyric: List<RichLyricLine>? = null
    private var lastSongId: String? = null
    private var lastBridgeLyrics: LxMusicBridgeLyrics? = null
    private var lastBridgeLyricsTrackIdentity = ""
    private var bridgeTrackIdentity = ""
    private var bridgeTrackGeneration = 0L
    private var lastBridgePayloadKey = ""
    private var translationActionInjectionLogged = false
    private var lastBridgeTrackMetadata: Metadata? = null
    private var bluetoothLyricMetadataProjectionLogged = false
    private var lastBridgeLyricCaptureProbeKey = ""
    private var lastBridgeDeferredLyricProbeKey = ""
    private var lastBridgeNoTrackIdentityProbeKey = ""
    private var lastBridgePayloadProbeKey = ""

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var syncJob: Job? = null

    private val provider: LyriconProvider by lazy {
        val context = appContext ?: error("AppContext is required")
        LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromBase64(Constants.ICON),
        )
    }

    override fun onHook() {
        onAppLifecycle {
            onCreate {
                provider.register()
                injectLyricModule()
                hookMediaSession()
                LxMusicLspProbe.event(
                    "hook-ready",
                    "player=${LxMusicLspProbe.token(appContext?.packageName)}"
                )
            }
        }
    }

    private fun injectLyricModule() {
        lyricModuleClasses.asSequence()
            .mapNotNull { className -> className.toClassOrNull()?.resolve() }
            .firstOrNull()
            ?.apply {
                firstMethod { name = "setLyric" }.hook {
                    after {
                        val lyric = args[0] as? String ?: ""
                        val trans = args[1] as? String
                        val roma = args[2] as? String
                        updateLyric(lyric, trans, roma)
                    }
                }

                firstMethod { name = "play" }.hook {
                    after {
                        val position = (args[0] as? Int ?: 0).toLong()
                        handlePlay(position)
                    }
                }
                firstMethod { name = "pause" }.hook {
                    after {
                        handlePause()
                    }
                }
                firstMethod { name = "setPlaybackRate" }.hook {
                    after {
                        val rate = args[0] as? Float ?: 1f
                        handleSpeedChange(rate)
                    }
                }
                firstMethod { name = "toggleTranslation" }.hook {
                    after {
                        val enabled = args[0] as? Boolean ?: false
                        if (enabled != isDisplayTranslation) {
                            isDisplayTranslation = enabled
                            provider.player.setDisplayTranslation(enabled)
                        }
                    }
                }
                firstMethod { name = "toggleRoma" }.hook {
                    after {
                        val enabled = args[0] as? Boolean ?: false
                        if (enabled != isDisplayRoma) {
                            isDisplayRoma = enabled
                            provider.player.setDisplayRoma(enabled)
                        }
                    }
                }
            }
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass()
            .resolve()
            .apply {
                firstMethod {
                    name = "setPlaybackState"
                    parameters(PlaybackState::class.java)
                }.hook {
                    before {
                        val state = args[0] as? PlaybackState ?: return@before
                        val patched = copyPlaybackStateWithTranslationActionFirst(state)
                        if (patched !== state) {
                            args[0] = patched
                            if (!translationActionInjectionLogged) {
                                translationActionInjectionLogged = true
                                bridgeDebug("Injected Bridge translation toggle into PlaybackState")
                            }
                        }
                    }
                    // Lyricon position/state is driven exclusively by the LyricModule play/pause
                    // hooks and the anchor-based sync loop. Do NOT forward the platform
                    // PlaybackState here; it conflicts with the manual position calculation
                    // and causes lyric overlay flickering / progress mismatch (issue #19).
                }

                firstMethod {
                    name = "setMetadata"
                    parameters("android.media.MediaMetadata")
                }.hook {
                    after {
                        val mediaMetadata = args[0] as? MediaMetadata ?: return@after
                        val incomingMetadata = MetadataCache.save(mediaMetadata) ?: return@after
                        val metadata = selectBridgeMetadata(incomingMetadata)
                        publishBridgeTrack(metadata)
                        publishBridgeLyrics(metadata)
                        // Lyricon setSong is driven exclusively by LyricModule.setLyric via updateLyric().
                        // Re-publishing on every MediaSession metadata change causes the overlay
                        // to reset and flicker (issue #19). Bridge track/lyric publishing above
                        // is unaffected.
                    }
                }
            }
    }

    private fun selectBridgeMetadata(incoming: Metadata): Metadata {
        val stable = lastBridgeTrackMetadata
        if (LxMusicBluetoothLyricMetadataPolicy.isBluetoothLyricProjection(
                stable,
                incoming,
                lastBridgeLyrics != null
            )) {
            if (!bluetoothLyricMetadataProjectionLogged) {
                bluetoothLyricMetadataProjectionLogged = true
                bridgeDebug(
                    "Ignored Bluetooth lyric MediaSession metadata projection; retaining current track"
                )
                LxMusicLspProbe.event(
                    "metadata-projection-ignored",
                    "stable=${LxMusicLspProbe.track(stable)} " +
                        "candidate=${LxMusicLspProbe.track(incoming)}"
                )
            }
            return stable!!
        }
        lastBridgeTrackMetadata = incoming
        bluetoothLyricMetadataProjectionLogged = false
        return incoming
    }

    /**
     * ColorOS only creates a tappable Rule0 slot for a public MediaSession custom action.
     * Declaring the external-lyric capability transports the translation text, but does not create
     * that slot by itself. Keep this action in the player state so the Bridge can own its click
     * callback inside SystemUI without changing LX's own lyric UI.
     */
    private fun copyPlaybackStateWithTranslationActionFirst(
        original: PlaybackState
    ): PlaybackState {
        val originalActions = original.customActions.orEmpty()
        if (originalActions.any { it.action == ACTION_TOGGLE_TRANSLATION }) return original

        val translationAction = PlaybackState.CustomAction.Builder(
            ACTION_TOGGLE_TRANSLATION,
            TRANSLATION_ACTION_NAME,
            resolveTranslationActionPlaceholderIcon(original)
        ).build()
        return original.copy(
            customActions = buildList {
                add(translationAction)
                originalActions.filterTo(this) { it.action != ACTION_TOGGLE_TRANSLATION }
            }
        )
    }

    private fun resolveTranslationActionPlaceholderIcon(state: PlaybackState): Int {
        state.customActions.orEmpty().firstOrNull { it.icon != 0 }?.let { return it.icon }
        appContext?.applicationInfo?.icon?.takeIf { it != 0 }?.let { return it }
        return android.R.drawable.ic_menu_manage
    }

    private fun updateLyric(lyric: String, trans: String?, roma: String?) {
        val richLyric = Converter.toRich(lyric, trans, roma)
        lastRichLyric = richLyric
        val songId =
            (lyric.hashCode() + (trans?.hashCode() ?: 0) + (roma?.hashCode() ?: 0)).toString()
        lastSongId = songId
        val metadata = lastBridgeTrackMetadata ?: MetadataCache.getCurrent()
        provider.player.setSong(richLyric.toSong(songId, metadata))

        // LX's third argument is romaji. Bridge must never expose it as a translation lane.
        lastBridgeLyrics = LxMusicBridgeLyrics.from(lyric, trans)
        lastBridgeLyricsTrackIdentity = metadata?.let(::bridgeTrackIdentity).orEmpty()
        reportBridgeLyricCapture(lastBridgeLyrics, metadata)
        if (lastBridgeLyrics == null) {
            lastBridgePayloadKey = ""
            return
        }
        metadata?.let(::publishBridgeLyrics)
    }

    private fun publishBridgeTrack(metadata: Metadata) {
        val context = appContext ?: return
        val source = LxMusicSaltLyricBridge.sourceFor(context.packageName) ?: return
        val identity = bridgeTrackIdentity(metadata)
        if (identity.isBlank()) {
            val probeKey = LxMusicLspProbe.track(metadata)
            if (probeKey != lastBridgeNoTrackIdentityProbeKey) {
                lastBridgeNoTrackIdentityProbeKey = probeKey
                LxMusicLspProbe.event(
                    "track-skipped-no-identity",
                    "source=$source $probeKey"
                )
            }
            return
        }
        if (identity == bridgeTrackIdentity) return

        bridgeTrackIdentity = identity
        bridgeTrackGeneration = LxBridgeTrackGenerationPolicy.next(
            bridgeTrackGeneration,
            SystemClock.elapsedRealtime()
        )
        lastBridgePayloadKey = ""
        lastBridgeDeferredLyricProbeKey = ""
        lastBridgeNoTrackIdentityProbeKey = ""
        val sent = LxMusicSaltLyricBridge.sendTrackChanged(
            context,
            source,
            metadata,
            bridgeTrackGeneration
        )
        LxMusicLspProbe.event(
            "track-dispatched",
            "source=$source generation=$bridgeTrackGeneration sent=$sent " +
                LxMusicLspProbe.track(metadata)
        )
    }

    private fun publishBridgeLyrics(metadata: Metadata) {
        val context = appContext ?: return
        val source = LxMusicSaltLyricBridge.sourceFor(context.packageName) ?: return
        val lyrics = lastBridgeLyrics ?: return
        if (bridgeTrackGeneration <= 0L) {
            publishBridgeTrack(metadata)
        }
        if (bridgeTrackGeneration <= 0L) return
        // setMetadata for a new song can arrive before its LyricModule.setLyric callback. Never
        // attach the prior song's cached payload to that new metadata identity.
        val lyricTrackIdentity = lastBridgeLyricsTrackIdentity
        val metadataTrackIdentity = bridgeTrackIdentity(metadata)
        if (!LxMusicBridgeLyrics.matchesTrackIdentity(
                lyricTrackIdentity,
                metadataTrackIdentity
            )) {
            val probeKey = "$lyricTrackIdentity|$metadataTrackIdentity"
            if (probeKey != lastBridgeDeferredLyricProbeKey) {
                lastBridgeDeferredLyricProbeKey = probeKey
                LxMusicLspProbe.event(
                    "lyric-deferred-track-mismatch",
                    "generation=$bridgeTrackGeneration lyricTrack=" +
                        LxMusicLspProbe.token(lyricTrackIdentity) +
                        " metadataTrack=${LxMusicLspProbe.token(metadataTrackIdentity)}"
                )
            }
            return
        }
        lastBridgeDeferredLyricProbeKey = ""

        val payloadKey = listOf(
            source,
            bridgeTrackGeneration,
            metadata.id,
            lyrics.rawLyric.hashCode(),
            lyrics.translationLyric.hashCode()
        ).joinToString("|")
        if (payloadKey == lastBridgePayloadKey) return
        val sent = LxMusicSaltLyricBridge.sendLyricReady(
                context,
                source,
                metadata,
                lyrics,
                bridgeTrackGeneration
            )
        val probeKey = "$payloadKey|$sent"
        if (probeKey != lastBridgePayloadProbeKey) {
            lastBridgePayloadProbeKey = probeKey
            LxMusicLspProbe.event(
                "lyric-dispatched",
                "source=$source generation=$bridgeTrackGeneration sent=$sent " +
                    "rawChars=${lyrics.rawLyric.length} displayChars=${lyrics.lyric.length} " +
                    "translationChars=${lyrics.translationLyric.length} " +
                    LxMusicLspProbe.track(metadata)
            )
        }
        if (sent) {
            lastBridgePayloadKey = payloadKey
        }
    }

    private fun reportBridgeLyricCapture(
        lyrics: LxMusicBridgeLyrics?,
        metadata: Metadata?
    ) {
        val rawChars = lyrics?.rawLyric?.length ?: 0
        val translationChars = lyrics?.translationLyric?.length ?: 0
        val probeKey = listOf(
            metadata?.let(::bridgeTrackIdentity).orEmpty(),
            rawChars,
            translationChars,
            lyrics != null
        ).joinToString("|")
        if (probeKey == lastBridgeLyricCaptureProbeKey) return

        lastBridgeLyricCaptureProbeKey = probeKey
        LxMusicLspProbe.event(
            if (lyrics == null) "lyric-ignored-untimed" else "lyric-captured",
            "rawChars=$rawChars translationChars=$translationChars " +
                "capturedTrack=${LxMusicLspProbe.token(lastBridgeLyricsTrackIdentity)} " +
                LxMusicLspProbe.track(metadata)
        )
    }

    private fun bridgeTrackIdentity(metadata: Metadata): String {
        if (metadata.id.isNotBlank()) return "id:${metadata.id}"
        val title = metadata.title.orEmpty().trim()
        val artist = metadata.artist.orEmpty().trim()
        return if (title.isBlank() && artist.isBlank()) "" else "track:$title\u0000$artist"
    }

    private fun handlePlay(position: Long) {
        updateAnchor(position)
        setPlaybackState(true)
    }

    private fun handlePause() {
        updateAnchor(calculateCurrentPosition())
        setPlaybackState(false)
    }

    private fun handleSpeedChange(newRate: Float) {
        if (playbackRate != newRate) {
            updateAnchor(calculateCurrentPosition())
            playbackRate = newRate
            Log.d(TAG, "Playback rate changed to: $newRate")
        }
    }

    private fun bridgeDebug(message: String) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.d(TAG, message)
        }
    }

    private fun updateAnchor(position: Long) {
        lastSyncedPosition = position
        lastUpdateTimeMillis = System.currentTimeMillis()
    }

    private fun calculateCurrentPosition(): Long {
        if (!isPlaying) return lastSyncedPosition
        val elapsed = System.currentTimeMillis() - lastUpdateTimeMillis
        return lastSyncedPosition + (elapsed * playbackRate).toLong()
    }

    private fun setPlaybackState(playing: Boolean) {
        if (this.isPlaying == playing) return
        this.isPlaying = playing
        provider.player.setPlaybackState(playing)

        if (playing) startSyncLoop() else stopSyncLoop()
    }

    private fun startSyncLoop() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            while (isActive) {
                provider.player.setPosition(calculateCurrentPosition())
                delay(ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL)
            }
        }
    }

    private fun stopSyncLoop() {
        syncJob?.cancel()
        syncJob = null
    }
}
