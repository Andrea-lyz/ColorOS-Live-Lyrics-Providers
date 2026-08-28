/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

object QishuiTranslationActionPolicy {
    fun shouldExpose(
        currentGeneration: Long,
        translationGeneration: Long,
        translationCount: Int
    ): Boolean = currentGeneration > 0L &&
        currentGeneration == translationGeneration &&
        translationCount > 0
}
