/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.kgprovider.xposed.kugou;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class KuGouMetadataIdentityPolicyTest {

    @Test
    public void legitimateHyphenatedTitleWithArtistIsNotCarLyricMetadata() {
        MetadataData metadata = metadata(
                "BATTER UP (Remix) - Bonus Track",
                "BABYMONSTER");

        assertFalse(KuGouMetadataIdentityPolicy.INSTANCE
                .looksLikeCarLyricDisplayMetadata(metadata, null));
    }

    @Test
    public void titleContainingArtistIsCarLyricDisplayMetadata() {
        MetadataData metadata = metadata(
                "BATTER UP (Remix) - Bonus Track - BABYMONSTER",
                "BABYMONSTER");

        assertTrue(KuGouMetadataIdentityPolicy.INSTANCE
                .looksLikeCarLyricDisplayMetadata(metadata, null));
    }

    @Test
    public void decoratedCurrentTitleIsCarLyricDisplayMetadata() {
        MetadataData current = metadata(
                "BATTER UP (Remix) - Bonus Track",
                "BABYMONSTER");
        MetadataData incoming = metadata(
                "BATTER UP (Remix) - Bonus Track / live lyrics",
                "");

        assertTrue(KuGouMetadataIdentityPolicy.INSTANCE
                .looksLikeCarLyricDisplayMetadata(incoming, current));
    }

    @Test
    public void derivesRealIdentityFromChurnedCarLyricMetadata() {
        // "演唱: X" display prefix with "Artist Title" composite artist
        KuGouMetadataIdentityPolicy.CarLyricDerivedIdentity chinese =
                KuGouMetadataIdentityPolicy.INSTANCE.carLyricDerivedIdentity(
                        "演唱: 马健涛",
                        "对角音乐 逐梦滚烫");
        assertNotNull(chinese);
        assertEquals("逐梦滚烫", chinese.getRealTitle());
        assertEquals("对角音乐", chinese.getRealArtist());

        // Long lyric line title with dash-joined "Artist-Title" composite artist
        KuGouMetadataIdentityPolicy.CarLyricDerivedIdentity english =
                KuGouMetadataIdentityPolicy.INSTANCE.carLyricDerivedIdentity(
                        "These days I've been looking back on the lives we've had",
                        "Troye Sivan-She’s the Best (Explicit)");
        assertNotNull(english);
        assertEquals("She’s the Best (Explicit)", english.getRealTitle());
        assertEquals("Troye Sivan", english.getRealArtist());

        // CJK lyric line with space-joined composite artist
        KuGouMetadataIdentityPolicy.CarLyricDerivedIdentity cjk =
                KuGouMetadataIdentityPolicy.INSTANCE.carLyricDerivedIdentity(
                        "还是自娱自笑的消遣",
                        "莫文蔚 盛夏的果实");
        assertNotNull(cjk);
        assertEquals("盛夏的果实", cjk.getRealTitle());
        assertEquals("莫文蔚", cjk.getRealArtist());
    }

    @Test
    public void keepsStableMetadataUntouched() {
        assertNull(KuGouMetadataIdentityPolicy.INSTANCE.carLyricDerivedIdentity(
                "Good Times", "Lukas Graham"));
        assertNull(KuGouMetadataIdentityPolicy.INSTANCE.carLyricDerivedIdentity(
                "She's the Best (Explicit)",
                "Troye Sivan-She’s the Best (Explicit)"));
        assertNull(KuGouMetadataIdentityPolicy.INSTANCE.carLyricDerivedIdentity(
                "像我这样爱你的人", "马健涛"));
        assertNull(KuGouMetadataIdentityPolicy.INSTANCE.carLyricDerivedIdentity(
                "逐梦滚烫", "对角音乐 逐梦滚烫"));
        assertNull(KuGouMetadataIdentityPolicy.INSTANCE.carLyricDerivedIdentity(
                "明天过后", "张杰"));
    }

    private static MetadataData metadata(String title, String artist) {
        return new MetadataData(
                title,
                artist,
                "",
                226_000L,
                "",
                "");
    }
}
