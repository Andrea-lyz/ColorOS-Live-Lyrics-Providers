package io.github.andrealtb.artwork.am;

import java.nio.charset.StandardCharsets;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AmDiagnosticFileTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void retainedAcrossInstancesAndExportedInOrder() throws Exception {
        var root = temporary.newFolder();
        var file = new AmDiagnosticFile(root, 12);
        file.append("first");
        file.append("second"); // Rotate.
        assertEquals("first\nsecond\n", new String(new AmDiagnosticFile(root, 12).snapshot(), StandardCharsets.UTF_8));
        file.append("third");
        file.append("fourth");
        assertEquals("third\nfourth\n", new String(file.snapshot(), StandardCharsets.UTF_8));
        assertTrue(file.snapshot().length <= 24);
    }

    @Test public void utf8ByteBudgetAndOversizedEventDoNotGrowStorage() throws Exception {
        var file = new AmDiagnosticFile(temporary.newFolder(), 10);
        file.append("诊断");
        file.append("日志");
        file.append("oversized-event");
        assertEquals("诊断\n日志\n", new String(file.snapshot(), StandardCharsets.UTF_8));
        assertTrue(file.snapshot().length <= 20);
    }

    @Test public void clearRemovesBothSegmentsAndAllowsFurtherRecording() throws Exception {
        var file = new AmDiagnosticFile(temporary.newFolder(), 10);
        file.append("first");
        file.append("second");
        file.clear();
        assertEquals(0, file.snapshot().length);
        file.append("new");
        assertEquals("new\n", new String(file.snapshot(), StandardCharsets.UTF_8));
    }
}
