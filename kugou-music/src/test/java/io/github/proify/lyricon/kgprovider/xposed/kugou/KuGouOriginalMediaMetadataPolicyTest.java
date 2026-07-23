/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 */

package io.github.proify.lyricon.kgprovider.xposed.kugou;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class KuGouOriginalMediaMetadataPolicyTest {

    @Test
    public void alternateLanguageTitleWithSharedAlbumArtistAndDurationIsTrackChange() {
        MetadataData current = metadata("Catch Catch (Chinese Ver.)", "YENA", "GOOD MORNING", 180_000L);
        MetadataData incoming = metadata("캐치 캐치", "YENA", "GOOD MORNING", 180_000L);

        assertFalse(KuGouOriginalMediaMetadataPolicy.INSTANCE.shouldSuppressCarLyricMetadata(
                current, incoming, List.of("Catch my heart", "Ca-ca-catch my heart")));
    }

    @Test
    public void knownCurrentLyricLineIsSuppressed() {
        MetadataData current = metadata("Catch Catch (Chinese Ver.)", "YENA", "GOOD MORNING", 180_000L);
        MetadataData incoming = metadata("Catch my heart", "YENA", "GOOD MORNING", 180_000L);

        assertTrue(KuGouOriginalMediaMetadataPolicy.INSTANCE.shouldSuppressCarLyricMetadata(
                current, incoming, List.of("Catch my heart", "Ca-ca-catch my heart")));
    }

    @Test
    public void titleArtistCarProjectionIsSuppressed() {
        MetadataData current = metadata("Catch Catch (Chinese Ver.)", "YENA", "GOOD MORNING", 180_000L);
        MetadataData incoming = metadata(
                "Catch Catch (Chinese Ver.) - YENA", "YENA", "GOOD MORNING", 180_000L);

        assertTrue(KuGouOriginalMediaMetadataPolicy.INSTANCE.shouldSuppressCarLyricMetadata(
                current, incoming, List.of()));
    }

    @Test
    public void matchingExplicitMediaIdStillSuppressesTransientTitle() {
        MetadataData current = metadata("Catch Catch (Chinese Ver.)", "YENA", "GOOD MORNING", 180_000L, "song-42");
        MetadataData incoming = metadata("Catch my heart", "YENA", "GOOD MORNING", 180_000L, "song-42");

        assertTrue(KuGouOriginalMediaMetadataPolicy.INSTANCE.shouldSuppressCarLyricMetadata(
                current, incoming, List.of()));
    }

    private static MetadataData metadata(String title, String artist, String album, long duration) {
        return metadata(title, artist, album, duration, "");
    }

    private static MetadataData metadata(
            String title,
            String artist,
            String album,
            long duration,
            String mediaId) {
        return new MetadataData(title, artist, album, duration, mediaId, "");
    }
}
