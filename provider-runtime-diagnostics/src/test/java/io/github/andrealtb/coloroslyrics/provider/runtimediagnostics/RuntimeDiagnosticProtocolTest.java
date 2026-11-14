package io.github.andrealtb.coloroslyrics.provider.runtimediagnostics;

import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 34, manifest = Config.NONE)
public class RuntimeDiagnosticProtocolTest {
    private final String request = UUID.randomUUID().toString();
    private final String module = "io.github.example.provider";
    private final String id = "ColorOS-Live-Lyrics-Providers:player-salt:debug:" + "a".repeat(40) + ":dirty:" + "b".repeat(64) + ":resolver-1";
    private RuntimeDiagnosticProtocol.Collector collector() {
        return new RuntimeDiagnosticProtocol.Collector(module, request, id, List.of("com.example.player"));
    }
    private Bundle reply() {
        Bundle row = new Bundle(); row.putInt("schema", 1); row.putString("requestId", request);
        row.putString("module", module); row.putString("target", "com.example.player");
        row.putString("process", "com.example.player:audio"); row.putString("processEpoch", UUID.randomUUID().toString());
        row.putString("observedBuildId", id); row.putString("installerStage", "INSTALLER_RETURNED");
        return row;
    }
    @Test public void matchingBuildRequiresAnActualValidatedReply() {
        var c = collector(); assertTrue(c.replies().isEmpty());
        assertTrue(c.accept(reply())); assertEquals("MATCH", c.compare(id));
        assertEquals("unknown", c.compare("unknown"));
        assertTrue(c.compare(id + "-new").startsWith("MISMATCH"));
    }
    @Test public void wrongRequestModuleTargetOrSchemaIsRejected() {
        for (String field : List.of("requestId", "module", "target", "process")) {
            var c = collector(); Bundle row = reply(); row.putString(field, "com.unrelated.value");
            assertFalse(field, c.accept(row)); assertEquals(1, c.rejected);
        }
        var c = collector(); Bundle row = reply(); row.putInt("schema", 2); assertFalse(c.accept(row));
    }
    @Test public void requestAndProcessEpochMustBeCanonicalUuids() {
        assertFalse(RuntimeDiagnosticProtocol.requestId("old-request"));
        Bundle row = reply(); row.putString("processEpoch", "unknown"); assertFalse(collector().accept(row));
    }
    @Test public void onlyCanonicalSystemServerMatchesSystemTarget() {
        assertTrue(RuntimeDiagnosticProtocol.processMatches("android", "system_server"));
        assertFalse(RuntimeDiagnosticProtocol.processMatches("android", "com.unrelated.system"));
        assertFalse(RuntimeDiagnosticProtocol.processMatches("com.example.player", "com.example.player2"));
    }
    @Test public void replyCollectionIsBoundedAndKeepsDistinctEpochs() {
        var c = collector();
        for (int i = 0; i < 40; i++) c.accept(reply());
        assertEquals(16, c.replies().size()); assertEquals(24, c.omitted);
    }
    @Test public void repeatedResponseInSameEpochDoesNotCreateAnotherProcess() {
        var c = collector(); Bundle row = reply(); assertTrue(c.accept(row)); assertTrue(c.accept(row));
        assertEquals(1, c.replies().size());
    }
    @Test public void lyricAndCredentialFieldsAreNotPartOfTheProtocol() {
        var c = collector(); Bundle row = reply(); row.putString("lyricInfo", "private lyric");
        row.putString("token", "private token"); row.putString("mediaId", "stable private id");
        assertTrue(c.accept(row)); Bundle clean = c.replies().get(0);
        assertFalse(clean.containsKey("lyricInfo")); assertFalse(clean.containsKey("token")); assertFalse(clean.containsKey("mediaId"));
        assertFalse(c.format().contains("private"));
    }
    @Test public void arbitraryStageAndFrameworkTextDoNotBecomeExportChannels() {
        var c = collector(); Bundle row = reply();
        row.putString("framework", "PRIVATE_PATH_TOKEN"); row.putString("installerStage", "PRIVATE_LYRIC");
        row.putString("configObservation", "PRIVATE_ACCOUNT"); assertTrue(c.accept(row));
        assertFalse(c.format().contains("PRIVATE_"));
    }
    @Test public void invalidBuildSyntaxIsUnknownRatherThanMismatch() {
        var c = collector(); assertEquals("unknown", c.compare("ColorOS-Live-Lyrics-Providers:PRIVATE_VALUE"));
    }
    @Test public void responseDataIsCopiedAndCannotBeChangedByTheSender() {
        var c = collector(); Bundle row = reply(); c.accept(row); row.clear();
        assertEquals("com.example.player", c.replies().get(0).getString("target"));
        Bundle first = c.replies().get(0); first.clear(); assertFalse(c.replies().get(0).isEmpty());
    }
    @Test public void lateResponsesDoNotReviveClosedQuery() {
        var c = collector(); c.close(); assertFalse(c.accept(reply())); assertTrue(c.replies().isEmpty());
    }
    @Test public void unknownIdentityDoesNotBecomeAnInstalledMatch() {
        var c = new RuntimeDiagnosticProtocol.Collector(module, request, "unknown", List.of("com.example.player"));
        assertTrue(c.accept(reply())); assertEquals("unknown", c.compare(id));
    }
    @Test public void configurationRevisionSurvivesClockRollback() {
        assertEquals(101L, RuntimeDiagnosticProtocol.nextRevision(100L, 5L));
        assertEquals(500L, RuntimeDiagnosticProtocol.nextRevision(-1L, 500L));
        assertTrue(RuntimeDiagnosticProtocol.nextRevision(-1L, 0L) > 0L);
    }
}
