package io.github.andrealtb.artwork.am;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONObject;
import io.github.andrealtb.artwork.contract.ArtworkQuery;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

/** Reduced public NetEase/QQ/Apple responses with source URLs/hashes; tests never access the network. */
public class AmPlatformMatchingTest {
    private JSONObject sample(String name) throws Exception {
        try (var stream=getClass().getResourceAsStream("/matching-platform-public.json")) {
            assertNotNull(stream);
            var cases=new JSONObject(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getJSONArray("cases");
            for(int i=0;i<cases.length();i++) if(cases.getJSONObject(i).getString("name").equals(name)) return cases.getJSONObject(i);
            throw new AssertionError(name);
        }
    }
    private ArtworkQuery query(JSONObject sample) throws Exception {
        var q=sample.getJSONObject("query");
        return AmMatchingBoundaryTest.query(q.getString("title"),q.getString("artist"),q.getString("album"),q.getLong("durationMs"));
    }
    private List<AmIdentity.Track> tracks(JSONObject sample) throws Exception {
        return AmPage.itunes(new JSONObject().put("results",sample.getJSONArray("candidates")).toString());
    }
    @Test public void actualRichManMiniAlbumAndEpDescriptorsStillMatch() throws Exception {
        var sample=sample("rich");
        assertEquals("1833163983",AmIdentity.unique(tracks(sample),query(sample),"","").songId());
    }
    @Test public void actualBurningUpSingleMatchesButDuplicateReleaseIdsStayAmbiguous() throws Exception {
        var sample=sample("burning");
        var q=query(sample);
        var candidates=tracks(sample);
        assertTrue(candidates.stream().anyMatch(t->AmIdentity.agrees(t,q)));
        try { AmIdentity.unique(candidates,q,"",""); fail(); }
        catch(AmFailure failure) { assertEquals(Status.AMBIGUOUS,failure.status); }
    }
    @Test public void actualQqSunnyMatchesTraditionalAppleMetadata() throws Exception {
        var sample=sample("sunny");
        var chosen=AmIdentity.unique(tracks(sample),query(sample),"","");
        assertEquals("晴天",chosen.title());
        assertEquals("葉惠美",chosen.album());
        assertEquals("周杰倫",chosen.artist());
    }
    @Test public void actualQqWithCreditAndSingleDescriptorMatchTogether() throws Exception {
        var sample=sample("waiting");
        assertEquals("1721450095",AmIdentity.unique(tracks(sample),query(sample),"","").songId());
    }
    @Test public void actualNeteaseOrdinaryFortnightRejectsAnthologyOnEveryProfile() throws Exception {
        var sample=sample("fortnight-cross-edition");
        for(var profile:List.of(AmIdentity.MatchProfile.STRICT,AmIdentity.MatchProfile.STANDARD,AmIdentity.MatchProfile.LOOSE)) {
            for(var track:tracks(sample)) assertFalse(AmIdentity.agrees(track,query(sample),profile));
            try { AmIdentity.unique(tracks(sample),query(sample),"","",profile); fail(); }
            catch(AmFailure failure) { assertEquals("catalog_match_unconfirmed",failure.reason); }
        }
    }
}
