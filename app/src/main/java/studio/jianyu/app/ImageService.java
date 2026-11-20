package studio.jianyu.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;

final class ImageService {
    static final int IMAGE_LIMIT = 24 * 1024 * 1024;
    static final String PENDING_PREFS = "pending-image";
    private static final Object KEY_LOCK = new Object();
    private static final int REFERENCE_LIMIT = 4 * 1024 * 1024;
    private static final int JSON_LIMIT = 1024 * 1024;
    private static final int MAX_DETAIL = 300;
    private static final String LINE1_BASE = "https://img.pipiwangcom.com/api/v1";
    private static final String LINE2_BASE = "https://image.mlgb7.com";
    private static final Pattern TASK_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern KEY_SPLIT = Pattern.compile("[\\s,;]+");

    private final Context context;
    private final SharedPreferences pending;
    private final SharedPreferences config;
    private volatile boolean cancelled;
    private volatile HttpsURLConnection activeConn;
    private final Object waitLock = new Object();
    private final GenerationPolling polling;
    private volatile GenerationDownload downloading;
    private java.util.function.IntConsumer progressListener = status -> {};

    void setProgressListener(java.util.function.IntConsumer listener) { progressListener = listener; }
    private void reportProgress(int status) { progressListener.accept(status); }
    boolean isSubmissionUnconfirmed() { return pending.getBoolean("submissionUnconfirmed", false); }
    String gatewayEndpoint() { return BuildConfig.IMAGE_ENDPOINT; }
    HttpsURLConnection connect(URL url, int timeout) throws IOException { return open(url, timeout); }
    long downloadClockMillis() { return SystemClock.elapsedRealtime(); }
    String downloadProgress() {
        GenerationDownload progress = downloading;
        return progress == null ? "正在连接图片服务器…" : progress.progressLabel();
    }

    ImageService(Context context) {
        this(context, "");
    }

    ImageService(Context context, String recordId) {
        this(context, recordId, new GenerationPolling(SystemClock::elapsedRealtime));
    }

    ImageService(Context context, String recordId, GenerationPolling polling) {
        this.polling = polling;
        this.context = context.getApplicationContext();
        String name = PENDING_PREFS + (recordId.isEmpty() ? "" : "-" + recordId);
        pending = this.context.getSharedPreferences(name, Context.MODE_PRIVATE);
        config = this.context.getSharedPreferences("config", Context.MODE_PRIVATE);
    }

    boolean hasPending() { return pending.contains("task") || pending.contains("url"); }
    void forget() { pending.edit().clear().commit(); }

    boolean isCancelled() { return cancelled; }
    void cancelActive() {
        cancelled = true;
        synchronized (waitLock) { waitLock.notifyAll(); }
        disconnectActive();
    }

    private void disconnectActive() {
        HttpsURLConnection conn = activeConn;
        if (conn != null) {
            new Thread(() -> {
                try { conn.disconnect(); } catch (Exception ignored) { }
            }, "jianyu-cancel").start();
        }
    }

    private HttpsURLConnection openTracked(URL url, int timeout) throws IOException {
        if (cancelled) throw new IOException("已取消本次等待。");
        HttpsURLConnection conn = connect(url, timeout);
        activeConn = conn;
        return conn;
    }

    boolean isDirectMode() { return gatewayEndpoint().isEmpty(); }
    boolean isConfigured() { return !isDirectMode() || !activeKeysLine1().isEmpty() || !activeKeysLine2().isEmpty(); }
    boolean isLineConfigured(String model) {
        if (!isDirectMode()) return true;
        return isLine2(model) ? !activeKeysLine2().isEmpty() : !activeKeysLine1().isEmpty();
    }

    static List<String> parseKeys(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return result;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String part : KEY_SPLIT.split(raw.trim())) {
            if (!part.isEmpty()) seen.add(part);
        }
        result.addAll(seen);
        return result;
    }

    List<String> activeKeysLine1() {
        return parseKeys(BuildConfig.IMAGE_API_KEYS);
    }

    List<String> activeKeysLine2() {
        return parseKeys(BuildConfig.IMAGE_API_KEYS_V2);
    }

    private String nextKey(List<String> keys, String cursorKey) {
        synchronized (KEY_LOCK) {
            if (keys.isEmpty()) return "";
            int cursor = Math.max(0, config.getInt(cursorKey, 0));
            String key = keys.get(cursor % keys.size());
            config.edit().putInt(cursorKey, (cursor + 1) % keys.size()).commit();
            return key;
        }
    }

    private static HttpsURLConnection open(URL url, int timeout) throws IOException {
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null) throw new IOException("图片地址须使用 HTTPS。");
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(timeout);
        return conn;
    }

    private String referenceData(Uri reference) throws IOException {
        byte[] bytes;
        try (InputStream input = openUri(reference);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("无法读取参考图，请重新选择。");
            copy(input, out, IMAGE_LIMIT, "参考图超过 24 MiB，请选择较小的图片。");
            bytes = out.toByteArray();
        }
        BitmapFactory.Options info = new BitmapFactory.Options();
        info.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, info);
        if (extension(info) == null) throw new IOException("请选择 PNG、JPEG 或 WebP 参考图。");
        String mime = info.outMimeType;
        if (bytes.length > REFERENCE_LIMIT) {
            // 自动将超过 4 MiB 的本地手机照片降采样并压缩为 JPEG，避免直接拒收
            BitmapFactory.Options dec = new BitmapFactory.Options();
            dec.inSampleSize = 1;
            while ((long) Math.max(info.outWidth, info.outHeight) / dec.inSampleSize > 1800) dec.inSampleSize *= 2;
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, dec);
            if (bmp == null) throw new IOException("无法解码参考图。");
            try {
                byte[] compressed = null;
                for (int q : new int[]{90, 82, 74, 65}) {
                    ByteArrayOutputStream jpegOut = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.JPEG, q, jpegOut);
                    byte[] attempt = jpegOut.toByteArray();
                    compressed = attempt;
                    if (attempt.length <= REFERENCE_LIMIT - 256 * 1024) break;
                }
                if (compressed == null || compressed.length > REFERENCE_LIMIT) {
                    throw new IOException("参考图压缩后仍超过 4 MiB，请更换较小的图片。");
                }
                bytes = compressed;
                mime = "image/jpeg";
            } finally {
                bmp.recycle();
            }
        }
        return "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    /** Android 10+ 零权限一键直存系统相册（Pictures/见屿） */
    boolean saveToSystemGallery(InputStream in, String displayName, String mime) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/见屿");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("无法写入系统相册。");
        boolean ok = false;
        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("无法打开相册输出流。");
            copy(in, out, IMAGE_LIMIT, "作品文件过大。");
            ok = true;
        } finally {
            if (ok) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, done, null, null);
            } else {
                context.getContentResolver().delete(uri, null, null);
            }
        }
        return true;
    }

    void shareImageFile(Context uiContext, File file, String mime) {
        Uri uri = FileProvider.getUriForFile(uiContext, uiContext.getPackageName() + ".fileprovider", file);
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        uiContext.startActivity(Intent.createChooser(share, "分享作品"));
    }

    String artworkMetaSummary(String id) {
        if (id == null || id.isEmpty()) return "内置示例 · JPG";
        File file = new File(new File(context.getFilesDir(), "images"), id);
        if (!file.isFile()) return "";
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        StringBuilder sb = new StringBuilder();
        if (o.outWidth > 0 && o.outHeight > 0) sb.append(o.outWidth).append("×").append(o.outHeight);
        long bytes = file.length();
        if (bytes > 0) {
            if (sb.length() > 0) sb.append(" · ");
            if (bytes >= 1024 * 1024) sb.append(String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0)));
            else sb.append(Math.max(1, bytes / 1024)).append(" KB");
        }
        int dash = id.indexOf('-');
        if (dash > 0) {
            try {
                long ts = Long.parseLong(id.substring(0, dash));
                String dt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(ts));
                if (sb.length() > 0) sb.append(" · ");
                sb.append(dt);
            } catch (Exception ignored) { }
        }
        return sb.toString();
    }

    long cacheSizeBytes() {
        return folderSize(context.getCacheDir());
    }

    void clearAllCaches() {
        deleteChildren(context.getCacheDir());
        InspirationRepository.clearMemoryAndGalleryCache();
    }

    private static long folderSize(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        if (dir.isFile()) return dir.length();
        File[] list = dir.listFiles();
        if (list == null) return 0L;
        long total = 0L;
        for (File f : list) total += folderSize(f);
        return total;
    }

    private static void deleteChildren(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) deleteChildren(f);
            f.delete();
        }
    }

    InputStream openUri(Uri uri) throws IOException {
        if (uri == null) return null;
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return new FileInputStream(new File(uri.getPath()));
        }
        return context.getContentResolver().openInputStream(uri);
    }

    Uri cacheRemoteReference(String address) throws IOException {
        File dir = new File(context.getCacheDir(), "refs");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建参考图缓存目录。");
        File raw = File.createTempFile("remote-", ".tmp", dir);
        try {
            URL target = new URL(address);
            for (int redirects = 0; redirects < 5; redirects++) {
                HttpsURLConnection conn = open(target, 30000);
                try {
                    int status = conn.getResponseCode();
                    if (status >= 300 && status < 400 && conn.getHeaderField("Location") != null) {
                        target = new URL(target, conn.getHeaderField("Location"));
                        continue;
                    }
                    if (status != 200) throw new IOException("无法下载参考图（HTTP " + status + "）。");
                    try (InputStream in = conn.getInputStream();
                         OutputStream out = new FileOutputStream(raw)) {
                        copy(in, out, IMAGE_LIMIT, "图片体积过大。");
                    }
                    break;
                } finally { conn.disconnect(); }
            }
            BitmapFactory.Options info = new BitmapFactory.Options();
            info.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(raw.getPath(), info);
            String ext = extension(info);
            if (ext == null) throw new IOException("图片格式不受支持。");
            if (raw.length() <= REFERENCE_LIMIT) {
                File dest = new File(dir, "ref-" + System.currentTimeMillis() + "." + ext);
                if (!raw.renameTo(dest)) throw new IOException("参考图缓存失败。");
                return Uri.fromFile(dest);
            }
            // 超 4 MiB 时降采样压缩为 JPEG 以满足图改图接口限制
            BitmapFactory.Options dec = new BitmapFactory.Options();
            dec.inSampleSize = 1;
            while ((long) Math.max(info.outWidth, info.outHeight) / dec.inSampleSize > 1600) dec.inSampleSize *= 2;
            android.graphics.Bitmap bmp = BitmapFactory.decodeFile(raw.getPath(), dec);
            if (bmp == null) throw new IOException("无法解码参考图。");
            File dest = new File(dir, "ref-" + System.currentTimeMillis() + ".jpg");
            try (OutputStream out = new FileOutputStream(dest)) {
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out);
            } finally { bmp.recycle(); }
            return Uri.fromFile(dest);
        } finally {
            if (raw.exists()) raw.delete();
        }
    }

    String modelLabel(String id) {
        if (id == null || id.isEmpty()) return "GPT";
        String meta = context.getSharedPreferences("art-meta", Context.MODE_PRIVATE).getString(id, "");
        int sep = meta.indexOf('|');
        return sep > 0 ? meta.substring(0, sep) : "";
    }

    String ratioLabel(String id) {
        if (id == null || id.isEmpty()) return "1:1";
        String meta = context.getSharedPreferences("art-meta", Context.MODE_PRIVATE).getString(id, "");
        int sep = meta.indexOf('|');
        return sep >= 0 && sep + 1 < meta.length() ? meta.substring(sep + 1) : "";
    }

    boolean deleteArtwork(String id) {
        if (id == null || id.isEmpty()) return false;
        File file = new File(new File(context.getFilesDir(), "images"), id);
        boolean ok = !file.exists() || file.delete();
        context.getSharedPreferences("art", Context.MODE_PRIVATE).edit().remove(id).apply();
        context.getSharedPreferences("art-meta", Context.MODE_PRIVATE).edit().remove(id).apply();
        return ok;
    }

    static boolean isLine2(String wireModel) {
        if (wireModel == null) return false;
        return wireModel.startsWith("gpt-image-") || wireModel.startsWith("gpt-") || "GPT-2.5".equalsIgnoreCase(wireModel);
    }

    private static String wireToDisplayModel(String wireModel) {
        if ("image-1".equals(wireModel) || "Image 1".equals(wireModel)) return "GPT";
        if ("image-2".equals(wireModel) || "Image 2".equals(wireModel)) return "Grok";
        if ("gpt-image-2.5".equals(wireModel)) return "GPT-2.5";
        if ("gpt-image-2.5-flare".equals(wireModel)) return "GPT-2.5 Flare";
        if ("gpt-image-2.5-sunburst".equals(wireModel)) return "GPT-2.5 Sunburst";
        if ("gpt-image-2".equals(wireModel)) return "GPT-2";
        return wireModel == null ? "GPT-2.5" : wireModel;
    }

    String run(String prompt, String model, String ratio, Uri reference, boolean nsfw) throws Exception {
        if (cancelled) throw new IOException("已取消本次等待。");
        if (isSubmissionUnconfirmed() && !hasPending())
            throw new IOException("上次提交结果尚未确认，请先核对服务端记录，避免重复生成。");
        reportProgress(hasPending() ? GenerationRecord.STATUS_RUNNING : GenerationRecord.STATUS_SUBMITTING);
        if (!isDirectMode()) return runGateway(prompt, model, ratio, reference, nsfw);
        return runDirect(prompt, model, ratio, reference, nsfw);
    }

    private void markSubmitting() {
        pending.edit().putBoolean("submissionUnconfirmed", true).commit();
    }

    private void acknowledgeResponse(int status) {
        if (status >= 400 && status < 500 && status != 408)
            pending.edit().remove("submissionUnconfirmed").commit();
    }

    private static boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private static int pollingStatus(String state) {
        return "queued".equals(state) || "pending".equals(state)
                ? GenerationRecord.STATUS_QUEUED : GenerationRecord.STATUS_RUNNING;
    }

    void awaitNextPoll(int status) throws IOException {
        reportProgress(status);
        waitForPoll(polling.nextDelayMillis());
        polling.queryTimeoutMillis();
    }

    void waitForPoll(long delayMs) throws IOException {
        long until = SystemClock.elapsedRealtime() + delayMs;
        synchronized (waitLock) {
            while (!cancelled) {
                long remaining = until - SystemClock.elapsedRealtime();
                if (remaining <= 0) return;
                try { waitLock.wait(remaining); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("等待已中断，可稍后继续获取原任务。", e);
                }
            }
        }
        throw new IOException("已停止本次等待。");
    }

    private String runGateway(String prompt, String model, String ratio, Uri reference, boolean nsfw) throws Exception {
        String task = pending.getString("task", "");
        String imageUrl = pending.getString("url", "");
        if (hasPending()) {
            prompt = pending.getString("prompt", prompt);
            model = pending.getString("model", model);
            ratio = pending.getString("ratio", ratio);
        }
        String meta = wireToDisplayModel(model) + "|" + ratio;
        if (!imageUrl.isEmpty()) return download(imageUrl, prompt, meta);
        boolean submit = task.isEmpty();
        while (true) {
            if (cancelled) throw new IOException("已取消本次等待。");
            String endpoint = gatewayEndpoint() + (submit ? "" : "?task_id=" + Uri.encode(task));
            HttpsURLConnection conn = openTracked(new URL(endpoint), submit ? 1950000 : polling.queryTimeoutMillis());
            try {
                conn.setRequestProperty("Accept", "application/json, image/png, image/jpeg, image/webp");
                if (submit) {
                    JSONObject body = new JSONObject().put("prompt", prompt).put("model", model).put("ratio", ratio);
                    if ("Image 2".equals(model) || "image-2".equals(model)) body.put("nsfw", nsfw);
                    if (reference != null) body.put("image", referenceData(reference));
                    markSubmitting();
                    sendJson(conn, body);
                }
                int status = conn.getResponseCode();
                String type = conn.getContentType();
                if (status == 200 && (type == null || !type.toLowerCase(Locale.ROOT).contains("json"))) {
                    try (GenerationDownload progress = beginDownload()) { return saveDownload(conn, progress, prompt, meta); }
                }
                JSONObject result = readJson(conn, status);
                acknowledgeResponse(status);
                task = jsonTaskId(result, task);
                imageUrl = jsonString(result, "image_url");
                savePending(prompt, task, imageUrl, "", model, ratio);
                if (status == 200 && !imageUrl.isEmpty()) return download(imageUrl, prompt, meta);
                if (status == 202 && !task.isEmpty()) {
                    submit = false;
                    conn.disconnect();
                    awaitNextPoll(GenerationRecord.STATUS_RUNNING);
                    continue;
                }
                if (hasPending() && isRetryableStatus(status)) {
                    submit = false;
                    conn.disconnect();
                    awaitNextPoll(GenerationRecord.STATUS_RUNNING);
                    continue;
                }
                if (status == 410) forget();
                String message = jsonString(result, "error");
                if (message.isEmpty()) message = extractDetail(result);
                if (message.isEmpty()) message = "网关未返回作品（HTTP " + status + "），请核对任务记录。";
                throw new IOException(message);
            } finally {
                if (activeConn == conn) activeConn = null;
                conn.disconnect();
            }
        }
    }

    private String runDirect(String prompt, String model, String ratio, Uri reference, boolean nsfw) throws Exception {
        if (hasPending()) {
            model = pending.getString("model", model);
        }
        if (isLine2(model)) {
            return runDirectLine2(prompt, model, ratio, reference);
        } else {
            return runDirectLine1(prompt, model, ratio, reference, nsfw);
        }
    }

    private String runDirectLine1(String prompt, String model, String ratio, Uri reference, boolean nsfw) throws Exception {
        String task = pending.getString("task", "");
        String imageUrl = pending.getString("url", "");
        String taskKey = pending.getString("key", "");
        if (hasPending()) {
            prompt = pending.getString("prompt", prompt);
            model = pending.getString("model", model);
            ratio = pending.getString("ratio", ratio);
        }
        String meta = wireToDisplayModel(model) + "|" + ratio;
        if (!imageUrl.isEmpty()) return download(imageUrl, prompt, meta);
        boolean submit = task.isEmpty();
        while (true) {
            if (cancelled) throw new IOException("已取消本次等待。");
            List<String> keys = activeKeysLine1();
            int status = 502;
            JSONObject result = new JSONObject();
            String usedKey = taskKey;
            if (submit) {
                if (keys.isEmpty()) throw new IOException("请先配置线路 1 密钥。");
                usedKey = nextKey(keys, "key_cursor_line1");
                boolean isGrok = "image-2".equals(model) || "Image 2".equals(model);
                String upstreamModel = isGrok ? "image-2" : "image-1";
                String quality = isGrok ? "1k" : "auto";
                String size = directSize(upstreamModel, ratio);
                JSONObject body = new JSONObject()
                        .put("prompt", prompt)
                        .put("model", upstreamModel)
                        .put("size", size)
                        .put("quality", quality)
                        .put("n", 1)
                        .put("response_format", "url");
                if (isGrok) body.put("nsfw", nsfw);
                String path = "/images/generations";
                if (reference != null) {
                    body.put("image", referenceData(reference));
                    path = "/images/edits";
                }
                HttpsURLConnection conn = openTracked(new URL(LINE1_BASE + path), 1900000);
                try {
                    conn.setRequestProperty("Authorization", "Bearer " + usedKey);
                    conn.setRequestProperty("Accept", "application/json");
                    markSubmitting();
                    sendJson(conn, body);
                    status = conn.getResponseCode();
                    result = readJson(conn, status);
                    acknowledgeResponse(status);
                } finally {
                    if (activeConn == conn) activeConn = null;
                    conn.disconnect();
                }
            } else {
                List<String> candidates = new ArrayList<>();
                if (!taskKey.isEmpty()) candidates.add(taskKey);
                for (String key : keys) {
                    if (!candidates.contains(key)) candidates.add(key);
                }
                if (candidates.isEmpty()) throw new IOException("请先配置线路 1 密钥。");
                for (String candidate : candidates) {
                    if (cancelled) throw new IOException("已取消本次等待。");
                    usedKey = candidate;
                    HttpsURLConnection conn = openTracked(new URL(LINE1_BASE + "/tasks/" + Uri.encode(task)), polling.queryTimeoutMillis());
                    try {
                        conn.setRequestProperty("Authorization", "Bearer " + candidate);
                        conn.setRequestProperty("Accept", "application/json");
                        status = conn.getResponseCode();
                        result = readJson(conn, status);
                        acknowledgeResponse(status);
                    } finally {
                        if (activeConn == conn) activeConn = null;
                        conn.disconnect();
                    }
                    if (status != 401 && status != 403 && status != 404) break;
                }
            }

            JSONArray data = result.optJSONArray("data");
            if (data != null && data.length() > 0) {
                for (int i = 0; i < data.length(); i++) {
                    String url = jsonString(data.optJSONObject(i), "url");
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        savePending(prompt, task, url, usedKey, model, ratio);
                        return download(url, prompt, meta);
                    }
                }
            }
            JSONObject container = result.optJSONObject("data");
            if (container == null) container = result;
            String url = jsonString(container, "url");
            if (url.isEmpty()) url = jsonString(container, "image_url");
            if (url.startsWith("https://") || url.startsWith("http://")) {
                savePending(prompt, task, url, usedKey, model, ratio);
                return download(url, prompt, meta);
            }
            task = jsonTaskId(result, task);
            if (task.isEmpty()) task = jsonTaskId(container, task);
            taskKey = usedKey;
            String detailText = extractDetail(result);
            if (detailText.isEmpty()) detailText = extractDetail(container);
            String state = jsonString(result, "status").toLowerCase(Locale.ROOT);
            if (state.isEmpty()) state = jsonString(container, "status").toLowerCase(Locale.ROOT);
            boolean doneState = "completed".equals(state) || "done".equals(state)
                    || "success".equals(state) || "succeeded".equals(state);
            boolean failed = "failed".equals(state) || "error".equals(state)
                    || "cancelled".equals(state) || "canceled".equals(state)
                    || container.optBoolean("refunded", false)
                    || (container.has("delivered_count") && container.optInt("delivered_count", -1) == 0 && doneState);
            if (failed) {
                forget();
                throw new IOException(formatError("任务未交付图片，请重试。", detailText));
            }
            if (status < 200 || status >= 300) {
                if (status == 410) { forget(); throw new IOException("原任务已失效，请核对任务与额度记录。"); }
                if (!task.isEmpty()) savePending(prompt, task, "", usedKey, model, ratio);
                if (!task.isEmpty() && isRetryableStatus(status)) {
                    submit = false;
                    awaitNextPoll(GenerationRecord.STATUS_RUNNING);
                    continue;
                }
                String base;
                if (status == 400 || status == 422) base = "请检查提示词或参考图。";
                else if (status == 401) base = "请检查线路 1 密钥。";
                else if (status == 403) base = "请检查生图服务账户权限。";
                else if (status == 429) base = "请求过于频繁，请稍后再试。";
                else base = "线路 1 未返回作品，请核对任务记录。";
                throw new IOException(formatError(base, detailText));
            }
            if (!task.isEmpty()) {
                savePending(prompt, task, "", usedKey, model, ratio);
                submit = false;
                awaitNextPoll(pollingStatus(state));
                continue;
            }
            throw new IOException(formatError("结果中缺少可下载的图片地址。", detailText));
        }
    }

    private String runDirectLine2(String prompt, String model, String ratio, Uri reference) throws Exception {
        String task = pending.getString("task", "");
        String imageUrl = pending.getString("url", "");
        String taskKey = pending.getString("key", "");
        if (hasPending()) {
            prompt = pending.getString("prompt", prompt);
            model = pending.getString("model", model);
            ratio = pending.getString("ratio", ratio);
        }
        String meta = wireToDisplayModel(model) + "|" + ratio;
        if (!imageUrl.isEmpty()) return download(imageUrl, prompt, meta);
        boolean submit = task.isEmpty();
        while (true) {
            if (cancelled) throw new IOException("已取消本次等待。");
            List<String> keys = activeKeysLine2();
            int status = 502;
            JSONObject result = new JSONObject();
            String usedKey = taskKey;
            if (submit) {
                if (keys.isEmpty()) throw new IOException("请先配置线路 2 密钥。");
                usedKey = nextKey(keys, "key_cursor_line2");
                if (reference == null) {
                    // 文生图接口：POST /v1/images/generations
                    JSONObject body = new JSONObject()
                            .put("model", model)
                            .put("prompt", prompt)
                            .put("n", 1)
                            .put("async", true)
                            .put("response_format", "url");
                    HttpsURLConnection conn = openTracked(new URL(LINE2_BASE + "/v1/images/generations"), 1900000);
                    try {
                        conn.setRequestProperty("Authorization", "Bearer " + usedKey);
                        conn.setRequestProperty("Accept", "application/json");
                        markSubmitting();
                        sendJson(conn, body);
                        status = conn.getResponseCode();
                        result = readJson(conn, status);
                        acknowledgeResponse(status);
                    } finally {
                        if (activeConn == conn) activeConn = null;
                        conn.disconnect();
                    }
                } else {
                    // 图生图接口：POST /v1/images/edits (multipart/form-data)
                    String boundary = "----JianyuFormBoundary" + System.currentTimeMillis();
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("model", model);
                    fields.put("prompt", prompt);
                    fields.put("n", "1");
                    fields.put("response_format", "url");
                    HttpsURLConnection conn = openTracked(new URL(LINE2_BASE + "/v1/images/edits"), 1900000);
                    try {
                        conn.setRequestProperty("Authorization", "Bearer " + usedKey);
                        conn.setRequestProperty("Accept", "application/json");
                        markSubmitting();
                        sendMultipart(conn, boundary, fields, "image", reference);
                        status = conn.getResponseCode();
                        result = readJson(conn, status);
                        acknowledgeResponse(status);
                    } finally {
                        if (activeConn == conn) activeConn = null;
                        conn.disconnect();
                    }
                }
            } else {
                // 异步任务状态轮询：GET /api/image-tasks?ids={task}
                List<String> candidates = new ArrayList<>();
                if (!taskKey.isEmpty()) candidates.add(taskKey);
                for (String key : keys) {
                    if (!candidates.contains(key)) candidates.add(key);
                }
                if (candidates.isEmpty()) throw new IOException("请先配置线路 2 密钥。");
                for (String candidate : candidates) {
                    if (cancelled) throw new IOException("已取消本次等待。");
                    usedKey = candidate;
                    HttpsURLConnection conn = openTracked(new URL(LINE2_BASE + "/api/image-tasks?ids=" + Uri.encode(task)), polling.queryTimeoutMillis());
                    try {
                        conn.setRequestProperty("Authorization", "Bearer " + candidate);
                        conn.setRequestProperty("Accept", "application/json");
                        status = conn.getResponseCode();
                        result = readJson(conn, status);
                        acknowledgeResponse(status);
                    } finally {
                        if (activeConn == conn) activeConn = null;
                        conn.disconnect();
                    }
                    if (status != 401 && status != 403 && status != 404) break;
                }
            }

            task = jsonTaskId(result, task);
            taskKey = usedKey;
            if (!task.isEmpty()) savePending(prompt, task, "", usedKey, model, ratio);
            String detailText = extractDetail(result);
            if (status < 200 || status >= 300) {
                if (status == 410) { forget(); throw new IOException("原任务已失效，请核对任务与额度记录。"); }
                if (!task.isEmpty() && isRetryableStatus(status)) {
                    submit = false;
                    awaitNextPoll(GenerationRecord.STATUS_RUNNING);
                    continue;
                }
                String base;
                if (status == 400 || status == 422) base = "请检查提示词或参考图。";
                else if (status == 401) base = "请检查线路 2 密钥。";
                else if (status == 403) base = "请检查生图服务账户权限。";
                else if (status == 429) base = "请求过于频繁，请稍后再试。";
                else base = "线路 2 未返回作品，请核对任务记录。";
                throw new IOException(formatError(base, detailText));
            }
            JSONArray directData = result.optJSONArray("data");
            if (directData != null) {
                for (int i = 0; i < directData.length(); i++) {
                    String url = jsonString(directData.optJSONObject(i), "url");
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        savePending(prompt, task, url, usedKey, model, ratio);
                        return download(url, prompt, meta);
                    }
                }
            }
            JSONArray pollItems = result.optJSONArray("items");
            JSONObject item = pollItems != null ? pollItems.optJSONObject(0) : null;
            if (item == null) item = result;
            String state = jsonString(item, "status").toLowerCase(Locale.ROOT);
            if ("failed".equals(state) || "text_review".equals(state) || "error".equals(state)
                    || "cancelled".equals(state) || "canceled".equals(state) || item.optBoolean("refunded", false)) {
                forget();
                String error = jsonString(item, "public_error");
                if (error.isEmpty()) error = jsonString(item, "error_code");
                if (error.isEmpty()) error = "生成未能交付画面，请调整描述后重试。";
                throw new IOException(error);
            }
            JSONArray results = item.optJSONArray("results");
            if (results != null) {
                for (int i = 0; i < results.length(); i++) {
                    String url = jsonString(results.optJSONObject(i), "url");
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        savePending(prompt, task, url, usedKey, model, ratio);
                        return download(url, prompt, meta);
                    }
                }
            }
            String itemId = jsonString(item, "id");
            if (!itemId.isEmpty() && TASK_ID.matcher(itemId).matches()) task = itemId;
            if (!task.isEmpty()) {
                savePending(prompt, task, "", usedKey, model, ratio);
                submit = false;
                awaitNextPoll(pollingStatus(state));
                continue;
            }
            throw new IOException(formatError("结果中缺少可下载的图片地址，请先核对服务端记录。", detailText));
        }
    }

    private void sendMultipart(HttpsURLConnection conn, String boundary,
                               Map<String, String> fields,
                               String fileField, Uri fileUri) throws IOException {
        byte[] lineEnd = "\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] twoDashes = "--".getBytes(StandardCharsets.UTF_8);
        byte[] boundaryBytes = boundary.getBytes(StandardCharsets.UTF_8);

        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setChunkedStreamingMode(32768);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream out = conn.getOutputStream()) {
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                out.write(twoDashes);
                out.write(boundaryBytes);
                out.write(lineEnd);
                String header = "Content-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n";
                out.write(header.getBytes(StandardCharsets.UTF_8));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.write(lineEnd);
            }

            if (fileField != null && fileUri != null) {
                out.write(twoDashes);
                out.write(boundaryBytes);
                out.write(lineEnd);
                String fileName = "reference.png";
                String mimeType = "image/png";
                try (InputStream checkIn = openUri(fileUri)) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(checkIn, null, o);
                    String ext = extension(o);
                    if (ext != null) {
                        fileName = "reference." + ext;
                        if ("jpg".equals(ext)) mimeType = "image/jpeg";
                        else if ("webp".equals(ext)) mimeType = "image/webp";
                    }
                } catch (Exception ignored) { }

                String fileHeader = "Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\"" + fileName + "\"\r\n"
                        + "Content-Type: " + mimeType + "\r\n\r\n";
                out.write(fileHeader.getBytes(StandardCharsets.UTF_8));

                try (InputStream in = openUri(fileUri)) {
                    if (in == null) throw new IOException("无法读取参考图。");
                    byte[] buffer = new byte[16384];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                }
                out.write(lineEnd);
            }

            out.write(twoDashes);
            out.write(boundaryBytes);
            out.write(twoDashes);
            out.write(lineEnd);
            out.flush();
        }
    }

    private void savePending(String prompt, String task, String imageUrl, String key, String model, String ratio) {
        if (task.isEmpty() && imageUrl.isEmpty()) return;
        SharedPreferences.Editor edit = pending.edit()
                .remove("submissionUnconfirmed")
                .putString("prompt", prompt)
                .putString("model", model)
                .putString("ratio", ratio);
        if (!task.isEmpty()) edit.putString("task", task);
        if (!imageUrl.isEmpty()) edit.putString("url", imageUrl);
        if (!key.isEmpty()) edit.putString("key", key);
        edit.commit();
    }

    private static void sendJson(HttpsURLConnection conn, JSONObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(bytes.length);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
    }

    private static JSONObject readJson(HttpsURLConnection conn, int status) throws IOException {
        try (InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) return new JSONObject();
            copy(in, out, JSON_LIMIT, "服务返回内容过大。");
            try { return new JSONObject(out.toString("UTF-8")); } catch (JSONException ignored) { return new JSONObject(); }
        }
    }

    private static String directSize(String upstreamModel, String ratio) {
        if ("image-1".equals(upstreamModel)) {
            if ("3:4".equals(ratio) || "9:16".equals(ratio)) return "1024x1536";
            if ("4:3".equals(ratio) || "16:9".equals(ratio)) return "1536x1024";
            return "1024x1024";
        }
        if ("3:4".equals(ratio)) return "768x1024";
        if ("4:3".equals(ratio)) return "1024x768";
        if ("9:16".equals(ratio)) return "576x1024";
        if ("16:9".equals(ratio)) return "1024x576";
        return "1024x1024";
    }

    private static String jsonString(JSONObject obj, String key) {
        if (obj == null || obj.isNull(key)) return "";
        Object value = obj.opt(key);
        return value instanceof String ? ((String) value).trim() : "";
    }

    private static String extractTaskField(JSONObject obj) {
        if (obj == null || obj.isNull("task_id")) return "";
        Object value = obj.opt("task_id");
        return value instanceof Number || value instanceof String ? String.valueOf(value).trim() : "";
    }

    private static String jsonTaskId(JSONObject obj, String fallback) {
        String id = extractTaskField(obj);
        if (id.isEmpty() && obj != null) id = extractTaskField(obj.optJSONObject("detail"));
        return !id.isEmpty() && TASK_ID.matcher(id).matches() ? id : fallback;
    }

    private static String normalizeText(String text) {
        if (text == null) return "";
        String cleaned = text.trim().replaceAll("\\s+", " ");
        if (cleaned.length() > MAX_DETAIL) return cleaned.substring(0, MAX_DETAIL - 1) + "…";
        return cleaned;
    }

    private static String pickDetail(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof String) return normalizeText((String) value);
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            for (String key : new String[]{"message", "error", "msg", "detail"}) {
                String found = pickDetail(obj.opt(key));
                if (!found.isEmpty()) return found;
            }
            return "";
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder parts = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                String entry;
                if (item instanceof JSONObject) {
                    JSONObject obj = (JSONObject) item;
                    String msg = pickDetail(obj.opt("msg"));
                    if (msg.isEmpty()) msg = pickDetail(obj.opt("message"));
                    if (msg.isEmpty()) msg = pickDetail(obj.opt("error"));
                    JSONArray loc = obj.optJSONArray("loc");
                    String field = loc != null && loc.length() > 0 ? String.valueOf(loc.opt(loc.length() - 1)).trim() : "";
                    entry = !msg.isEmpty() && !field.isEmpty() ? field + ": " + msg : msg;
                } else {
                    entry = pickDetail(item);
                }
                if (!entry.isEmpty()) {
                    if (parts.length() > 0) parts.append('；');
                    parts.append(entry);
                }
            }
            return normalizeText(parts.toString());
        }
        return "";
    }

    private static String extractDetail(JSONObject body) {
        if (body == null) return "";
        for (String key : new String[]{"detail", "message", "error", "result"}) {
            String found = pickDetail(body.opt(key));
            if (!found.isEmpty()) return found;
        }
        return "";
    }

    private static String formatError(String base, String detail) {
        if (detail == null || detail.isEmpty()) return base;
        String prefix = base.endsWith("。") ? base.substring(0, base.length() - 1) : base;
        return prefix + "：" + detail;
    }

    private String download(String address, String prompt, String meta) throws IOException {
        try (GenerationDownload progress = beginDownload()) {
            URL target = new URL(address);
            for (int redirects = 0; redirects < GenerationDownload.MAX_REDIRECTS; redirects++) {
                checkDownload(progress);
                HttpsURLConnection conn = openTracked(target, progress.timeoutMillis());
                try {
                    conn.setConnectTimeout(Math.max(1, Math.min(conn.getConnectTimeout(), progress.timeoutMillis())));
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    int status = conn.getResponseCode();
                    checkDownload(progress);
                    if (status >= 300 && status < 400 && conn.getHeaderField("Location") != null) {
                        target = new URL(target, conn.getHeaderField("Location"));
                        continue;
                    }
                    if (status != 200) throw new IOException("图片已生成，但下载失败（HTTP " + status + "），请在生成记录中继续获取。");
                    return saveDownload(conn, progress, prompt, meta);
                } catch (IOException error) {
                    checkDownload(progress);
                    throw error;
                } finally {
                    if (activeConn == conn) activeConn = null;
                    conn.disconnect();
                }
            }
            throw new IOException("图片下载地址重定向过多。");
        }
    }

    private GenerationDownload beginDownload() {
        GenerationDownload progress = new GenerationDownload(this::downloadClockMillis, this::disconnectActive);
        downloading = progress;
        reportProgress(GenerationRecord.STATUS_DOWNLOADING);
        return progress;
    }

    private void checkDownload(GenerationDownload progress) throws IOException {
        if (cancelled) throw new IOException("已取消本次等待。");
        progress.check();
    }

    private String saveDownload(HttpsURLConnection conn, GenerationDownload progress, String prompt, String meta) throws IOException {
        String encoding = conn.getContentEncoding();
        progress.setTotal(encoding == null || "identity".equalsIgnoreCase(encoding) ? conn.getContentLengthLong() : -1);
        checkDownload(progress);
        conn.setReadTimeout(progress.timeoutMillis());
        try (InputStream in = new FilterInputStream(conn.getInputStream()) {
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                checkDownload(progress);
                conn.setReadTimeout(progress.timeoutMillis());
                int count = super.read(bytes, offset, length);
                progress.addBytes(count);
                checkDownload(progress);
                if (count == -1) progress.finish();
                return count;
            }
        }) {
            return save(in, prompt, meta);
        }
    }

    private static String extension(BitmapFactory.Options info) {
        if (info.outWidth <= 0 || info.outHeight <= 0) return null;
        if ("image/png".equals(info.outMimeType)) return "png";
        if ("image/jpeg".equals(info.outMimeType)) return "jpg";
        if ("image/webp".equals(info.outMimeType)) return "webp";
        return null;
    }

    private String save(InputStream in, String prompt, String meta) throws IOException {
        File dir = new File(context.getFilesDir(), "images");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建作品目录。");
        File tmp = File.createTempFile("pending-", ".tmp", dir);
        try {
            try (OutputStream out = new FileOutputStream(tmp)) { copy(in, out, IMAGE_LIMIT, "图片超过 24 MiB。"); }
            BitmapFactory.Options info = new BitmapFactory.Options();
            info.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getPath(), info);
            String ext = extension(info);
            if (ext == null) throw new IOException("结果不是支持的图片格式。");
            String id = System.currentTimeMillis() + "-" + UUID.randomUUID() + "." + ext;
            if (!tmp.renameTo(new File(dir, id))) throw new IOException("作品保存失败。");
            context.getSharedPreferences("art", Context.MODE_PRIVATE).edit().putString(id, prompt).apply();
            if (meta != null && !meta.isEmpty()) {
                context.getSharedPreferences("art-meta", Context.MODE_PRIVATE).edit().putString(id, meta).apply();
            }
            forget();
            return id;
        } finally { if (tmp.exists()) tmp.delete(); }
    }

    static void copy(InputStream in, OutputStream out, int limit, String error) throws IOException {
        byte[] buffer = new byte[16384];
        int size = 0, count;
        while ((count = in.read(buffer)) != -1) {
            size += count;
            if (size > limit) throw new IOException(error);
            out.write(buffer, 0, count);
        }
    }
}
