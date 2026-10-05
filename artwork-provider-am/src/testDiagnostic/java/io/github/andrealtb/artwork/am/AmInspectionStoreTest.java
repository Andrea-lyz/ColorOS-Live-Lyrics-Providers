package io.github.andrealtb.artwork.am;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.zip.ZipInputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AmInspectionStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void retainsOnlyFirstOriginalOutsideCacheAndExportsExactBytesAfterReopen() throws Exception {
        File cache = temporary.newFolder("cache");
        File root = temporary.newFolder("inspection");
        var store = new AmInspectionStore(root);
        File first = new File(cache, "download.part");
        byte[] bytes = { 0, 1, 2, 3, -1, -2 };
        Files.write(first.toPath(), bytes);
        assertTrue(store.retain(first, "reason=missing_initial_keyframe\n"));
        assertFalse(first.exists());
        File next = new File(cache, "next.part"); Files.write(next.toPath(), new byte[] { 7 });
        assertFalse(store.retain(next, "replacement")); assertTrue(next.exists());
        store.write("path.txt", "state=finished\n");
        var out = new ByteArrayOutputStream();
        new AmInspectionStore(root).export(out, "logs".getBytes(StandardCharsets.UTF_8));
        var entries = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) entries.put(entry.getName(), zip.readAllBytes());
        }
        assertEquals(7, entries.size());
        assertArrayEquals(bytes, entries.get("original.mp4"));
        assertEquals("reason=missing_initial_keyframe\n", new String(entries.get("capture.txt"), StandardCharsets.UTF_8));
        assertEquals("state=finished\n", new String(entries.get("path.txt"), StandardCharsets.UTF_8));
        assertEquals("logs", new String(entries.get("diagnostics.txt"), StandardCharsets.UTF_8));
        store.clear(); assertFalse(store.hasSample()); assertTrue(next.exists());
    }

    @Test public void importIsByteExactAndOversizeOrEmptyInputCannotLeavePartialSample() throws Exception {
        var store = new AmInspectionStore(temporary.newFolder());
        byte[] source = "same-device-comparison".getBytes(StandardCharsets.UTF_8);
        assertTrue(store.importSample(new ByteArrayInputStream(source), "origin=manual_import"));
        assertArrayEquals(source, Files.readAllBytes(store.source().toPath()));
        store.clear();
        try { store.importSample(new ByteArrayInputStream(new byte[0]), "empty"); fail(); }
        catch (java.io.IOException expected) { assertFalse(store.hasSample()); }
        InputStream oversized = new InputStream() {
            long remaining = AmInspectionStore.MAX_BYTES + 1;
            @Override public int read() { return remaining-- > 0 ? 0 : -1; }
            @Override public int read(byte[] buffer, int offset, int length) {
                if (remaining <= 0) return -1;
                int count = (int) Math.min(remaining, length); remaining -= count; return count;
            }
        };
        try { store.importSample(oversized, "oversize"); fail(); }
        catch (java.io.IOException expected) { assertFalse(store.hasSample()); }
        assertEquals(0, store.source().getParentFile().list().length);
    }

    @Test public void reportNamesAndSizeAreBoundedAndNoSampleCannotExport() throws Exception {
        var store = new AmInspectionStore(temporary.newFolder());
        try { store.write("../outside.txt", "bad"); fail(); }
        catch (IllegalArgumentException expected) { }
        try { store.write("path.txt", "x".repeat(32769)); fail(); }
        catch (java.io.IOException expected) { }
        try { store.export(new ByteArrayOutputStream(), new byte[0]); fail(); }
        catch (java.io.IOException expected) { }
    }
}
