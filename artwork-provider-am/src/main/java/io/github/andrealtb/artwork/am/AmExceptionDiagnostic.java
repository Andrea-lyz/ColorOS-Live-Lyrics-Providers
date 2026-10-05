package io.github.andrealtb.artwork.am;

/** Bounded stack locations only: exception messages/file names may contain private paths or URLs. */
final class AmExceptionDiagnostic {
    private AmExceptionDiagnostic() {}

    static String describe(String stage, Throwable error) {
        StringBuilder out = new StringBuilder("stage=").append(name(stage));
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 3; depth++) {
            out.append(depth == 0 ? " exception=" : " cause=").append(name(cause.getClass().getName()));
            StackTraceElement[] frames = cause.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 6); i++) {
                out.append(" at=").append(name(frames[i].getClassName())).append('.').append(name(frames[i].getMethodName()))
                        .append(':').append(frames[i].getLineNumber());
            }
            if (cause.getCause() == cause) break;
            cause = cause.getCause();
        }
        return out.substring(0, Math.min(out.length(), 1800));
    }

    private static String name(String value) { return value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9_.$<>]", "_"); }
}
