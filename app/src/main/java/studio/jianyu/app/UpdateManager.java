package studio.jianyu.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Timer;
import java.util.TimerTask;
import javax.net.ssl.HttpsURLConnection;

final class UpdateManager {
    private static final String REPO = "981029l/jianyu";
    private static final String CDN_VERSION_JSON =
            "https://cdn.jsdelivr.net/gh/" + REPO + "@main/version.json";
    private static final String RAW_VERSION_JSON =
            "https://raw.githubusercontent.com/" + REPO + "/main/version.json";
    private static final String WEB_LATEST_RELEASE =
            "https://github.com/" + REPO + "/releases/latest";
    private static final String API_LATEST_RELEASE =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String MIRROR_PREFIX = "https://ghfast.top/";
    private static final int JSON_LIMIT = 256 * 1024;
    private static final int APK_LIMIT = 100 * 1024 * 1024;
    static final int CONNECT_TIMEOUT_MS = 6000;
    static final int READ_TIMEOUT_MS = 15000;
    static final int JSON_TIMEOUT_MS = 15000;
    static final int APK_TIMEOUT_MS = 2 * 60 * 1000;
    static final int MAX_REDIRECTS = 5;

    static final class UpdateInfo {
        final boolean hasUpdate;
        final int latestCode;
        final String latestVersion;
        final String currentVersion;
        final String releaseNotes;
        final String downloadUrl;

        UpdateInfo(boolean hasUpdate, int latestCode, String latestVersion,
                   String currentVersion, String releaseNotes, String downloadUrl) {
            this.hasUpdate = hasUpdate;
            this.latestCode = latestCode;
            this.latestVersion = latestVersion;
            this.currentVersion = currentVersion;
            this.releaseNotes = releaseNotes;
            this.downloadUrl = downloadUrl;
        }
    }

    interface ProgressListener {
        void onProgress(int percent);
        default void onChannelChanged(String channel) { }
    }

    static long nowMillis() { return SystemClock.elapsedRealtime(); }

    static HttpsURLConnection open(URL url, int connectMs, int readMs, boolean followRedirects) throws IOException {
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null) throw new IOException("仅支持 HTTPS 下载地址。");
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(followRedirects);
        conn.setConnectTimeout(connectMs);
        conn.setReadTimeout(readMs);
        conn.setRequestProperty("User-Agent", "Jianyu-Android/" + BuildConfig.VERSION_NAME);
        return conn;
    }

    private static String fetchText(String address, String accept) throws IOException {
        URL target = new URL(address);
        try (Transfer transfer = new Transfer(JSON_TIMEOUT_MS)) {
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            HttpsURLConnection conn = transfer.open(target);
            try {
                conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate");
                conn.setRequestProperty("Pragma", "no-cache");
                if (accept != null) conn.setRequestProperty("Accept", accept);
                int status = conn.getResponseCode();
                transfer.check();
                if (status >= 300 && status < 400 && conn.getHeaderField("Location") != null) {
                    target = new URL(target, conn.getHeaderField("Location"));
                    continue;
                }
                if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
                try (InputStream in = conn.getInputStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = transfer.read(in, buffer)) != -1) {
                        if (out.size() + count > JSON_LIMIT) throw new IOException("版本信息过大。");
                        out.write(buffer, 0, count);
                    }
                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            } finally { transfer.release(conn); }
        }
        }
        throw new IOException("重定向过多。");
    }

    static UpdateInfo checkForUpdate() throws IOException {
        String currentVersion = BuildConfig.VERSION_NAME;
        int currentCode = BuildConfig.VERSION_CODE;
        IOException lastError = null;
        UpdateInfo cdn = null, raw = null, probe = null, api = null;

        try {
            cdn = checkViaVersionJson(MIRROR_PREFIX + RAW_VERSION_JSON, currentCode, currentVersion);
            if (cdn.hasUpdate) return cdn;
        } catch (IOException e) { lastError = e; }

        try {
            UpdateInfo fallback = checkViaVersionJson(CDN_VERSION_JSON, currentCode, currentVersion);
            if (fallback.hasUpdate) return fallback;
            if (cdn == null) cdn = fallback;
        } catch (IOException e) { lastError = e; }

        try {
            raw = checkViaVersionJson(RAW_VERSION_JSON, currentCode, currentVersion);
            if (raw.hasUpdate) return raw;
        } catch (IOException e) { lastError = e; }

        try {
            probe = checkViaWebRedirect(WEB_LATEST_RELEASE, currentVersion);
            if (probe.hasUpdate) {
                String notes = cdn != null && !cdn.releaseNotes.isEmpty() ? cdn.releaseNotes
                        : raw != null && !raw.releaseNotes.isEmpty() ? raw.releaseNotes
                        : probe.releaseNotes;
                return new UpdateInfo(true, probe.latestCode, probe.latestVersion,
                        currentVersion, notes, probe.downloadUrl);
            }
        } catch (IOException e) { lastError = e; }

        try {
            api = checkViaGithubApi(API_LATEST_RELEASE, currentVersion);
            if (api.hasUpdate) return api;
        } catch (IOException e) { lastError = e; }

        UpdateInfo confirmed = probe != null ? probe : cdn != null ? cdn : raw != null ? raw : api;
        if (confirmed != null) {
            return new UpdateInfo(false, confirmed.latestCode, confirmed.latestVersion,
                    currentVersion, confirmed.releaseNotes, confirmed.downloadUrl);
        }
        throw new IOException("检查更新失败，请稍后重试（" + (lastError != null && lastError.getMessage() != null ? lastError.getMessage() : "网络波动") + "）");
    }

    private static UpdateInfo checkViaVersionJson(String baseUrl, int currentCode, String currentVersion) throws IOException {
        String url = baseUrl + (baseUrl.contains("?") ? "&_t=" : "?_t=") + System.currentTimeMillis();
        try {
            JSONObject json = new JSONObject(fetchText(url, "application/json"));
            int latestCode = json.optInt("versionCode", 0);
            String latestVersion = cleanVersion(json.optString("versionName", ""));
            String downloadUrl = json.optString("downloadUrl", "").trim();
            String releaseNotes = json.optString("releaseNotes", "新版本体验与性能优化").trim();
            if (latestVersion.isEmpty() || !downloadUrl.startsWith("https://")) {
                throw new IOException("版本清单格式异常");
            }
            boolean newer = (latestCode > 0 && latestCode > currentCode)
                    || isNewerVersion(latestVersion, currentVersion);
            return new UpdateInfo(newer, latestCode, latestVersion, currentVersion, releaseNotes, downloadUrl);
        } catch (org.json.JSONException e) {
            throw new IOException("版本清单解析失败");
        }
    }

    private static UpdateInfo checkViaWebRedirect(String url, String currentVersion) throws IOException {
        HttpsURLConnection conn = open(new URL(url), CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, false);
        try {
            conn.setRequestMethod("HEAD");
            conn.getResponseCode();
            String location = conn.getHeaderField("Location");
            if (location == null || location.trim().isEmpty()) throw new IOException("未获取到最新版本跳转地址");
            int slash = location.lastIndexOf('/');
            String tag = slash >= 0 ? location.substring(slash + 1).trim() : "";
            String latestVersion = cleanVersion(tag);
            if (latestVersion.isEmpty() || "releases".equalsIgnoreCase(latestVersion)) {
                throw new IOException("尚未发布版本");
            }
            String downloadUrl = "https://github.com/" + REPO + "/releases/download/" + tag + "/Jianyu-" + tag + ".apk";
            boolean newer = isNewerVersion(latestVersion, currentVersion);
            String notes = "发现最新版本 v" + latestVersion + "，支持应用内一键下载并覆盖安装。";
            return new UpdateInfo(newer, 0, latestVersion, currentVersion, notes, downloadUrl);
        } finally { conn.disconnect(); }
    }

    private static UpdateInfo checkViaGithubApi(String url, String currentVersion) throws IOException {
        try {
            JSONObject json = new JSONObject(fetchText(url, "application/vnd.github.v3+json"));
            String tag = json.optString("tag_name", "").trim();
            String latestVersion = cleanVersion(tag);
            String notes = json.optString("body", "新版本已发布").trim();
            String downloadUrl = "";
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) continue;
                    String name = asset.optString("name", "");
                    if (name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                        downloadUrl = asset.optString("browser_download_url", "").trim();
                        break;
                    }
                }
            }
            if (latestVersion.isEmpty() || !downloadUrl.startsWith("https://")) {
                throw new IOException("发布包信息不完整");
            }
            boolean newer = isNewerVersion(latestVersion, currentVersion);
            return new UpdateInfo(newer, 0, latestVersion, currentVersion, notes, downloadUrl);
        } catch (org.json.JSONException e) {
            throw new IOException("API 响应解析失败");
        }
    }

    static File downloadApk(Context context, UpdateInfo info, ProgressListener listener) throws IOException {
        File destDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (destDir == null) destDir = context.getFilesDir();
        if (!destDir.isDirectory() && !destDir.mkdirs()) throw new IOException("无法创建下载目录。");
        File tempFile = new File(destDir, "Jianyu-update.apk.tmp");
        File targetFile = new File(destDir, "Jianyu-update.apk");
        if (tempFile.exists()) tempFile.delete();
        if (targetFile.exists()) targetFile.delete();

        String[] candidates = downloadCandidates(info.downloadUrl);
        IOException lastError = null;
        for (String candidate : candidates) {
            try {
                if (listener != null) {
                    listener.onChannelChanged(candidate.startsWith(MIRROR_PREFIX) ? "加速通道" : "直连通道");
                    listener.onProgress(0);
                }
                downloadToFile(candidate, tempFile, listener);
                if (isValidApkFile(context, tempFile, info)) {
                    if (targetFile.exists()) targetFile.delete();
                    File result = tempFile.renameTo(targetFile) ? targetFile : tempFile;
                    if (listener != null) listener.onProgress(100);
                    return result;
                }
                throw new IOException("安装包不完整，或包名、版本、签名与预期不匹配。");
            } catch (IOException e) {
                lastError = e;
                if (tempFile.exists()) tempFile.delete();
            }
        }
        throw lastError != null ? lastError : new IOException("APK 下载失败，请检查网络连接。");
    }

    static String[] downloadCandidates(String url) throws IOException {
        URL target = new URL(url);
        if (!"https".equals(target.getProtocol()) || target.getUserInfo() != null) throw new IOException("更新地址无效。");
        return "github.com".equalsIgnoreCase(target.getHost())
                ? new String[]{MIRROR_PREFIX + url, url} : new String[]{url};
    }

    static void downloadToFile(String address, File dest, ProgressListener listener) throws IOException {
        URL target = new URL(address);
        try (Transfer transfer = new Transfer(APK_TIMEOUT_MS)) {
        for (int redirects = 0; redirects < MAX_REDIRECTS; redirects++) {
            HttpsURLConnection conn = transfer.open(target);
            try {
                conn.setRequestProperty("Accept-Encoding", "identity");
                int status = conn.getResponseCode();
                transfer.check();
                if (status >= 300 && status < 400 && conn.getHeaderField("Location") != null) {
                    target = new URL(target, conn.getHeaderField("Location"));
                    continue;
                }
                if (status != 200) throw new IOException("下载通道响应异常（HTTP " + status + "）");
                long total = conn.getContentLengthLong();
                if (total > APK_LIMIT) throw new IOException("安装包体积异常。");
                if (total <= 0 && listener != null) listener.onProgress(-1);
                try (InputStream in = conn.getInputStream();
                     OutputStream out = new FileOutputStream(dest)) {
                    byte[] buffer = new byte[16384];
                    long received = 0;
                    int lastPercent = -1;
                    int count;
                    while ((count = transfer.read(in, buffer)) != -1) {
                        received += count;
                        if (received > APK_LIMIT) throw new IOException("安装包体积异常。");
                        out.write(buffer, 0, count);
                        if (total > 0 && listener != null) {
                            int percent = (int) Math.min(99, Math.max(0, (received * 100) / total));
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                listener.onProgress(percent);
                            }
                        }
                    }
                    if (total > 0 && received != total) throw new IOException("安装包下载不完整。");
                    out.flush();
                }
                return;
            } finally { transfer.release(conn); }
        }
        }
        throw new IOException("下载地址重定向过多。");
    }

    static boolean isValidApkFile(Context context, File file, UpdateInfo expected) {
        if (!file.isFile() || file.length() < 1024) return false;
        try {
            PackageManager manager = context.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo apk = manager.getPackageArchiveInfo(file.getPath(), flags);
            PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
            if (apk == null || !context.getPackageName().equals(apk.packageName)) return false;
            long code = Build.VERSION.SDK_INT >= 28 ? apk.getLongVersionCode() : apk.versionCode;
            if (code <= BuildConfig.VERSION_CODE || (expected.latestCode > 0 && code != expected.latestCode)
                    || !cleanVersion(expected.latestVersion).equals(cleanVersion(apk.versionName))) return false;
            Signature[] current = signatures(installed);
            Signature[] update = signatures(apk);
            return current != null && update != null && current.length > 0
                    && new HashSet<>(Arrays.asList(current)).equals(new HashSet<>(Arrays.asList(update)));
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    private static Signature[] signatures(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 && info.signingInfo != null ? info.signingInfo.getApkContentsSigners() : info.signatures;
    }

    private static final class Transfer implements AutoCloseable {
        private final long deadline;
        private final Timer timer = new Timer("jianyu-update-timeout", true);
        private volatile HttpsURLConnection active;
        private volatile boolean expired;

        Transfer(int timeoutMs) {
            deadline = nowMillis() + timeoutMs;
            timer.schedule(new TimerTask() {
                @Override public void run() {
                    expired = true;
                    HttpsURLConnection conn = active;
                    if (conn != null) {
                        Thread thread = new Thread(conn::disconnect, "jianyu-update-disconnect");
                        thread.setDaemon(true);
                        thread.start();
                    }
                }
            }, timeoutMs);
        }

        void check() throws IOException {
            if (expired || nowMillis() >= deadline) throw new IOException("当前下载通道超时，请切换线路或稍后重试。");
        }

        private int remaining(int maximum) throws IOException {
            check();
            return (int) Math.max(1, Math.min(maximum, deadline - nowMillis()));
        }

        HttpsURLConnection open(URL url) throws IOException {
            active = UpdateManager.open(url, remaining(CONNECT_TIMEOUT_MS), remaining(READ_TIMEOUT_MS), false);
            return active;
        }

        int read(InputStream input, byte[] buffer) throws IOException {
            active.setReadTimeout(remaining(READ_TIMEOUT_MS));
            int count = input.read(buffer);
            check();
            return count;
        }

        void release(HttpsURLConnection conn) {
            if (active == conn) active = null;
            conn.disconnect();
        }

        @Override public void close() { timer.cancel(); }
    }

    static boolean installApk(Context context, File apkFile) {
        if (apkFile == null || !apkFile.exists() || apkFile.length() == 0) {
            Toast.makeText(context, "安装包不存在或已损坏，请重新下载。", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(context, "请开启“允许安装未知应用”，授权后返回即可安装。", Toast.LENGTH_LONG).show();
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:" + context.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(settings);
            return false;
        }
        try {
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Toast.makeText(context, "调起系统安装器失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private static String cleanVersion(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("v") || value.startsWith("V")) value = value.substring(1);
        return value.trim();
    }

    static boolean isNewerVersion(String latest, String current) {
        String cleanLatest = cleanVersion(latest);
        String cleanCurrent = cleanVersion(current);
        if (cleanLatest.isEmpty() || cleanLatest.equals(cleanCurrent)) return false;
        String[] lp = cleanLatest.split("\\.");
        String[] cp = cleanCurrent.split("\\.");
        int max = Math.max(lp.length, cp.length);
        for (int i = 0; i < max; i++) {
            int l = parsePart(lp, i);
            int c = parsePart(cp, i);
            if (l > c) return true;
            if (l < c) return false;
        }
        return false;
    }

    private static int parsePart(String[] parts, int index) {
        if (index >= parts.length) return 0;
        String digits = parts[index].replaceAll("[^0-9].*$", "");
        if (digits.isEmpty()) return 0;
        try { return Integer.parseInt(digits); } catch (NumberFormatException e) { return 0; }
    }
}
