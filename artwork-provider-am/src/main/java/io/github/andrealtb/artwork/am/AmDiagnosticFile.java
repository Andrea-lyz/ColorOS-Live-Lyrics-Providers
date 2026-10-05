package io.github.andrealtb.artwork.am;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/** Two bounded UTF-8 segments, accessed only by the diagnostic writer. No media cache dependency. */
final class AmDiagnosticFile {
    static final int MAX_BYTES = 512 * 1024;
    private final File directory;
    private final int limit;

    AmDiagnosticFile(File directory) { this(directory, MAX_BYTES); }
    AmDiagnosticFile(File directory, int limit) { this.directory = directory; this.limit = limit; }

    void append(String line) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("diagnostic_directory");
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) return;
        File current = new File(directory, "current.txt");
        if (current.length() + bytes.length > limit) {
            Files.move(current.toPath(), new File(directory, "previous.txt").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Files.write(current.toPath(), bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    byte[] snapshot() throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (String name : new String[] { "previous.txt", "current.txt" }) {
            File file = new File(directory, name);
            if (file.isFile() && file.length() <= limit) out.write(Files.readAllBytes(file.toPath()));
        }
        return out.toByteArray();
    }

    void clear() throws IOException {
        for (String name : new String[] { "previous.txt", "current.txt" }) {
            Files.deleteIfExists(new File(directory, name).toPath());
        }
    }
}
