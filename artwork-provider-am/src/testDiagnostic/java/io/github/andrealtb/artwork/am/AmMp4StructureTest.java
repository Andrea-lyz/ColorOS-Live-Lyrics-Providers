package io.github.andrealtb.artwork.am;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AmMp4StructureTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void reportsWholeFileHashAndFragmentedContainerOffsets() throws Exception {
        byte[] bytes = ByteBuffer.allocate(32)
                .putInt(8).put("ftyp".getBytes(StandardCharsets.US_ASCII))
                .putInt(8).put("moov".getBytes(StandardCharsets.US_ASCII))
                .putInt(8).put("moof".getBytes(StandardCharsets.US_ASCII))
                .putInt(0).put("mdat".getBytes(StandardCharsets.US_ASCII)).array();
        var file = temporary.newFile(); Files.write(file.toPath(), bytes);
        String result = AmMp4Structure.describe(file);
        assertTrue(result.contains("sha256=" + AmCache.digest(bytes)));
        assertTrue(result.contains("box=moof offset=16 bytes=8"));
        assertTrue(result.contains("box=mdat offset=24 bytes=8"));
        assertTrue(result.contains("container=top_level_complete"));
    }

    @Test public void rejectsTruncatedOrInvalidBoxBoundsAndHandlesExtendedSize() throws Exception {
        var file = temporary.newFile();
        Files.write(file.toPath(), new byte[] { 0, 0, 0 });
        assertTrue(AmMp4Structure.describe(file).contains("container=truncated_header"));
        Files.write(file.toPath(), ByteBuffer.allocate(8).putInt(99).put("moov".getBytes(StandardCharsets.US_ASCII)).array());
        assertTrue(AmMp4Structure.describe(file).contains("container=invalid_box_bounds"));
        Files.write(file.toPath(), ByteBuffer.allocate(16).putInt(1).put("free".getBytes(StandardCharsets.US_ASCII)).putLong(16).array());
        assertTrue(AmMp4Structure.describe(file).contains("container=top_level_complete"));
        Files.write(file.toPath(), ByteBuffer.allocate(16).putInt(1).put("free".getBytes(StandardCharsets.US_ASCII)).putLong(-1).array());
        assertTrue(AmMp4Structure.describe(file).contains("container=invalid_box_bounds"));
    }

    @Test public void stopsAtBoxCountLimit() throws Exception {
        var bytes = ByteBuffer.allocate(129 * 8);
        for (int i = 0; i < 129; i++) bytes.putInt(8).put("free".getBytes(StandardCharsets.US_ASCII));
        var file = temporary.newFile(); Files.write(file.toPath(), bytes.array());
        assertTrue(AmMp4Structure.describe(file).contains("container=box_count_limit"));
    }
}
