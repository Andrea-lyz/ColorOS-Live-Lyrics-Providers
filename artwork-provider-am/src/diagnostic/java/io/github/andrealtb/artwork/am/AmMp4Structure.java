package io.github.andrealtb.artwork.am;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;

/** Bounded top-level container inventory, not a substitute for full MP4/sample validation. */
final class AmMp4Structure {
    private AmMp4Structure() {}

    static String describe(File file) throws Exception {
        long length = file.length();
        if (length <= 0 || length > AmInspectionStore.MAX_BYTES) throw new IllegalArgumentException("inspection_size_budget");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            long read = 0;
            for (int count; (count = stream.read(buffer)) != -1;) {
                read += count;
                if (read > length) throw new IllegalArgumentException("inspection_source_changed");
                digest.update(buffer, 0, count);
            }
            if (read != length) throw new IllegalArgumentException("inspection_source_changed");
        }
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        StringBuilder out = new StringBuilder("bytes=").append(length).append("\nsha256=").append(hash).append('\n');
        try (var input = new RandomAccessFile(file, "r")) {
            long offset = 0;
            int count = 0;
            while (offset < length && count++ < 128) {
                if (length - offset < 8) return out.append("container=truncated_header offset=").append(offset).append('\n').toString();
                input.seek(offset);
                long size = Integer.toUnsignedLong(input.readInt());
                byte[] type = new byte[4]; input.readFully(type);
                StringBuilder name = new StringBuilder();
                for (byte value : type) name.append(value >= 32 && value <= 126 ? (char) value : '?');
                int header = 8;
                if (size == 1) {
                    if (length - offset < 16) return out.append("container=truncated_extended_header\n").toString();
                    size = input.readLong(); header = 16;
                } else if (size == 0) size = length - offset;
                out.append("box=").append(name).append(" offset=").append(offset).append(" bytes=").append(size).append('\n');
                if (size < header || size > length - offset) return out.append("container=invalid_box_bounds\n").toString();
                offset += size;
            }
            out.append(offset == length ? "container=top_level_complete\n" : "container=box_count_limit\n");
        }
        return out.toString();
    }
}
