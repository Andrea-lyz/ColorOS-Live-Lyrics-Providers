package io.github.andrealtb.artwork.am;

import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Only the official login protocol; no service or account borrowed from another application. */
final class NcmProtocol {
    private static final String PUBLIC_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB";
    private static final Set<String> COOKIE_NAMES = Set.of("MUSIC_U", "__csrf", "NMTID", "MUSIC_A", "__remember_me");
    private static final SecureRandom RANDOM = new SecureRandom();
    private NcmProtocol() {}

    static byte[] weapi(String json) throws Exception {
        byte[] secret = new byte[16];
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < secret.length; i++) secret[i] = (byte) alphabet.charAt(RANDOM.nextInt(alphabet.length()));
        return weapi(json, secret);
    }
    static byte[] weapi(String json, byte[] secret) throws Exception {
        String first = aes(json.getBytes(StandardCharsets.UTF_8), "0CoJUm6Qyw8W8jud".getBytes(StandardCharsets.UTF_8));
        String params = aes(first.getBytes(StandardCharsets.UTF_8), secret);
        byte[] reversed = secret.clone();
        for (int i = 0; i < reversed.length / 2; i++) {
            byte v = reversed[i]; reversed[i] = reversed[reversed.length - 1 - i]; reversed[reversed.length - 1 - i] = v;
        }
        RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
        String encrypted = new BigInteger(1, reversed).modPow(key.getPublicExponent(), key.getModulus()).toString(16);
        return form(Map.of("params", params, "encSecKey", "0".repeat(256 - encrypted.length()) + encrypted));
    }
    private static String aes(byte[] data, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new IvParameterSpec("0102030405060708".getBytes(StandardCharsets.UTF_8)));
        return Base64.getEncoder().encodeToString(cipher.doFinal(data));
    }
    static byte[] form(Map<String, String> values) {
        StringBuilder text = new StringBuilder();
        values.forEach((key, value) -> {
            if (text.length() > 0) text.append('&');
            text.append(encode(key)).append('=').append(encode(value));
        });
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }
    static String encode(String text) {
        try { return URLEncoder.encode(text, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
    }
    static void validateApi(URI uri) {
        if (!secure(uri) || !"music.163.com".equals(uri.getHost())
                || !(uri.getPath().startsWith("/api/") || uri.getPath().startsWith("/weapi/"))) {
            throw new IllegalArgumentException("untrusted_network_uri");
        }
    }
    static URI videoUri(String url) {
        URI uri = URI.create(url);
        if ("http".equalsIgnoreCase(uri.getScheme())) uri = URI.create("https" + url.substring(4));
        validateVideo(uri);
        return uri;
    }
    static void validateVideo(URI uri) {
        if (!secure(uri) || !"dcover.music.126.net".equals(uri.getHost()) || !uri.getPath().endsWith(".mp4")) {
            throw new IllegalArgumentException("untrusted_network_uri");
        }
    }
    private static boolean secure(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getPort() == -1
                && uri.getUserInfo() == null && uri.getFragment() == null;
    }
    static String cookies(String previous, List<String> setCookies) {
        Map<String, String> jar = new LinkedHashMap<>();
        for (String entry : previous.split(";")) collect(jar, entry, false);
        for (String entry : setCookies) collect(jar, entry, true);
        StringBuilder text = new StringBuilder();
        jar.forEach((name, value) -> {
            if (text.length() > 0) text.append("; ");
            text.append(name).append('=').append(value);
        });
        return text.toString();
    }
    private static void collect(Map<String, String> jar, String entry, boolean header) {
        String pair = entry.split(";", 2)[0].trim();
        int separator = pair.indexOf('=');
        if (separator < 1) return;
        String name = pair.substring(0, separator), value = pair.substring(separator + 1);
        if (!COOKIE_NAMES.contains(name) || value.length() > 16_384 || !value.matches("[\\x21-\\x7E]*")) return;
        if (value.isEmpty() || header && entry.toLowerCase(java.util.Locale.ROOT).matches(".*;\\s*max-age=0(?:;.*)?")) jar.remove(name);
        else jar.put(name, value);
    }
    static String csrf(String cookie) {
        for (String part : cookie.split(";")) if (part.trim().startsWith("__csrf=")) return part.trim().substring(7);
        return "";
    }
    static boolean loggedIn(String cookie) {
        for (String part : cookie.split(";")) if (part.trim().startsWith("MUSIC_U=") && part.trim().length() > 8) return true;
        return false;
    }
    static String header(String cookie) { return (cookie.isEmpty() ? "" : cookie + "; ") + "os=pc; appver=9.5.70"; }
}
