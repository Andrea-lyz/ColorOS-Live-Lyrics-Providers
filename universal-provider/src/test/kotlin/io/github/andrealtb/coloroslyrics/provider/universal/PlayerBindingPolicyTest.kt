package io.github.andrealtb.coloroslyrics.provider.universal

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerBindingPolicyTest {
    @Test
    fun blocksOfficialAndSpecialPlayers() {
        assertTrue(PlayerBindingPolicy.isBlocked("com.tencent.qqmusic"))
        assertTrue(PlayerBindingPolicy.isBlocked("com.kugou.android.lite"))
        assertTrue(PlayerBindingPolicy.isBlocked("cn.kuwo.player"))
        assertEquals("禁用（已有官方 Provider）", PlayerBindingPolicy.blockReason("com.heytap.music"))
        assertEquals("禁用（需专项适配）", PlayerBindingPolicy.blockReason("cn.kuwo.player"))
    }

    @Test
    fun sanitizeRemovesBlockedKeepOthers() {
        val cleaned = PlayerBindingPolicy.sanitize(
            setOf("com.salt.music", "com.netease.cloudmusic", "cn.kuwo.player", "com.spotify.music")
        )
        assertEquals(setOf("com.salt.music", "com.spotify.music"), cleaned)
    }
}

