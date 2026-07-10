/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Application
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as MediaSessionPlaybackState
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.VagueType
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XposedHelpers
import io.github.proify.extensions.android.ScreenStateMonitor
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
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object Apple : YukiBaseHooker() {
    private const val APPLE_METADATA_KEY_MEDIA_ID =
        "com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID"
    private const val APPLE_METADATA_KEY_PLAYBACK_ENDPOINT_TYPE =
        "com.apple.android.music.playback.metadata.METADATA_KEY_PLAYBACK_ENDPOINT_TYPE"

    private lateinit var application: Application
    private lateinit var classLoader: ClassLoader

    private var isPlaying = false
    private var exoMediaPlayerInstance: Any? = null
    private var getPositionMethod: Method? = null
    private var dexKitBridge: DexKitBridge? = null

    private val coroutineScope by lazy { CoroutineScope(Dispatchers.Default + SupervisorJob()) }
    private var progressJob: Job? = null

    private var provider: LyriconProvider? = null
    private var lyricRequester: LyricRequester? = null

    init {
        runCatching { System.loadLibrary("dexkit") }
            .onFailure { YLog.error("load dexkit failed", it) }
    }

    override fun onHook() {
        onAppLifecycle {
            onCreate { onAppCreate() }
        }
    }

    private fun onAppCreate() {
        application = appContext ?: return
        classLoader = appClassLoader ?: return
        dexKitBridge = runCatching {
            DexKitBridge.create(appInfo.sourceDir)
        }.onFailure {
            YLog.error("create Apple DexKitBridge failed", it)
        }.getOrNull()
        PreferencesMonitor.initialize(application)
        PreferencesMonitor.listener = object : PreferencesMonitor.Listener {
            override fun onTranslationSelectedChanged(selected: Boolean) {
                provider?.player?.setDisplayTranslation(selected)
            }
        }

        DiskSongManager.initialize(application)
        initScreenStateMonitor()
        initProvider()
        startHooks()
    }

    private fun initProvider() {
        val helper =
            LyriconFactory.createProvider(
                context = application,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = application.packageName,
                logo = ProviderLogo.fromBase64(Constants.ICON)
            )
        val requester = LyricRequester(classLoader, application)

        PlaybackManager.init(
            remotePlayer = helper.player,
            requester = requester,
            application = application
        )

        helper.player.setDisplayTranslation(PreferencesMonitor.isTranslationSelected())
        helper.register()
        this.provider = helper
        this.lyricRequester = requester
    }

    private fun startHooks() {
        hookPlaybackItemLoadMethod()
        hookPlaybackItemConvertMethod()
        hookMediaMetadataChange()
        hookLyricBuildMethod()
        hookBridgeMediaSessionPlaybackState()
        runCatching {
            hookExoMediaPlayer()
        }.onFailure {
            YLog.error("hookExoMediaPlayer failed", it)
        }
    }

    private fun hookPlaybackItemLoadMethod() {
        runCatching {
            val viewModelClass =
                classLoader.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
            val playbackItemClass =
                classLoader.loadClass("com.apple.android.music.model.PlaybackItem")
            val method = findLoadLyricsMethod(viewModelClass, playbackItemClass)
                ?: error("loadLyrics method not found")
            lyricRequester?.setLoadLyricsMethod(method)

            viewModelClass.declaredConstructors.forEach { constructor ->
                constructor.isAccessible = true
                constructor.hook {
                    after {
                        lyricRequester?.setPlayerLyricsViewModel(instanceOrNull)
                    }
                }
            }

            method.hook {
                before {
                    lyricRequester?.setPlayerLyricsViewModel(instanceOrNull)
                    PlaybackManager.onPlaybackItemObserved(
                        playbackItem = args.getOrNull(0),
                        requestIfMissing = false
                    )
                }
            }
        }.onFailure {
            YLog.error("hookPlaybackItemLoadMethod failed", it)
        }
    }

    private fun hookPlaybackItemConvertMethod() {
        runCatching {
            val playbackItemClass = classLoader.loadClass("com.apple.android.music.model.PlaybackItem")
            val methods = findPlaybackItemMapperMethods(playbackItemClass)
            if (methods.isEmpty()) {
                error("playback item mapper method not found")
            }

            methods.forEach { method ->
                method.isAccessible = true
                method.hook {
                    after {
                        PlaybackManager.onPlaybackItemObserved(
                            playbackItem = this.result,
                            requestIfMissing = true
                        )
                    }
                }
            }
        }.onFailure {
            YLog.error("hookPlaybackItemConvertMethod failed", it)
        }
    }

    private fun hookMediaMetadataChange() {
        runCatching {
            val method = findMediaMetadataChangeMethod()
            if (method == null) {
                return@runCatching
            }

            method.hook {
                after {
                    val mediaMetadata = args[0] as? MediaMetadata ?: return@after
                    val metadata = MediaMetadataCache.putAndGet(mediaMetadata) ?: return@after
                    PlaybackManager.onSongChanged(metadata.id)
                }
            }
        }.onFailure {
            YLog.error("hookMediaMetadataChange failed", it)
        }
    }

    private fun hookLyricBuildMethod() {
        runCatching {
            val viewModelClass =
                classLoader.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
            val songInfoPtrClass =
                classLoader.loadClass("com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoPtr")
            val method = findLyricBuildMethod(viewModelClass, songInfoPtrClass)
                ?: error("buildTimeRangeToLyricsMap method not found")

            method.hook {
                after {
                    val songInfoPtr = args.getOrNull(0)
                    if (songInfoPtr == null) {
                        return@after
                    }

                    val songNative = XposedHelpers.callMethod(songInfoPtr, "get")
                    if (songNative == null) {
                        return@after
                    }

                    prepareSongInfoNativeForBridge(songNative)
                    PlaybackManager.onLyricsBuilt(songNative)
                }
            }
        }.onFailure {
            YLog.error("hookLyricBuildMethod failed", it)
        }
    }

    private fun hookBridgeMediaSessionPlaybackState() {
        runCatching {
            MediaSession::class.java
                .getDeclaredMethod("setPlaybackState", MediaSessionPlaybackState::class.java)
                .hook {
                    after {
                        val playbackState =
                            args.getOrNull(0) as? MediaSessionPlaybackState ?: return@after
                        PlaybackManager.onBridgeMediaSessionPlaybackStateChanged(playbackState)
                    }
                }
        }.onFailure {
            YLog.error("hookBridgeMediaSessionPlaybackState failed", it)
        }
    }

    private fun prepareSongInfoNativeForBridge(songNative: Any) {
        val language = runCatching {
            classLoader
                .loadClass("com.apple.android.music.playback.util.LocaleUtil")
                .getDeclaredMethod("getSystemLyricsLanguage")
                .invoke(null) as? String
        }.onFailure {
            YLog.error("resolve Apple lyrics language failed", it)
        }.getOrNull()

        if (language.isNullOrBlank()) {
            return
        }

        runCatching {
            XposedHelpers.callMethod(songNative, "setTranslation", language) as? Boolean
        }.onFailure {
            YLog.error("set Apple lyrics translation failed language=$language", it)
        }
    }

    private fun hookExoMediaPlayer() {
        val exoPlayerClass =
            classLoader.loadClass("com.apple.android.music.playback.player.ExoMediaPlayer")

        exoPlayerClass.declaredConstructors.forEach { constructor ->
            constructor.hook {
                after {
                    exoMediaPlayerInstance = instanceOrNull
                    getPositionMethod = instanceClass?.getDeclaredMethod("getCurrentPosition")
                }
            }
        }

        exoPlayerClass.resolve().firstMethod {
            name = "seekToPosition"
            parameters(Long::class)
        }.hook {
            after {
                val position = args(0).cast<Long>() ?: 0L
                if (isPlaying) provider?.player?.seekTo(position)
            }
        }

        classLoader.loadClass("com.apple.android.music.playback.controller.LocalMediaPlayerController")
            .resolve()
            .method {
                name = "onPlaybackStateChanged"
                parameters(VagueType, Int::class, Int::class)
            }.first().hook {
                after {
                    when (PlaybackState.of(args[2] as Int)) {
                        PlaybackState.PLAYING -> startSyncAction()
                        else -> stopSyncAction()
                    }
                }
            }
    }

    private fun startSyncAction() {
        if (isPlaying) return
        isPlaying = true
        provider?.player?.setPlaybackState(true)
        resumeCoroutineTask()
    }

    private fun stopSyncAction() {
        isPlaying = false
        provider?.player?.setPlaybackState(false)
        pauseCoroutineTask()
    }

    private fun resumeCoroutineTask() {
        if (progressJob?.isActive == true) return
        progressJob = coroutineScope.launch {
            while (isActive && isPlaying) {
                try {
                    val pos = getPositionMethod?.invoke(exoMediaPlayerInstance) as? Long ?: 0L
                    provider?.player?.setPosition(pos)
                } catch (_: Exception) {
                }
                delay(ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL)
            }
        }
    }

    private fun pauseCoroutineTask() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun initScreenStateMonitor() {
        ScreenStateMonitor.initialize(application)
        ScreenStateMonitor.addListener(object : ScreenStateMonitor.ScreenStateListener {
            override fun onScreenOn() {
                if (isPlaying) resumeCoroutineTask()
            }

            override fun onScreenOff() {
                pauseCoroutineTask()
            }

            override fun onScreenUnlocked() {
                if (isPlaying && progressJob == null) resumeCoroutineTask()
            }
        })
    }

    private fun findMediaMetadataChangeMethod() =
        "android.support.v4.media.MediaMetadataCompat".toClass()
            .declaredMethods.firstOrNull {
                Modifier.isPublic(it.modifiers)
                        && Modifier.isStatic(it.modifiers)
                        && it.parameterCount == 1
                        && it.returnType.simpleName.contains("MediaMetadata")
            }

    private fun findLoadLyricsMethod(
        viewModelClass: Class<*>,
        playbackItemClass: Class<*>
    ): Method? =
        viewModelClass.declaredMethods.firstOrNull { method ->
            method.name == "loadLyrics" &&
                    method.parameterCount == 1 &&
                    playbackItemClass.isAssignableFrom(method.parameterTypes[0])
        }?.apply { isAccessible = true }
            ?: viewModelClass.declaredMethods.firstOrNull { method ->
                method.parameterCount == 1 &&
                        playbackItemClass.isAssignableFrom(method.parameterTypes[0]) &&
                        method.returnType == Void.TYPE
            }?.apply { isAccessible = true }

    private fun findLyricBuildMethod(
        viewModelClass: Class<*>,
        songInfoPtrClass: Class<*>
    ): Method? =
        runCatching {
            viewModelClass.getDeclaredMethod("buildTimeRangeToLyricsMap", songInfoPtrClass)
        }.getOrNull()?.apply { isAccessible = true }
            ?: viewModelClass.declaredMethods.firstOrNull { method ->
                !Modifier.isStatic(method.modifiers) &&
                        method.parameterCount == 1 &&
                        method.parameterTypes[0] == songInfoPtrClass &&
                        method.returnType == Void.TYPE
            }?.apply { isAccessible = true }

    private fun findPlaybackItemMapperMethods(playbackItemClass: Class<*>): List<Method> {
        val candidates = sequence {
            yield("com.apple.android.music.player.N")
            yield("com.apple.android.music.player.M")
            findPlaybackItemMapperClassesByDexKit().forEach { yield(it.name) }
        }.distinct()

        return candidates
            .mapNotNull { className ->
                runCatching { classLoader.loadClass(className) }
                    .getOrNull()
            }
            .flatMap { mapperClass -> mapperClass.playbackItemMapperMethods(playbackItemClass) }
            .distinctBy { "${it.declaringClass.name}#${it.name}${it.parameterTypes.joinToString()}" }
            .onEach { it.isAccessible = true }
            .toList()
    }

    private fun findPlaybackItemMapperClassesByDexKit(): List<Class<*>> {
        val bridge = dexKitBridge ?: return emptyList()
        return runCatching {
            bridge.findClass {
                searchPackages("com.apple.android.music.player")
                matcher {
                    usingStrings(
                        APPLE_METADATA_KEY_MEDIA_ID,
                        APPLE_METADATA_KEY_PLAYBACK_ENDPOINT_TYPE
                    )
                }
            }.mapNotNull { classData ->
                runCatching { classData.getInstance(classLoader) }
                    .getOrNull()
            }
        }.onFailure {
            YLog.error("Apple DexKit mapper search failed", it)
        }.getOrDefault(emptyList())
    }

    private fun Class<*>.playbackItemMapperMethods(playbackItemClass: Class<*>): List<Method> =
        declaredMethods.filter { method ->
            Modifier.isStatic(method.modifiers) &&
                    playbackItemClass.isAssignableFrom(method.returnType) &&
                    method.parameterCount == 1
        }
}
