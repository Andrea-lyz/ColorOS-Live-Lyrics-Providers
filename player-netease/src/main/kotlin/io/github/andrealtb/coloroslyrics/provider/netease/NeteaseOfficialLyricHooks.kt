/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

import android.os.Handler
import android.os.Message
import io.github.andrealtb.coloroslyrics.provider.hook102.ProviderHookRuntime
import java.lang.reflect.Method

/**
 * Live native-append hooks only: official writer/encoder, structural track
 * binding, and the filtered framework dispatch boundary. The ThreadLocal is
 * valid only during the synchronous writer -> encoder call stack.
 */
class NeteaseOfficialLyricHooks(
    private val hostPackage: String,
    private val processName: String,
    private val apkPath: String,
    private val classLoader: ClassLoader,
    private val hookRuntime: ProviderHookRuntime,
    private val coordinator: NeteaseLyricSessionCoordinator,
    private val onRuntimeEntry: () -> Unit
) {
    private val pendingEncode = ThreadLocal<NeteasePublication>()

    fun install() {
        val lyricWrite = resolveLyricWrite() ?: return
        hookLyricWrite(lyricWrite)
        hookOfficialEncoder()
        hookTrackBind(lyricWrite.declaringClass)
        hookLyricMessageDispatch(lyricWrite.declaringClass)
    }

    private fun resolveLyricWrite(): Method? {
        val method = runCatching {
            NeteaseLyricHookResolver.resolveLyricWriteMethod(apkPath, classLoader)
        }.onFailure {
            NeteaseDiagnostics.error(
                area = "hook",
                event = "LYRIC_WRITE_RESOLVE_FAILED",
                process = processName,
                message = it.message,
                throwable = it
            )
        }.getOrNull()
        if (method == null) {
            NeteaseDiagnostics.error(
                area = "hook",
                event = "LYRIC_WRITE_MISSING",
                process = processName
            )
        }
        return method
    }

    private fun hookLyricWrite(method: Method) {
        hookRuntime.hook(
            method,
            "netease.writer.${method.declaringClass.name}#${method.name}"
        ) {
            before {
                pendingEncode.remove()
                runCatching {
                    val publication = coordinator.captureOfficial(
                        snapshot = NeteaseLyricInfoReader.read(
                            args.getOrNull(0),
                            args.getOrNull(1)
                        ),
                        musicInfoPresent = args.getOrNull(1) != null,
                        captureOrigin = "lyric-info"
                    ) ?: return@runCatching
                    pendingEncode.set(publication)
                    NeteaseDiagnostics.info(
                        area = "lyric",
                        event = "PENDING_ENCODE_SET",
                        process = processName,
                        generation = publication.generation,
                        session = publication.track.id,
                        message = "lines=${publication.lines.size} " +
                            "translated=${publication.lines.count { !it.secondary.isNullOrBlank() }} " +
                            "source=${publication.captureOrigin} ${threadNote()}"
                    )
                }.onFailure { error ->
                    NeteaseDiagnostics.error(
                        area = "lyric",
                        event = "LYRIC_WRITE_HOOK_FAILED",
                        process = processName,
                        message = error.message,
                        throwable = error
                    )
                }
            }

            after {
                val pending = pendingEncode.get()
                NeteaseDiagnostics.info(
                    area = "lyric",
                    event = "PENDING_ENCODE_CLEARED",
                    process = processName,
                    generation = pending?.generation,
                    session = pending?.track?.id,
                    reason = "after-lyric-write",
                    message = threadNote()
                )
                pendingEncode.remove()
            }
        }
        logHooked("LYRIC_WRITE_HOOKED", method.declaringClass.name + "#" + method.name)
    }

    private fun hookOfficialEncoder() {
        val method = runCatching {
            NeteaseLyricHookResolver.resolveOfficialEncoder(apkPath, classLoader)
        }.onFailure {
            NeteaseDiagnostics.error(
                area = "hook",
                event = "ENCODER_RESOLVE_FAILED",
                process = processName,
                message = it.message,
                throwable = it
            )
        }.getOrNull()
        if (method == null) {
            NeteaseDiagnostics.error(
                area = "hook",
                event = "ENCODER_MISSING",
                process = processName
            )
            return
        }
        hookRuntime.hook(
            method,
            "netease.encoder.${method.declaringClass.name}#${method.name}"
        ) {
            after {
                runCatching {
                    val publication = pendingEncode.get()
                    if (publication == null) {
                        NeteaseDiagnostics.info(
                            area = "publisher",
                            event = "ENCODER_SKIPPED",
                            process = processName,
                            session = "no-pending",
                            reason = "no-pending",
                            message = "hostChars=${(result as? String)?.length ?: 0} " +
                                threadNote()
                        )
                        return@runCatching
                    }
                    val existing = result as? String
                    val encoded = NeteaseLyricInfoPayloadEncoder.encode(
                        track = publication.track,
                        lines = publication.lines,
                        trackGeneration = publication.generation,
                        hostPackage = hostPackage,
                        existingLyricInfo = existing,
                        mode = publication.payloadMode
                    )
                    if (encoded == null) {
                        NeteaseDiagnostics.info(
                            area = "publisher",
                            event = "ENCODER_SKIPPED",
                            process = processName,
                            generation = publication.generation,
                            session = publication.track.id,
                            reason = "encode-null",
                            message = "hostChars=${existing?.length ?: 0} " +
                                "lines=${publication.lines.size} ${threadNote()}"
                        )
                        return@runCatching
                    }
                    result = encoded.value
                    NeteaseDiagnostics.info(
                        area = "publisher",
                        event = "NATIVE_LYRICINFO_PATCHED",
                        process = processName,
                        generation = publication.generation,
                        session = publication.track.id,
                        payloadChars = encoded.value.length,
                        message = "source=encoder rawChars=${encoded.rawLyric.length} " +
                            "translationChars=${encoded.translationLyric.length} " +
                            "officialLyricRepair=${encoded.repairedOfficialLyric} ${threadNote()}"
                    )
                }.onFailure { error ->
                    NeteaseDiagnostics.error(
                        area = "publisher",
                        event = "ENCODER_HOOK_FAILED",
                        process = processName,
                        message = error.message,
                        throwable = error
                    )
                }
            }
        }
        logHooked("ENCODER_HOOKED", method.declaringClass.name + "#" + method.name)
    }

    private fun hookTrackBind(handlerClass: Class<*>) {
        val methods = NeteaseLyricHookResolver.resolveTrackBindMethods(handlerClass)
        if (methods.isEmpty()) {
            NeteaseDiagnostics.info(
                area = "hook",
                event = "TRACK_BIND_MISSING",
                process = processName,
                session = handlerClass.name
            )
            return
        }
        methods.forEach { method ->
            hookRuntime.hook(
                method,
                "netease.track.${method.declaringClass.name}#${method.name}"
            ) {
                before {
                    runCatching {
                        val musicInfo = args.getOrNull(0) ?: return@runCatching
                        coordinator.bindTrack(
                            NeteaseLyricInfoReader.read(null, musicInfo).track,
                            "track-bind"
                        )
                    }.onFailure { error ->
                        NeteaseDiagnostics.error(
                            area = "identity",
                            event = "TRACK_BIND_HOOK_FAILED",
                            process = processName,
                            message = error.message,
                            throwable = error
                        )
                    }
                }
            }
            logHooked("TRACK_BIND_HOOKED", method.declaringClass.name + "#" + method.name)
        }
    }

    /** Install once per official process; reject unrelated Handler traffic immediately. */
    private fun hookLyricMessageDispatch(handlerClass: Class<*>) {
        val expectedHandlerName = handlerClass.name
        val resolvedMusicAccessor =
            NeteaseLyricHookResolver.resolveCurrentMusicAccessor(handlerClass)
        val dispatch = Handler::class.java.getDeclaredMethod(
            "dispatchMessage",
            Message::class.java
        )
        hookRuntime.hook(dispatch, "netease.dispatch.Handler#dispatchMessage") {
            after {
                val message = args.getOrNull(0) as? Message ?: return@after
                val handler = instanceOrNull as? Handler ?: return@after
                val lyric = message.obj ?: return@after
                if (!NeteaseLyricHookResolver.matchesLyricDispatch(
                        expectedHandlerClassName = expectedHandlerName,
                        actualHandlerClassName = handler.javaClass.name,
                        what = message.what,
                        payloadTypeName = lyric.javaClass.name
                    )
                ) {
                    return@after
                }
                runCatching {
                    onRuntimeEntry()
                    pendingEncode.remove()
                    val musicAccessor = resolvedMusicAccessor
                        ?.takeIf { it.declaringClass.isInstance(handler) }
                        ?: NeteaseLyricHookResolver.resolveCurrentMusicAccessor(handler.javaClass)
                    val musicInfo = musicAccessor?.let { method ->
                        runCatching { method.invoke(handler) }
                            .onFailure { error ->
                                NeteaseDiagnostics.info(
                                    area = "lyric",
                                    event = "CURRENT_MUSIC_READ_FAILED",
                                    process = processName,
                                    session = handler.javaClass.name,
                                    reason = method.name,
                                    message = "${error.message} ${threadNote()}"
                                )
                            }.getOrNull()
                    }
                    NeteaseDiagnostics.info(
                        area = "lyric",
                        event = "LYRIC_MESSAGE_SEEN",
                        process = processName,
                        session = handler.javaClass.name + "#what" + message.what,
                        reason = musicAccessor?.name ?: "no-music-accessor",
                        message = "lyric=${lyric.javaClass.name} ${threadNote()}"
                    )
                    val publication = coordinator.captureOfficial(
                        snapshot = NeteaseLyricInfoReader.read(lyric, musicInfo),
                        musicInfoPresent = musicInfo != null,
                        captureOrigin = "handler-message"
                    )
                    if (publication != null) {
                        NeteaseDiagnostics.info(
                            area = "lyric",
                            event = "PENDING_ENCODE_SKIPPED",
                            process = processName,
                            generation = publication.generation,
                            session = publication.track.id,
                            reason = "post-dispatch-replay",
                            message = "source=handler-message lines=${publication.lines.size} " +
                                threadNote()
                        )
                    }
                }.onFailure { error ->
                    NeteaseDiagnostics.error(
                        area = "lyric",
                        event = "LYRIC_MESSAGE_HOOK_FAILED",
                        process = processName,
                        message = error.message,
                        throwable = error
                    )
                }
            }
        }
        logHooked(
            event = "LYRIC_MESSAGE_HOOKED",
            target = "android.os.Handler#dispatchMessage",
            extra = "handler=$expectedHandlerName what=${NeteasePlayerConstants.LYRIC_HANDLER_WHAT} " +
                "music=${resolvedMusicAccessor?.name ?: "missing"}"
        )
    }

    private fun logHooked(event: String, target: String, extra: String? = null) {
        NeteaseDiagnostics.info(
            area = "hook",
            event = event,
            process = processName,
            session = target,
            message = if (extra.isNullOrBlank()) target else "$target $extra"
        )
    }

    private fun threadNote(): String = "tid=" + android.os.Process.myTid()
}
