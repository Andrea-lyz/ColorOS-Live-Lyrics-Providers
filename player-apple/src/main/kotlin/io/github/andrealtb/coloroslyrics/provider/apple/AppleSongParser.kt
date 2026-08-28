/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

object AppleSongParser {
    fun parse(songNative: Any): AppleSongModel? {
        val adamId = AppleNativeCalls.call(songNative, "getAdamId")?.toString()
            ?.takeIf { it.isNotBlank() && it != "0" }
            ?: return null
        val duration = AppleNativeCalls.callInt(songNative, "getDuration")
        val sections = AppleNativeCalls.call(songNative, "getSections")
        val lyrics = if (sections != null) parseSectionVector(sections) else emptyList()
        return AppleSongModel(
            adamId = adamId,
            durationMs = duration.toLong(),
            lyrics = lyrics
        )
    }

    fun applySystemTranslation(songNative: Any, language: String?): Boolean {
        if (language.isNullOrBlank()) return false
        return AppleNativeCalls.callBoolean(songNative, "setTranslation", language) == true
    }

    private fun parseSectionVector(vector: Any): List<AppleLyricLineModel> {
        val size = AppleNativeCalls.vectorSize(vector)
        val lines = mutableListOf<AppleLyricLineModel>()
        var index = 0L
        while (index < size) {
            val sectionNative = AppleNativeCalls.unwrapPtr(AppleNativeCalls.vectorItem(vector, index))
            if (sectionNative != null) {
                AppleNativeCalls.call(sectionNative, "getLines")?.let { lineVector ->
                    lines += parseLineVector(lineVector)
                }
            }
            index++
        }
        return lines
    }

    private fun parseLineVector(vector: Any): List<AppleLyricLineModel> {
        val size = AppleNativeCalls.vectorSize(vector)
        val lines = mutableListOf<AppleLyricLineModel>()
        var index = 0L
        while (index < size) {
            val lineNative = AppleNativeCalls.unwrapPtr(AppleNativeCalls.vectorItem(vector, index))
            if (lineNative != null) {
                lines += parseLine(lineNative)
            }
            index++
        }
        return lines
    }

    private fun parseLine(lineNative: Any): AppleLyricLineModel {
        val words = AppleNativeCalls.call(lineNative, "getWords")?.let(::parseWordVector).orEmpty()
        val backgroundWords = parseBackgroundWords(lineNative)
        return AppleLyricLineModel(
            begin = AppleNativeCalls.callInt(lineNative, "getBegin"),
            end = AppleNativeCalls.callInt(lineNative, "getEnd"),
            duration = AppleNativeCalls.callInt(lineNative, "getDuration"),
            htmlLineText = AppleNativeCalls.callString(lineNative, "getHtmlLineText"),
            htmlTranslationLineText =
                AppleNativeCalls.callString(lineNative, "getHtmlTranslationLineText"),
            htmlBackgroundVocalsLineText =
                AppleNativeCalls.callString(lineNative, "getHtmlBackgroundVocalsLineText"),
            htmlPronunciationLineText =
                AppleNativeCalls.callString(lineNative, "getHtmlPronunciationLineText"),
            words = words,
            backgroundWords = backgroundWords
        )
    }

    private fun parseBackgroundWords(lineNative: Any): List<AppleLyricWordModel> {
        val withFlag = AppleNativeCalls.call(lineNative, "getBackgroundWords", false)
        val vector = withFlag ?: AppleNativeCalls.call(lineNative, "getBackgroundWords")
        return vector?.let(::parseWordVector).orEmpty()
    }

    private fun parseWordVector(vector: Any): List<AppleLyricWordModel> {
        val size = AppleNativeCalls.vectorSize(vector)
        val words = mutableListOf<AppleLyricWordModel>()
        var index = 0L
        while (index < size) {
            val wordNative = AppleNativeCalls.unwrapPtr(AppleNativeCalls.vectorItem(vector, index))
            if (wordNative != null) {
                words += AppleLyricWordModel(
                    begin = AppleNativeCalls.callInt(wordNative, "getBegin"),
                    end = AppleNativeCalls.callInt(wordNative, "getEnd"),
                    duration = AppleNativeCalls.callInt(wordNative, "getDuration"),
                    text = AppleNativeCalls.callString(wordNative, "getHtmlLineText"),
                    whitespace = AppleNativeCalls.callBoolean(wordNative, "isWhitespace") == true
                )
            }
            index++
        }
        return words
    }
}
