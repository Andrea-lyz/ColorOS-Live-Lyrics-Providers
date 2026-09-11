package io.github.andrealtb.coloroslyrics.provider.readify;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
public final class ReadifyRegressionTest {
    @Test public void cleansSentenceWithoutBreakingUnicode() { SentenceSnapshotTest.main(new String[0]); }
    @Test public void gatesHostVersionBeforeServiceCreation() throws Exception { VersionGateRegressionTest.main(new String[0]); }
    @Test public void buildsBoundedWindowAndStableWireIdentity() {
        SentenceWindow window = SentenceWindow.select(Arrays.asList("a","b","c","d","e","f"),
                Arrays.asList("A","B","C","D","E","F"), "d", "D");
        assertEquals(Arrays.asList("B","C","D","E","F"), window.lines);
        assertEquals(2, window.current);
        assertEquals("id|book||0", ProviderTrackKey.build("id", "Book", "", 0));
        window = SentenceWindow.select(Collections.emptyList(), Collections.emptyList(), "x", "X");
        assertEquals(Collections.singletonList("X"), window.lines);
        assertEquals(0, window.current);
    }
}
