package io.github.andrealtb.artwork.am;

import org.junit.Test;
import static org.junit.Assert.*;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

public class AmExceptionDiagnosticTest {
    @Test public void reportsStageAndCallLocationWithoutMessagesOrPrivateFilenames() {
        var error = new IllegalArgumentException("/data/user/0/private.mp4?token=secret");
        error.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("android.media.MediaExtractor", "setDataSource", "/private/secret.java", 123) });
        error.initCause(new RuntimeException("https://example.test/?cookie=secret"));
        String result = AmExceptionDiagnostic.describe("extractor_set_data_source", error);
        assertTrue(result.contains("stage=extractor_set_data_source"));
        assertTrue(result.contains("android.media.MediaExtractor.setDataSource:123"));
        assertTrue(result.contains("java.lang.IllegalArgumentException"));
        assertFalse(result.contains("secret"));
        assertFalse(result.contains("/data"));
        assertFalse(result.contains("https"));
        assertTrue(result.length() <= 1800);
    }

    @Test public void oversizedLastCandidateDoesNotHideDownloadedVideoFailure() {
        var media = new AmFailure(Status.UNSUPPORTED, "media_validation_failed", 0, "stage=extractor_set_data_source");
        var budget = new AmFailure(Status.UNSUPPORTED, "source_file_too_large");
        assertSame(media, AmFailure.preferVariantFailure(media, budget));
        assertSame(media, AmFailure.preferVariantFailure(budget, media));
        assertSame(budget, AmFailure.preferVariantFailure(null, budget));
    }

    @Test public void downloadedSampleFailureSurvivesLaterNetworkAndPlaylistFailures() {
        var sample = new AmFailure(Status.UNSUPPORTED, "source_no_initial_sample");
        var network = new AmFailure(Status.RETRY_LATER, "network_io", 30000);
        var playlist = new AmFailure(Status.UNSUPPORTED, "unsupported_hls_layout");
        assertSame(sample, AmFailure.preferVariantFailure(sample, network));
        assertSame(sample, AmFailure.preferVariantFailure(network, sample));
        assertSame(sample, AmFailure.preferVariantFailure(sample, playlist));
        assertSame(playlist, AmFailure.preferVariantFailure(network, playlist));
        assertSame(network, AmFailure.preferVariantFailure(null, network));
    }

    @Test public void extractionAndRemuxFailuresRetainTheirActualStage() {
        assertEquals("media_extract_failed", AmFailure.mediaStageReason("extractor_initial_sample_flags"));
        assertEquals("media_extract_failed", AmFailure.mediaStageReason("extractor_set_data_source"));
        assertEquals("media_remux_failed", AmFailure.mediaStageReason("muxer_write_sample"));
        assertEquals("media_validation_failed", AmFailure.mediaStageReason("final_file_validation"));
    }
}
