package io.github.andrealtb.artwork.am;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import io.github.andrealtb.artwork.contract.ArtworkResult;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

public class AmVideoSourcesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File video(AmCache cache, String bytes) throws Exception {
        File temp = cache.temporary(); Files.write(temp.toPath(), bytes.getBytes(StandardCharsets.UTF_8));
        return cache.commit(temp);
    }

    @Test public void recordedSourcesSurviveProcessReopenAndSharedContentRetainsBothSources() throws Exception {
        File root = temporary.newFolder(); AmCache cache = new AmCache(root);
        File apple = video(cache, "apple"), netease = video(cache, "netease");
        cache.rememberSource(apple, AmVideoSources.Source.AM);
        cache.rememberSource(netease, AmVideoSources.Source.NETEASE);
        var sources = new AmCache(root).videoSources(List.of(apple, netease), Set.of());
        assertEquals(AmVideoSources.Source.AM, sources.get(apple.getName()));
        assertEquals(AmVideoSources.Source.NETEASE, sources.get(netease.getName()));
        cache.rememberSource(apple, AmVideoSources.Source.NETEASE);
        assertEquals(AmVideoSources.Source.BOTH, cache.videoSources(List.of(apple), Set.of()).get(apple.getName()));
    }

    @Test public void oldInventoriesAndKnownNeteaseIndexesRecoverLabelsWithoutDownloading() throws Exception {
        File root = temporary.newFolder(); AmCache cache = new AmCache(root);
        File apple = video(cache, "legacy apple"), netease = video(cache, "legacy netease");
        File inventory = new File(root, AmCache.hash("old-apple-inventory") + ".json");
        Files.writeString(inventory.toPath(), new JSONObject().put("videos", new JSONArray()
                .put(new JSONObject().put("file", apple.getName()))).toString());
        String neteaseKey = NcmSearch.albumKey("危险世界", "2759704", 1280, 1280, 20 * 1024 * 1024);
        cache.remember(neteaseKey, netease, null);
        var sources = cache.videoSources(List.of(apple, netease), Set.of(neteaseKey));
        assertEquals(AmVideoSources.Source.AM, sources.get(apple.getName()));
        assertEquals(AmVideoSources.Source.NETEASE, sources.get(netease.getName()));
        Files.delete(inventory.toPath()); Files.delete(new File(root, neteaseKey + ".json").toPath());
        assertEquals(sources, new AmCache(root).videoSources(List.of(apple, netease), Set.of()));
    }

    @Test public void missingProvenanceAndFailureIndexesDoNotGuessTheSource() throws Exception {
        File root = temporary.newFolder(); AmCache cache = new AmCache(root); File video = video(cache, "unclassified");
        String key = AmCache.hash("netease-no-motion");
        cache.remember(key, null, ArtworkResult.failure(Status.NO_MOTION, "netease_album_no_motion"));
        assertEquals(AmVideoSources.Source.UNKNOWN, cache.videoSources(List.of(video), Set.of(key)).get(video.getName()));
        assertFalse(new File(root, video.getName() + ".source").exists());
        assertTrue(video.isFile());
    }

    @Test public void clearingAndEvictionKeepSourceMetadataExactlyAsLongAsPinnedVideo() throws Exception {
        File root = temporary.newFolder(); AmCache cache = new AmCache(root); File video = video(cache, "pinned video");
        cache.rememberSource(video, AmVideoSources.Source.NETEASE);
        File marker = new File(root, video.getName() + ".source");
        cache.clear(); assertTrue(video.isFile()); assertTrue(marker.isFile());
        cache.unpin(video); cache.setBudget(1); cache.cleanup();
        assertFalse(video.exists()); assertFalse(marker.exists());
        File second = video(cache, "second"); cache.rememberSource(second, AmVideoSources.Source.AM);
        cache.unpin(second); cache.clear();
        assertFalse(second.exists()); assertFalse(new File(root, second.getName() + ".source").exists());
    }

    @Test public void sourceWritesCannotEscapeTheCacheAndOrphanLabelsAreReaped() throws Exception {
        File root = temporary.newFolder(), other = temporary.newFolder();
        File outside = new File(other, AmCache.hash("outside") + ".mp4"); Files.writeString(outside.toPath(), "outside");
        AmVideoSources.remember(root, outside, AmVideoSources.Source.AM);
        assertEquals(0, root.list().length);
        File orphan = new File(root, AmCache.hash("orphan") + ".mp4.source"); Files.writeString(orphan.toPath(), "am");
        new AmCache(root).cleanup(); assertFalse(orphan.exists()); assertTrue(outside.exists());
    }
}
