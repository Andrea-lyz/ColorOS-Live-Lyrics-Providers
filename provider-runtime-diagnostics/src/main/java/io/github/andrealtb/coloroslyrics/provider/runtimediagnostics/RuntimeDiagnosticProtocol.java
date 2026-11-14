package io.github.andrealtb.coloroslyrics.provider.runtimediagnostics;

import android.os.Bundle;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Finite diagnostic protocol. No lyrics, media ids, commands, URLs or arbitrary target extras. */
public final class RuntimeDiagnosticProtocol {
    public static final int MAX_TARGETS = 32, MAX_REPLIES = 16;
    private RuntimeDiagnosticProtocol() { }
    public static boolean packageName(String name) {
        return name != null && name.length() <= 200 && name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }
    public static boolean requestId(String id) {
        try { return id != null && UUID.fromString(id).toString().equalsIgnoreCase(id); }
        catch (IllegalArgumentException ignored) { return false; }
    }
    public static boolean processMatches(String target, String process) {
        if (target == null || process == null) return false;
        if (target.equals("android")) return process.equals("system_server") || process.equals("android");
        return packageName(target) && process.matches("[A-Za-z0-9_.:-]{1,256}")
                && (process.equals(target) || process.startsWith(target + ":"));
    }
    public static long nextRevision(long previous, long now) {
        if (previous == Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(Math.max(0L, previous) + 1L, Math.max(1L, now));
    }
    public static List<String> declaredTargets(Class<?> anchor) {
        try (InputStream input = anchor.getClassLoader().getResourceAsStream("META-INF/xposed/scope.list")) {
            if (input == null) return Collections.emptyList();
            byte[] bytes = bounded(input);
            Set<String> result = new LinkedHashSet<>();
            for (String raw : new String(bytes, StandardCharsets.UTF_8).split("\\r?\\n")) {
                String value = raw.trim();
                if (value.isEmpty() || value.startsWith("#")) continue;
                if (value.equals("system")) value = "android";
                if (!value.equals("android") && !packageName(value)) return Collections.emptyList();
                result.add(value);
                if (result.size() > MAX_TARGETS) return Collections.emptyList();
            }
            return new ArrayList<>(result);
        } catch (Exception ignored) { return Collections.emptyList(); }
    }
    public static String readIdentity(InputStream input) {
        if (input == null) return "unknown";
        try {
            Properties properties = new Properties();
            properties.load(new java.io.ByteArrayInputStream(bounded(input)));
            return "1".equals(properties.getProperty("schema")) ? buildId(properties.getProperty("buildId")) : "unknown";
        } catch (Exception ignored) { return "unknown"; }
    }
    private static byte[] bounded(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024]; int n;
        while ((n = input.read(buffer)) != -1) {
            if (bytes.size() + n > 8192) throw new java.io.IOException("diagnostic resource budget");
            bytes.write(buffer, 0, n);
        }
        return bytes.toByteArray();
    }
    public static String buildId(String value) {
        if (value == null || value.length() > 1024 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) return "unknown";
        return value.matches("ColorOS-Live-Lyrics-Providers:[A-Za-z0-9_-]{1,64}:(debug|release|diagnostic):([0-9a-f]{40}|[0-9a-f]{64}|unknown):(clean|dirty|unknown):[0-9a-f]{64}:[A-Za-z0-9._-]{1,64}") ? value : "unknown";
    }
    private static String label(Bundle bundle, String key, int max) {
        String value = bundle.getString(key, "unknown");
        if (value == null || value.length() > max || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) return "unknown";
        return value;
    }
    public static final class Collector {
        public final String module, requestId, installedBuildId;
        public final Set<String> targets;
        private final Map<String, Bundle> replies = new LinkedHashMap<>();
        public int rejected, omitted;
        private boolean closed;
        public Collector(String module, String requestId, String installedBuildId, List<String> targets) {
            if (!packageName(module) || !RuntimeDiagnosticProtocol.requestId(requestId) || targets.size() > MAX_TARGETS) throw new IllegalArgumentException("Invalid diagnostic query");
            this.module = module; this.requestId = requestId; this.installedBuildId = buildId(installedBuildId);
            this.targets = Collections.unmodifiableSet(new LinkedHashSet<>(targets));
        }
        public synchronized boolean accept(Bundle data) {
            if (closed) return false;
            try {
                String target = label(data, "target", 200), process = label(data, "process", 256);
                String epoch = label(data, "processEpoch", 36);
                if (data.getInt("schema", -1) != 1 || !requestId.equals(data.getString("requestId"))
                        || !module.equals(data.getString("module")) || !targets.contains(target)
                        || !processMatches(target, process) || !RuntimeDiagnosticProtocol.requestId(epoch)) { rejected++; return false; }
                String key = target + "/" + process + "/" + epoch;
                if (!replies.containsKey(key) && replies.size() >= MAX_REPLIES) { omitted++; return false; }
                Bundle clean = new Bundle();
                clean.putString("target", target); clean.putString("process", process); clean.putString("processEpoch", epoch);
                clean.putString("observedBuildId", buildId(data.getString("observedBuildId")));
                String framework = label(data, "framework", 160);
                clean.putString("framework", framework.matches("[A-Za-z][A-Za-z0-9_-]{0,48} / [0-9][0-9A-Za-z.() -]{0,80}") ? framework : "unknown");
                String stage = label(data, "installerStage", 64);
                clean.putString("installerStage", java.util.Arrays.asList("INSTALLER_RETURNED", "INSTALLER_FAILED", "NOT_OBSERVED").contains(stage) ? stage : "NOT_OBSERVED");
                String observation = label(data, "configObservation", 64);
                clean.putString("configObservation", java.util.Arrays.asList("CONSUMED", "CONSUMED_NO_REVISION", "BOOTSTRAP_NO_REVISION", "UNAVAILABLE", "NOT_OBSERVED", "NOT_APPLICABLE").contains(observation) ? observation : "NOT_OBSERVED");
                clean.putString("evidenceScope", "IN_PROCESS_SELF_REPORT_NOT_LYRIC_EFFECT");
                for (String field : new String[]{"moduleObservedAt", "replyObservedAt", "configRevision", "configObservedAt", "frameworkProperties"}) clean.putLong(field, data.getLong(field, -1L));
                clean.putInt("api", data.getInt("api", -1));
                clean.putBoolean("debugEnabled", data.getBoolean("debugEnabled", false));
                replies.put(key, clean);
                return true;
            } catch (RuntimeException malformed) { rejected++; return false; }
        }
        public synchronized List<Bundle> replies() {
            List<Bundle> copy = new ArrayList<>();
            for (Bundle value : replies.values()) copy.add(new Bundle(value));
            return copy;
        }
        public synchronized void close() { closed = true; }
        public String compare(String observed) {
            observed = buildId(observed);
            if (installedBuildId.equals("unknown") || observed.equals("unknown")) return "unknown";
            return installedBuildId.equals(observed) ? "MATCH" : "MISMATCH（可能是旧注入进程）";
        }
        public synchronized String format() {
            StringBuilder text = new StringBuilder("已安装 buildId：" + installedBuildId
                    + "\n只读查询；不启动播放器，不申请 scope，不改播放状态。\n仅当前用户；进程回包是自身观测，不是框架认证或歌词有效证明。\n");
            if (replies.isEmpty()) text.append("注入进程：unknown（超时/未运行/旧协议/接收器不可用；不能判为未启用）\n");
            for (Bundle row : replies.values()) {
                text.append("\n目标：").append(row.getString("target")).append(" · ").append(row.getString("process"))
                    .append("\n纪元：").append(row.getString("processEpoch"))
                    .append("\n观测 buildId：").append(row.getString("observedBuildId"))
                    .append("\n构建对照：").append(compare(row.getString("observedBuildId")))
                    .append("\n框架：").append(row.getString("framework")).append(" / API ").append(row.getInt("api"))
                    .append("\n安装器阶段：").append(row.getString("installerStage"))
                    .append("（RETURNED 不等于功能有效）")
                    .append("\nDebug 配置消费：").append(row.getString("configObservation"))
                    .append(" · revision=").append(row.getLong("configRevision"))
                    .append(" · observedAt=").append(row.getLong("configObservedAt"))
                    .append("\n回包 elapsedRealtime：").append(row.getLong("replyObservedAt")).append('\n');
            }
            return text.append("\n拒绝回包数：").append(rejected).append("；容量省略：").append(omitted).toString();
        }
    }
}
