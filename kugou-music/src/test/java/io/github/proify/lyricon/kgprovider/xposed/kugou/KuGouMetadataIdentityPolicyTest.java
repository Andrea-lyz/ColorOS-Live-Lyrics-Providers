/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.kgprovider.xposed.kugou;

import static org.junit.Assert.assertFalse;
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
