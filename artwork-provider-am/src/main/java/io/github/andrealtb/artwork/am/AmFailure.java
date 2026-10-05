package io.github.andrealtb.artwork.am;

import io.github.andrealtb.artwork.contract.ArtworkResult;

final class AmFailure extends Exception {
    final ArtworkResult.Status status;
    final String reason;
    final long retryMs;
    /** Diagnostic only (a parsing step or host), never sent to the client and never page content. */
    final String detail;
    AmFailure(ArtworkResult.Status status, String reason) { this(status, reason, 0); }
    AmFailure(ArtworkResult.Status status, String reason, long retryMs) { this(status, reason, retryMs, null); }
    AmFailure(ArtworkResult.Status status, String reason, long retryMs, String detail) {
        super(reason);
        this.status = status;
        this.reason = reason;
        this.retryMs = retryMs;
        this.detail = detail;
    }
    ArtworkResult result() { return new ArtworkResult(status, null, retryMs, reason); }
    static AmFailure preferVariantFailure(AmFailure previous, AmFailure next) {
        // Preserve the furthest completed stage; every candidate still has its own diagnostic event.
        return previous != null && variantPriority(previous) > variantPriority(next) ? previous : next;
    }

    private static int variantPriority(AmFailure failure) {
        if (failure.reason.equals("source_file_too_large")) return 0;
        if (failure.status == ArtworkResult.Status.RETRY_LATER) return 1;
        return switch (failure.reason) {
            case "source_no_initial_sample", "source_sample_read_failed", "source_initial_keyframe_missing",
                    "missing_initial_keyframe", "media_extract_failed", "media_remux_failed", "media_validation_failed",
                    "source_track_layout", "source_format_mismatch", "source_sample_budget", "source_sample_incomplete",
                    "source_duration_mismatch", "remux_size_budget", "media_manifest_mismatch" -> 3;
            default -> 2;
        };
    }

    static String mediaStageReason(String stage) {
        if (stage.startsWith("extractor_")) return "media_extract_failed";
        if (stage.startsWith("muxer_") || stage.equals("set_output_duration")) return "media_remux_failed";
        return "media_validation_failed";
    }
}
