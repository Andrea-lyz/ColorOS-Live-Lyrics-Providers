/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lxprovider.xposed.variant.main;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LxMusicBridgeLyricsTest {
    @Test
    public void enhancedLrcPreservesRawWordTimingAndBuildsPlainFallback() {
        String raw = "[00:01.000]<00:01.000>Ni<00:01.400>hao<00:02.000>\n"
                + "[00:03.000]<00:03.000>Hello <00:03.500>world<00:04.000>";
        String translation = "[00:01.000]Hello\n[00:03.000]Welcome";

        LxMusicBridgeLyrics lyrics = LxMusicBridgeLyrics.Companion.from(raw, translation);

        assertNotNull(lyrics);
        assertEquals(raw, lyrics.getRawLyric());
        assertEquals("[00:01.000]Nihao\n[00:03.000]Hello world", lyrics.getLyric());
        assertEquals(translation, lyrics.getTranslationLyric());
    }

    @Test
    public void normalLrcStaysLineTimedWithoutSyntheticWordTiming() {
        String raw = "[00:01.000]First line\n[00:03.000]Second line";

        LxMusicBridgeLyrics lyrics = LxMusicBridgeLyrics.Companion.from(raw, "");

        assertNotNull(lyrics);
        assertEquals(raw, lyrics.getRawLyric());
        assertEquals(raw, lyrics.getLyric());
        assertFalse(lyrics.getRawLyric().contains("<"));
        assertEquals("", lyrics.getTranslationLyric());
    }

    @Test
    public void untimedTextIsNotPublishedAsBridgeLyrics() {
        assertNull(LxMusicBridgeLyrics.Companion.from("plain lyric", "[00:01.000]translation"));
    }

    @Test
    public void lyricsBoundToPreviousTrackAreNotRepublishedForNewMetadata() {
        assertFalse(LxMusicBridgeLyrics.matchesTrackIdentity("id:previous", "id:next"));
        assertTrue(LxMusicBridgeLyrics.matchesTrackIdentity("id:current", "id:current"));
        assertTrue(LxMusicBridgeLyrics.matchesTrackIdentity("", "id:initial"));
    }
}
