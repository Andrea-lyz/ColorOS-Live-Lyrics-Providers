/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.netease

/** Explicit payload contract; capture labels must never choose the JSON shape. */
enum class NeteasePayloadMode(val source: String) {
    OFFICIAL_APPEND("netease-official-append"),
    CONSTRUCTED("netease-constructed")
}
