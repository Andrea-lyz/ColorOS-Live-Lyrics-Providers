package io.github.andrealtb.artwork.am;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Private, non-backed-up credentials encrypted with an Android Keystore key. Never exposed by IPC. */
final class NcmSession {
    record Session(String cookie, String uid, String nickname, long refreshedAt, long epoch) {}
    private static final String KEY = "artwork-netease-session-v1";
    private static final Object LOCK = new Object();
    private static long epoch;
    private static boolean loaded;
    private static Session cached;
    private NcmSession() {}
    static boolean enabled(Context context) { return AmSettings.prefs(context).getBoolean("netease", false); }
    static long epoch() { synchronized (LOCK) { return epoch; } }
    private static AtomicFile file(Context context) { return new AtomicFile(new File(context.getNoBackupFilesDir(), "netease-session.enc")); }
    static Session read(Context context) {
        synchronized (LOCK) {
            if (loaded) return atEpoch(cached);
            try {
                AtomicFile file = file(context);
                if (!file.getBaseFile().isFile() || file.getBaseFile().length() > 64 * 1024) return null;
                JSONObject encrypted = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.getDecoder().decode(encrypted.getString("iv"))));
                JSONObject saved = new JSONObject(new String(cipher.doFinal(Base64.getDecoder().decode(encrypted.getString("data"))), StandardCharsets.UTF_8));
                String cookie = NcmProtocol.cookies(saved.getString("cookie"), java.util.List.of());
                if (!NcmProtocol.loggedIn(cookie)) return null;
                cached = new Session(cookie, saved.optString("uid"), saved.optString("nickname"), saved.optLong("refreshedAt"), epoch);
                return cached;
            } catch (Exception unavailable) { return null; }
            finally { loaded = true; }
        }
    }
    private static Session atEpoch(Session session) {
        return session == null ? null : new Session(session.cookie(), session.uid(), session.nickname(), session.refreshedAt(), epoch);
    }
    static boolean save(Context context, Session session, long expectedEpoch) throws Exception {
        synchronized (LOCK) {
            if (epoch != expectedEpoch || !enabled(context)) return false;
            if (cached != null && session.refreshedAt() < cached.refreshedAt()) return false;
            if (!NcmProtocol.loggedIn(session.cookie())) throw new IllegalArgumentException("netease_login_cookie_missing");
            JSONObject plain = new JSONObject().put("cookie", session.cookie()).put("uid", session.uid())
                    .put("nickname", session.nickname()).put("refreshedAt", session.refreshedAt());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            JSONObject encrypted = new JSONObject().put("iv", Base64.getEncoder().encodeToString(cipher.getIV()))
                    .put("data", Base64.getEncoder().encodeToString(cipher.doFinal(plain.toString().getBytes(StandardCharsets.UTF_8))));
            AtomicFile file = file(context);
            FileOutputStream out = file.startWrite();
            try { out.write(encrypted.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
            catch (Exception error) { file.failWrite(out); throw error; }
            cached = atEpoch(session); loaded = true;
            AmSettings.prefs(context).edit().putBoolean("neteaseExpired", false).apply();
            return true;
        }
    }
    static void logout(Context context) {
        synchronized (LOCK) {
            epoch++;
            file(context).delete();
            cached = null; loaded = true;
            AmSettings.prefs(context).edit().putBoolean("neteaseExpired", false).apply();
        }
    }
    static void expire(Context context, long expectedEpoch) {
        synchronized (LOCK) {
            if (epoch != expectedEpoch) return;
            logout(context);
            AmSettings.prefs(context).edit().putBoolean("neteaseExpired", true).apply();
        }
    }
    /** Invalidates in-flight requests even when the user turns the source off and on quickly. */
    static void enable(Context context, boolean enabled) {
        synchronized (LOCK) {
            epoch++;
            AmSettings.prefs(context).edit().putBoolean("netease", enabled).apply();
        }
    }
    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(KEY)) return (SecretKey) store.getKey(KEY, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
