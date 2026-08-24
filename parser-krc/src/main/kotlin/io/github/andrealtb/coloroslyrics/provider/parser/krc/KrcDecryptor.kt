/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.parser.krc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream

object KrcDecryptor {

    private val DECRYPT_KEY = byteArrayOf(
        64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45,
        206.toByte(), 210.toByte(), 110, 105
    )

    private const val OFFSET = 4

    @JvmStatic
    fun decrypt(input: ByteArray): String? {
        if (input.size <= OFFSET) {
            return null
        }

        return runCatching {
            val decryptedLength = input.size - OFFSET
            val decryptedBytes = ByteArray(decryptedLength)

            for (i in 0 until decryptedLength) {
                val key = DECRYPT_KEY[i % DECRYPT_KEY.size].toInt()
                val encryptedByte = input[i + OFFSET].toInt()
                decryptedBytes[i] = (encryptedByte xor key).toByte()
            }

            decompressAndToString(decryptedBytes)
        }.getOrNull()
    }

    private fun decompressAndToString(compressedData: ByteArray): String {
        return ByteArrayInputStream(compressedData).use { bais ->
            InflaterInputStream(bais).use { inflaterStream ->
                ByteArrayOutputStream(compressedData.size * 2).use { baos ->
                    val buffer = ByteArray(2048)
                    var len: Int
                    while (inflaterStream.read(buffer).also { len = it } != -1) {
                        baos.write(buffer, 0, len)
                    }
                    baos.toString(StandardCharsets.UTF_8.name())
                }
            }
        }
    }
}
