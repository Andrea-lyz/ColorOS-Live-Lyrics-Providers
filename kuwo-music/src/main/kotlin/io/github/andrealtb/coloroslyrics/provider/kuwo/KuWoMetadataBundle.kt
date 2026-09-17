/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kuwo

import android.media.MediaMetadata
import android.os.Bundle
import io.github.andrealtb.coloroslyrics.provider.reflection.CandidateResolver
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Reflective access to the value bundle a host [MediaMetadata] already carries.
 *
 * The lyricInfo overlay must not rebuild the metadata object. ColorOS derives the lockscreen cover
 * from the bitmap lanes of exactly the metadata the host published
 * (com.oplus.systemui.media.controls.pipeline.OplusMediaDataManagerExImpl tries loadBitmapFromUri,
 * then METADATA_KEY_ART, then METADATA_KEY_ALBUM_ART, and KuWo publishes ALBUM_ART), so writing one
 * key into the host bundle is the only change this Provider is allowed to make.
 *
 * The field is private (AOSP name "mBundle"). It is resolved once, preferring that name and falling
 * back to a unique non-static Bundle field, then cached. When it cannot be resolved the overlay
 * fails open and logs LYRIC_INFO_APPEND_UNSUPPORTED instead of rebuilding metadata.
 */
internal object KuWoMetadataBundle {
    private const val BUNDLE_FIELD_NAME = "mBundle"

    @Volatile
    private var bundleField: Field? = null

    @Volatile
    private var resolutionAttempted = false

    fun bundleOf(metadata: MediaMetadata): Bundle? {
        val field = resolve() ?: return null
        return runCatching { field.get(metadata) as? Bundle }.getOrNull()
    }

    private fun resolve(): Field? {
        bundleField?.let { return it }
        if (resolutionAttempted) return null
        synchronized(this) {
            bundleField?.let { return it }
            if (resolutionAttempted) return null
            val resolved = runCatching { resolveBundleField() }.getOrNull()
            if (resolved != null) {
                bundleField = resolved
            }
            resolutionAttempted = true
            return resolved
        }
    }

    private fun resolveBundleField(): Field? {
        val candidates = classHierarchy(MediaMetadata::class.java)
            .flatMap { it.declaredFields.asList() }
            .filter { !Modifier.isStatic(it.modifiers) && it.type == Bundle::class.java }
        if (candidates.isEmpty()) return null
        val field = candidates.firstOrNull { it.name == BUNDLE_FIELD_NAME }
            ?: CandidateResolver.resolveUniqueField(
                candidates = candidates,
                targetName = "MediaMetadata bundle field",
                searchCriteria = "single non-static android.os.Bundle field"
            )
        field.isAccessible = true
        return field
    }

    private fun classHierarchy(type: Class<*>): List<Class<*>> {
        val hierarchy = ArrayList<Class<*>>(4)
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            hierarchy.add(current)
            current = current.superclass
        }
        return hierarchy
    }
}
