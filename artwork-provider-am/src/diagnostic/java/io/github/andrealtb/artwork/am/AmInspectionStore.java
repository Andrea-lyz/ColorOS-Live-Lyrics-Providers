package io.github.andrealtb.artwork.am;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** One immutable original, outside the video cache. All exported entry names are fixed. */
final class AmInspectionStore {
    static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Set<String> REPORTS = Set.of("capture.txt", "structure.txt", "path.txt", "fd.txt", "fd-seek.txt");
    private final File root;
    AmInspectionStore(File root) { this.root = root; }
    File source() { return new File(root, "source.part"); }
    synchronized boolean hasSample() { return source().isFile(); }

    synchronized boolean retain(File original, String metadata) throws IOException {
        if (hasSample() || !original.isFile() || original.length() <= 0 || original.length() > MAX_BYTES) return false;
        Files.createDirectories(root.toPath());
        // No second 20 MiB copy in the resolve path; cache and private files are on the same app volume.
        if (!original.renameTo(source())) return false;
        initialize(metadata);
        return true;
    }

    synchronized boolean importSample(InputStream input, String metadata) throws IOException {
        if (hasSample()) return false;
        Files.createDirectories(root.toPath());
        File pending = new File(root, "import.part");
        try {
            long bytes = 0;
            try (OutputStream out = Files.newOutputStream(pending.toPath())) {
                byte[] buffer = new byte[32 * 1024];
                for (int count; (count = input.read(buffer)) != -1;) {
                    bytes += count;
                    if (bytes > MAX_BYTES) throw new IOException("inspection_size_budget");
                    out.write(buffer, 0, count);
                }
            }
            if (bytes == 0) throw new IOException("inspection_empty_file");
            Files.move(pending.toPath(), source().toPath());
            initialize(metadata);
            return true;
        } finally { Files.deleteIfExists(pending.toPath()); }
    }

    private void initialize(String metadata) throws IOException {
        write("capture.txt", metadata);
        for (String name : REPORTS) if (!name.equals("capture.txt")) write(name, "state=pending\n");
    }

    synchronized void write(String name, String text) throws IOException {
        if (!REPORTS.contains(name)) throw new IllegalArgumentException("inspection_report_name");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 32 * 1024) throw new IOException("inspection_report_budget");
        Files.createDirectories(root.toPath());
        File pending = new File(root, name + ".tmp");
        Files.write(pending.toPath(), bytes);
        Files.move(pending.toPath(), new File(root, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    void export(OutputStream destination, byte[] diagnosticLog) throws IOException {
        Map<String, byte[]> reports = new LinkedHashMap<>();
        FileInputStream media;
        long size;
        synchronized (this) {
            if (!hasSample()) throw new IOException("inspection_no_sample");
            size = source().length();
            if (size <= 0 || size > MAX_BYTES) throw new IOException("inspection_size_budget");
            for (String name : new String[] { "capture.txt", "structure.txt", "path.txt", "fd.txt", "fd-seek.txt" }) {
                File file = new File(root, name);
                if (file.isFile() && file.length() <= 32 * 1024) reports.put(name, Files.readAllBytes(file.toPath()));
            }
            // Own an open descriptor so a later clear cannot switch the bytes under this snapshot.
            media = new FileInputStream(source());
        }
        try (media; ZipOutputStream zip = new ZipOutputStream(destination)) {
            for (var item : reports.entrySet()) {
                zip.putNextEntry(new ZipEntry(item.getKey())); zip.write(item.getValue()); zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("diagnostics.txt")); zip.write(diagnosticLog); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("original.mp4"));
            byte[] buffer = new byte[32 * 1024];
            long copied = 0;
            for (int count; (count = media.read(buffer)) != -1;) {
                copied += count;
                if (copied > size) throw new IOException("inspection_source_changed");
                zip.write(buffer, 0, count);
            }
            if (copied != size) throw new IOException("inspection_source_changed");
            zip.closeEntry();
        }
    }

    synchronized void clear() throws IOException {
        // Fixed names only. Never recursively delete a caller-supplied path or the media cache.
        for (String name : REPORTS) {
            Files.deleteIfExists(new File(root, name).toPath());
            Files.deleteIfExists(new File(root, name + ".tmp").toPath());
        }
        Files.deleteIfExists(source().toPath());
        Files.deleteIfExists(new File(root, "import.part").toPath());
    }
}
