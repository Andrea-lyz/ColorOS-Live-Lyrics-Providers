/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.poweramp

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

object PowerampSafUriResolver {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun resolveToUri(context: Context, standardDocumentId: String): Uri? {
        if (standardDocumentId.isBlank() || !standardDocumentId.contains(":")) return null
        val inputVolume = standardDocumentId.substringBefore(":")
        for (permission in context.contentResolver.persistedUriPermissions) {
            if (!permission.isReadPermission) continue
            val treeUri = permission.uri
            if (EXTERNAL_STORAGE_AUTHORITY != treeUri.authority) continue
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri) ?: continue
            val treeVolume = treeDocumentId.substringBefore(":")
            if (inputVolume.equals(treeVolume, ignoreCase = true) &&
                standardDocumentId.startsWith(treeDocumentId)
            ) {
                return DocumentsContract.buildDocumentUriUsingTree(treeUri, standardDocumentId)
            }
        }
        return null
    }
}
