package studio.jianyu.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
    }

    private static HttpsURLConnection open(URL url, int connectMs, int readMs, boolean followRedirects) throws IOException {
        if (!"https".equals(url.getProtocol())) throw new IOException("仅支持 HTTPS 下载地址。");
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(followRedirects);
        conn.setConnectTimeout(connectMs);
        conn.setReadTimeout(readMs);
        conn.setRequestProperty("User-Agent", "Jianyu-Android/" + BuildConfig.VERSION_NAME);
        return conn;
    }

    private static String fetchText(String address, String accept) throws IOException {
        URL target = new URL(address);
        for (int i = 0; i < 5; i++) {
            HttpsURLConnection conn = open(target, 10000, 15000, false);
            try {
                conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate");
                conn.setRequestProperty("Pragma", "no-cache");
                if (accept != null) conn.setRequestProperty("Accept", accept);
                int status = conn.getResponseCode();
                if (status >= 300 && status < 400 && conn.getHeaderField("Location") != null) {
                    target = new URL(target, conn.getHeaderField("Location"));
                    continue;
                }
                if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
                try (InputStream in = conn.getInputStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    ImageService.copy(in, out, JSON_LIMIT, "版本信息过大。");
                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            } finally { conn.disconnect(); }
        }
        throw new IOException("重定向过多。");
    }

    static UpdateInfo checkForUpdate() throws IOException {
        String currentVersion = BuildConfig.VERSION_NAME;
        int currentCode = BuildConfig.VERSION_CODE;
        IOException lastError = null;
        UpdateInfo cdn = null, raw = null, probe = null, api = null;

        try {
            cdn = checkViaVersionJson(CDN_VERSION_JSON, currentCode, currentVersion);
            if (cdn.hasUpdate) return cdn;
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
        HttpsURLConnection conn = open(new URL(url), 10000, 10000, false);
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

    static File downloadApk(Context context, String downloadUrl, ProgressListener listener) throws IOException {
        File destDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (destDir == null) destDir = context.getFilesDir();
        if (!destDir.isDirectory() && !destDir.mkdirs()) throw new IOException("无法创建下载目录。");
        File tempFile = new File(destDir, "Jianyu-update.apk.tmp");
        File targetFile = new File(destDir, "Jianyu-update.apk");
        if (tempFile.exists()) tempFile.delete();
        if (targetFile.exists()) targetFile.delete();

        String[] candidates = new String[]{downloadUrl, MIRROR_PREFIX + downloadUrl};
        IOException lastError = null;
        for (String candidate : candidates) {
            try {
                downloadToFile(candidate, tempFile, listener);
                if (isValidApkFile(tempFile)) {
                    if (targetFile.exists()) targetFile.delete();
                    return tempFile.renameTo(targetFile) ? targetFile : tempFile;
                }
                throw new IOException("下载文件校验失败，不是有效的 APK 安装包。");
            } catch (IOException e) {
                lastError = e;
                if (tempFile.exists()) tempFile.delete();
            }
        }
        throw lastError != null ? lastError : new IOException("APK 下载失败，请检查网络连接。");
    }

    private static void downloadToFile(String address, File dest, ProgressListener listener) throws IOException {
        URL target = new URL(address);
        for (int redirects = 0; redirects < 5; redirects++) {
            HttpsURLConnection conn = open(target, 15000, 60000, false);
            try {
                int status = conn.getResponseCode();
                if (status >= 300 && status < 400 && conn.getHeaderField("Location") != null) {
                    target = new URL(target, conn.getHeaderField("Location"));
                    continue;
                }
                if (status != 200) throw new IOException("下载通道响应异常（HTTP " + status + "）");
                long total = conn.getContentLengthLong();
                try (InputStream in = conn.getInputStream();
                     OutputStream out = new FileOutputStream(dest)) {
                    byte[] buffer = new byte[16384];
                    long received = 0;
                    int lastPercent = -1;
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        received += count;
                        if (received > APK_LIMIT) throw new IOException("安装包体积异常。");
                        out.write(buffer, 0, count);
                        if (total > 0 && listener != null) {
                            int percent = (int) Math.min(100, Math.max(0, (received * 100) / total));
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                listener.onProgress(percent);
                            }
                        }
                    }
                    out.flush();
                }
                if (listener != null) listener.onProgress(100);
                return;
            } finally { conn.disconnect(); }
        }
        throw new IOException("下载地址重定向过多。");
    }

    private static boolean isValidApkFile(File file) {
        if (!file.isFile() || file.length() < 1024) return false;
        try (InputStream in = new FileInputStream(file)) {
            return in.read() == 0x50 && in.read() == 0x4B;
        } catch (IOException e) {
            return false;
        }
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
