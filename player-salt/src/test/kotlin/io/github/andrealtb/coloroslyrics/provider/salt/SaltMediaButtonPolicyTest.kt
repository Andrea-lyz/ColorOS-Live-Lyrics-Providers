package io.github.andrealtb.coloroslyrics.provider.salt

import org.junit.After
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SaltMediaButtonPolicyTest {
    @After fun restoreClock() = SaltMediaButtonPolicy.resetForTesting()

    @Test fun injectedClockVerifiesDebounceWithoutSleeping() {
        var now = 1L
        SaltMediaButtonPolicy.resetForTesting { now }
        assertTrue(SaltMediaButtonPolicy.shouldAcceptMediaButtonStart())
        assertFalse(SaltMediaButtonPolicy.shouldAcceptMediaButtonStart())
        now += SaltPlayerConstants.MEDIA_BUTTON_DEBOUNCE_MS * 1_000_000L
        assertTrue(SaltMediaButtonPolicy.shouldAcceptMediaButtonStart())
    }

    @Test fun migratedActionConstantsMatchBridge() {
        assertTrue(SaltPlayerConstants.ACTION_PLAY_OR_PAUSE == "com.salt.music.play_or_pause")
        assertTrue(SaltPlayerConstants.ACTION_DESKTOP_LYRICS == "com.salt.music.desktop_lyrics")
    }
}
