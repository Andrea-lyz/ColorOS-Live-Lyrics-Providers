/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 */

package io.github.proify.lyricon.lxprovider.xposed.variant.main;

import org.junit.Test;

import io.github.proify.lyricon.lxprovider.xposed.Metadata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LxMusicBluetoothLyricMetadataPolicyTest {
    @Test
    public void recognizesMainLxBluetoothLyricProjection() {
        Metadata stable = new Metadata("", "Style", "Taylor Swift", 231_000L);
        Metadata projection = new Metadata(
                "",
                "Midnight, you come and pick me up",
                "Style - Taylor Swift",
                231_000L);

        assertTrue(LxMusicBluetoothLyricMetadataPolicy.isBluetoothLyricProjection(
                stable, projection, true));
    }

    @Test
    public void recognizesProjectionWhenTrackPlayerPublishesAnEphemeralMediaId() {
        Metadata stable = new Metadata("stable-id", "Style", "Taylor Swift", 231_000L);
        Metadata projection = new Metadata(
                "projection-id",
                "Midnight, you come and pick me up",
                "Style - Taylor Swift",
                231_000L);

        assertTrue(LxMusicBluetoothLyricMetadataPolicy.isBluetoothLyricProjection(
                stable, projection, true));
    }

    @Test
    public void recognizesWalnutProjectionWhenTheStableArtistIncludesAlbum() {
        Metadata stable = new Metadata("", "Style", "Taylor Swift - 1989", 231_000L);
        Metadata projection = new Metadata(
                "",
                "You got that James Dean daydream look in your eye",
                "Style - Taylor Swift",
                231_000L);

        assertTrue(LxMusicBluetoothLyricMetadataPolicy.isBluetoothLyricProjection(
                stable, projection, true));
    }

    @Test
    public void recognizesProjectionForAnArtistlessTrack() {
        Metadata stable = new Metadata("", "Instrumental", "", 180_000L);
        Metadata projection = new Metadata("", "A lyric line", "Instrumental", 180_000L);

        assertTrue(LxMusicBluetoothLyricMetadataPolicy.isBluetoothLyricProjection(
                stable, projection, true));
    }

    @Test
    public void recognizesBlankLyricLineProjectionWhileSeeking() {
        Metadata stable = new Metadata("", "Welcome To New York", "Taylor Swift", 212_000L);
        Metadata projection = new Metadata(
                "",
                "",
                "Welcome To New York - Taylor Swift",
                212_000L);

        assertTrue(LxMusicBluetoothLyricMetadataPolicy.isBluetoothLyricProjection(
                stable, projection, true));
    }

    @Test
    public void preservesActualTrackChangesAndNormalMetadata() {
        Metadata stable = new Metadata("style-id", "Style", "Taylor Swift", 231_000L);
        Metadata normal = new Metadata("style-id", "Style", "Taylor Swift", 231_000L);
        Metadata nextTrack = new Metadata(
                "welcome-id", "Welcome To New York", "Taylor Swift", 212_000L);
        Metadata matchingShapeWithoutLyrics = new Metadata(
                "", "A lyric line", "Style - Taylor Swift", 231_000L);
        Metadata blankProjectionWithoutLyrics = new Metadata(
                "", "", "Style - Taylor Swift", 231_000L);

        assertFalse(LxMusicBluetoothLyricMetadataPolicy.isBluetoothLyricProjection(
                stable, normal, true));
        assertFalse(LxMusicBluetoothLyricMetadataPolicy.isBluetoothLyricProjection(
                stable, nextTrack, true));
        assertFalse(LxMusicBluetoothLyricMetadataPolicy.isBluetoothLyricProjection(
                stable, matchingShapeWithoutLyrics, false));
        assertFalse(LxMusicBluetoothLyricMetadataPolicy.isBluetoothLyricProjection(
                stable, blankProjectionWithoutLyrics, false));
    }
}
