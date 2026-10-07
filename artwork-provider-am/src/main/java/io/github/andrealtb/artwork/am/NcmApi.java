package io.github.andrealtb.artwork.am;

import android.content.Context;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import io.github.andrealtb.artwork.contract.ArtworkResult.Status;

final class NcmApi {
    record Reply(JSONObject json, String cookie) {}
    record Qr(String key, String url) {}
    record QrState(int code, String cookie) {}
    private final AmNetwork network;
    private final java.util.function.LongConsumer expire;
    NcmApi(Context context, BooleanSupplier allowed) {
        this(new AmNetwork(allowed, detail -> AmSettings.trace(context, "ARTWORK_NETEASE_HTTP", detail), NcmProtocol::validateApi),
                epoch -> NcmSession.expire(context, epoch));
    }
    NcmApi(AmNetwork network, java.util.function.LongConsumer expire) {
        this.network = network; this.expire = expire;
    }
    List<NcmSearch.Song> search(String text, AmNetwork.Task task) throws AmFailure {
        return NcmSearch.songs(call("/api/cloudsearch/pc?" + new String(NcmProtocol.form(Map.of(
                "s", text, "type", "1", "limit", "30", "offset", "0", "total", "true")), StandardCharsets.UTF_8), null, "", task).json());
    }
    NcmSearch.Song song(String id, AmNetwork.Task task) throws AmFailure {
        if (!id.matches("[0-9]{1,20}")) throw new AmFailure(Status.UNSUPPORTED, "netease_invalid_link");
        List<NcmSearch.Song> songs = NcmSearch.songs(call("/api/song/detail/?ids=" + NcmProtocol.encode("[" + id + "]"), null, "", task).json());
        return songs.stream().filter(song -> song.id().equals(id)).findFirst()
                .orElseThrow(() -> new AmFailure(Status.RETRY_LATER, "netease_song_unavailable", 60_000));
    }
    Qr qr(AmNetwork.Task task) throws AmFailure {
        Reply reply = weapi("/weapi/login/qrcode/unikey", object(Map.of("type", 1)), "", task);
        String key = reply.json().optString("unikey");
        if (key.isEmpty() && reply.json().optJSONObject("data") != null) key = reply.json().optJSONObject("data").optString("unikey");
        if (!key.matches("[a-zA-Z0-9_-]{8,256}")) throw new AmFailure(Status.RETRY_LATER, "netease_qr_unavailable", 30_000);
        return new Qr(key, "https://music.163.com/login?codekey=" + NcmProtocol.encode(key));
    }
    QrState check(Qr qr, AmNetwork.Task task) throws AmFailure {
        Reply reply = weapi("/weapi/login/qrcode/client/login", object(Map.of("key", qr.key(), "type", 1)), "", task);
        return new QrState(reply.json().optInt("code"), reply.cookie());
    }
    NcmSession.Session account(NcmSession.Session session, AmNetwork.Task task) throws AmFailure {
        Reply reply = call("/api/nuser/account/get", null, session.cookie(), task);
        int code = reply.json().optInt("code", -1);
        JSONObject profile = reply.json().optJSONObject("profile");
        if (code == 301 || code == 200 && (profile == null || profile.optLong("userId") <= 0)) {
            expire.accept(session.epoch());
            throw new AmFailure(Status.NETWORK_BLOCKED, "netease_login_required");
        }
        require(reply.json(), session);
        if (profile == null) throw new AmFailure(Status.RETRY_LATER, "netease_account_schema", 60_000);
        return new NcmSession.Session(reply.cookie(), profile.optString("userId"), profile.optString("nickname"), session.refreshedAt(), session.epoch());
    }
    NcmSession.Session refresh(NcmSession.Session session, AmNetwork.Task task) throws AmFailure {
        Reply reply = weapi("/weapi/login/token/refresh", object(Map.of("csrf_token", NcmProtocol.csrf(session.cookie()))), session.cookie(), task);
        require(reply.json(), session);
        return new NcmSession.Session(reply.cookie(), session.uid(), session.nickname(), System.currentTimeMillis(), session.epoch());
    }
    URI cover(NcmSession.Session session, String songId, AmNetwork.Task task) throws AmFailure {
        Reply reply = call("/api/songplay/dynamic-cover", NcmProtocol.form(Map.of("songId", songId,
                "scene", "fullscreen", "csrf_token", NcmProtocol.csrf(session.cookie()))), session.cookie(), task);
        require(reply.json(), session);
        JSONObject data = reply.json().optJSONObject("data");
        if (data == null) throw new AmFailure(Status.RETRY_LATER, "netease_cover_schema", 60_000);
        String url = data.optString("videoPlayUrl");
        if (url.isEmpty() && data.length() == 0) throw new AmFailure(Status.NO_MOTION, "netease_album_no_motion");
        if (url.isEmpty()) throw new AmFailure(Status.RETRY_LATER, "netease_cover_schema", 60_000);
        try { return NcmProtocol.videoUri(url); }
        catch (IllegalArgumentException invalid) { throw new AmFailure(Status.UNSUPPORTED, "netease_untrusted_video"); }
    }
    private void require(JSONObject value, NcmSession.Session session) throws AmFailure {
        int code = value.optInt("code", -1);
        if (code == 301) {
            expire.accept(session.epoch());
            throw new AmFailure(Status.NETWORK_BLOCKED, "netease_login_required");
        }
        if (code != 200) throw new AmFailure(Status.RETRY_LATER, "netease_api_unavailable", 60_000);
    }
    private Reply weapi(String path, JSONObject value, String cookie, AmNetwork.Task task) throws AmFailure {
        try { return call(path, NcmProtocol.weapi(value.toString()), cookie, task); }
        catch (AmFailure failure) {
            // A resolve-level recovery must not replay QR login or token-refresh mutations.
            task.blockTransportRecovery();
            throw failure;
        }
        catch (Exception unavailable) { throw new AmFailure(Status.ERROR, "netease_protocol_failed"); }
    }
    private Reply call(String path, byte[] body, String cookie, AmNetwork.Task task) throws AmFailure {
        AmNetwork.Response response = network.response(URI.create("https://music.163.com" + path), body, NcmProtocol.header(cookie), task,
                path.startsWith("/api/"));
        try {
            JSONObject value = new JSONObject(response.text());
            if (value.optInt("code") == 429) throw new AmFailure(Status.RETRY_LATER, "upstream_rate_limit", 300_000);
            return new Reply(value, NcmProtocol.cookies(cookie, response.cookies()));
        } catch (AmFailure failure) { throw failure; }
        catch (Exception changed) { throw new AmFailure(Status.RETRY_LATER, "netease_response_schema", 60_000); }
    }
    private static JSONObject object(Map<String, Object> values) { return new JSONObject(values); }
}
