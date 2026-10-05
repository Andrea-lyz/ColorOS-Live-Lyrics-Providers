package io.github.andrealtb.artwork.am;

import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

/** Preserve the original time guard before asking the extractor for sample flags. */
final class AmInitialSample {
    record Sample(long timeUs, int flags) {}

    private AmInitialSample() {}

    static Sample read(LongSupplier time, IntSupplier flags, Consumer<String> diagnostic) throws AmFailure {
        long origin;
        try { origin = time.getAsLong(); }
        catch (RuntimeException error) {
            throw new AmFailure(Status.UNSUPPORTED, "source_sample_read_failed", 0,
                    AmExceptionDiagnostic.describe("extractor_initial_sample_time", error));
        }
        trace(diagnostic, "stage=initial_time timeUs=" + origin);
        if (origin < 0) {
            throw new AmFailure(Status.UNSUPPORTED, "source_no_initial_sample", 0,
                    "stage=extractor_initial_sample_time timeUs=" + origin + " flags=not_read invalidTime=true");
        }
        int sampleFlags;
        try { sampleFlags = flags.getAsInt(); }
        catch (RuntimeException error) {
            throw new AmFailure(Status.UNSUPPORTED, "source_sample_read_failed", 0,
                    "timeUs=" + origin + " " + AmExceptionDiagnostic.describe("extractor_initial_sample_flags", error));
        }
        trace(diagnostic, "stage=initial_flags timeUs=" + origin + " flags=" + sampleFlags);
        return new Sample(origin, sampleFlags);
    }

    private static void trace(Consumer<String> diagnostic, String message) {
        if (diagnostic != null) try { diagnostic.accept(message); }
        catch (RuntimeException ignored) { /* Diagnostic failures do not change sample handling. */ }
    }
}
