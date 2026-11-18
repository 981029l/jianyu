package studio.jianyu.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;

final class ImageService {
    static final int IMAGE_LIMIT = 24 * 1024 * 1024;
    private static final int REFERENCE_LIMIT = 4 * 1024 * 1024;
    private final Context context;
    private final SharedPreferences pending;

    ImageService(Context context) {
        this.context = context.getApplicationContext();
        pending = this.context.getSharedPreferences("pending-image", Context.MODE_PRIVATE);
    }

    boolean hasPending() { return pending.contains("task") || pending.contains("url"); }
    void forget() { pending.edit().clear().commit(); }

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
        try (InputStream input = context.getContentResolver().openInputStream(reference);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("无法读取参考图，请重新选择。");
            copy(input, out, REFERENCE_LIMIT, "参考图超过 4 MiB，请选择较小的图片。");
            bytes = out.toByteArray();
        }
        BitmapFactory.Options info = new BitmapFactory.Options();
        info.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, info);
        if (extension(info) == null) throw new IOException("请选择 PNG、JPEG 或 WebP 参考图。");
        return "data:" + info.outMimeType + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    String run(String prompt, String model, String ratio, Uri reference, boolean nsfw) throws Exception {
        String task = pending.getString("task", "");
        String imageUrl = pending.getString("url", "");
        if (hasPending()) prompt = pending.getString("prompt", prompt);
        if (task.isEmpty() && !imageUrl.isEmpty()) return download(imageUrl, prompt);
        boolean submit = task.isEmpty();
        while (true) {
            String endpoint = BuildConfig.IMAGE_ENDPOINT + (submit ? "" : "?task_id=" + Uri.encode(task));
            HttpsURLConnection conn = open(new URL(endpoint), submit ? 1950000 : 60000);
            try {
                conn.setRequestProperty("Accept", "application/json, image/png, image/jpeg, image/webp");
                if (submit) {
                    JSONObject body = new JSONObject().put("prompt", prompt).put("model", model).put("ratio", ratio);
                    if ("Image 2".equals(model)) body.put("nsfw", nsfw);
                    if (reference != null) body.put("image", referenceData(reference));
                    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setFixedLengthStreamingMode(bytes.length);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
                }
                int status = conn.getResponseCode();
                String type = conn.getContentType();
                if (status == 200 && (type == null || !type.toLowerCase(java.util.Locale.ROOT).contains("json"))) {
                    try (InputStream in = conn.getInputStream()) { return save(in, prompt); }
                }
                JSONObject result = new JSONObject();
                try (InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    if (in != null) {
                        copy(in, out, 64 * 1024, "网关返回内容过大。");
                        try { result = new JSONObject(out.toString("UTF-8")); } catch (org.json.JSONException ignored) { }
                    }
                }
                task = result.optString("task_id", task);
                imageUrl = result.optString("image_url");
                if (!task.isEmpty() || !imageUrl.isEmpty()) {
                    SharedPreferences.Editor edit = pending.edit().putString("prompt", prompt);
                    if (!task.isEmpty()) edit.putString("task", task);
                    if (!imageUrl.isEmpty()) edit.putString("url", imageUrl);
                    edit.commit();
                }
                if (status == 200 && !imageUrl.isEmpty()) return download(imageUrl, prompt);
                if (status == 202 && !task.isEmpty()) {
                    if (!submit) throw new IOException("原任务仍在处理中，稍后可继续查询。");
                    submit = false;
                    continue;
                }
                if (status == 410) forget();
                String message = result.optString("error", "").trim();
                if (message.isEmpty()) message = result.optString("detail", "").trim();
                if (message.isEmpty()) message = "网关未返回作品（HTTP " + status + "），请核对任务记录。";
                throw new IOException(message);
            } finally { conn.disconnect(); }
        }
    }

    private String download(String address, String prompt) throws IOException {
        URL target = new URL(address);
        for (int redirects = 0; redirects < 5; redirects++) {
            HttpsURLConnection conn = open(target, 90000);
            try {
                int status = conn.getResponseCode();
                if (status >= 300 && status < 400 && conn.getHeaderField("Location") != null) {
                    target = new URL(target, conn.getHeaderField("Location"));
                    continue;
                }
                if (status != 200) throw new IOException("图片下载失败，可返回创作页继续取图。");
                try (InputStream in = conn.getInputStream()) { return save(in, prompt); }
            } finally { conn.disconnect(); }
        }
        throw new IOException("图片下载地址重定向过多。");
    }

    private static String extension(BitmapFactory.Options info) {
        if (info.outWidth <= 0 || info.outHeight <= 0) return null;
        if ("image/png".equals(info.outMimeType)) return "png";
        if ("image/jpeg".equals(info.outMimeType)) return "jpg";
        if ("image/webp".equals(info.outMimeType)) return "webp";
        return null;
    }

    private String save(InputStream in, String prompt) throws IOException {
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
