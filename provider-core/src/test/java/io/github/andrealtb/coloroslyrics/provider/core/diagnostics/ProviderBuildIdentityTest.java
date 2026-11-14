package io.github.andrealtb.coloroslyrics.provider.core.diagnostics;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
public class ProviderBuildIdentityTest {
    @Test public void noIdentityIsUnknownNotClean() {
        assertEquals("unknown", ProviderBuildIdentity.parse(new byte[0]));
        assertEquals("unknown", ProviderBuildIdentity.parse(new byte[8193]));
    }
    @Test public void futureSchemaAndPartialDigestsAreNotAccepted() {
        assertEquals("unknown", parse("schema=2\nbuildId=foo\nsourceState=clean\nsourceDigest=abc\n"));
    }
    @Test public void unknownGitIsPreservedInAnOtherwiseValidArtifactIdentity() {
        String text = "schema=1\nbuildId=providers:sample:debug:unknown\nsourceState=unknown\nsourceDigest="
                + "a".repeat(64) + "\n";
        assertEquals("providers:sample:debug:unknown", parse(text));
    }
    private static String parse(String text) {
        return ProviderBuildIdentity.parse(text.getBytes(StandardCharsets.ISO_8859_1));
    }
}
