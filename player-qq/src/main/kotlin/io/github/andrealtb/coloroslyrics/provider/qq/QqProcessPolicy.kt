/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qq

object QqProcessPolicy {
    fun shouldHook(hostPackage: String, processName: String): Boolean {
        if (hostPackage != QqPlayerConstants.HOST_PACKAGE) return false
        return processName == hostPackage + QqPlayerConstants.PLAYER_PROCESS_SUFFIX ||
            processName.endsWith(QqPlayerConstants.PLAYER_PROCESS_SUFFIX)
    }
}
