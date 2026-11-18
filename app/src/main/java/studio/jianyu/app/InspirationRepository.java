package studio.jianyu.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.LruCache;
import android.widget.ImageView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;

final class InspirationRepository {
    private static final String BASE = "https://img.pipiwangcom.com";
    private static final String PLAZA_URL = BASE + "/api/v1/plaza";
    private static final String GALLERY_CATALOG_URL = BASE + "/releases/prompt-gallery-20260919-r10/data/catalog.json";
    private static final String GALLERY_IMAGE_BASE = BASE + "/prompt-assets/";
    private static final String GALLERY_THUMB_BASE = BASE + "/prompt-thumbs/";
    private static final int JSON_LIMIT = 4 * 1024 * 1024;
    private static final int CATALOG_LIMIT = 20 * 1024 * 1024;
    private static final int IMAGE_LIMIT = 12 * 1024 * 1024;

    static final class Item {
        final String source;
        final String id;
        final String title;
        final String prompt;
        final String thumbUrl;
        final String imageUrl;
        final String major;
        final String category;
        final String model;
        final String ratio;
        final boolean nsfw;
        final String author;

        Item(String source, String id, String title, String prompt, String thumbUrl, String imageUrl,
             String major, String category, String model, String ratio, boolean nsfw, String author) {
            this.source = source;
            this.id = id;
            this.title = title;
            this.prompt = prompt;
            this.thumbUrl = thumbUrl;
            this.imageUrl = imageUrl;
            this.major = major;
            this.category = category;
            this.model = model;
            this.ratio = ratio;
            this.nsfw = nsfw;
            this.author = author;
        }
    }

    static final class PageResult {
        final List<Item> items;
        final boolean hasMore;

        PageResult(List<Item> items, boolean hasMore) {
            this.items = items;
            this.hasMore = hasMore;
        }
    }

    static final class GalleryData {
        final List<Item> items;
        final List<String> majors;

        GalleryData(List<Item> items, List<String> majors) {
            this.items = items;
            this.majors = majors;
        }
    }

    private static volatile GalleryData galleryCache;
    private static final ExecutorService IMAGE_POOL = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> MEMORY_CACHE = new LruCache<String, Bitmap>(
            (int) Math.min(32 * 1024, Math.max(8 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 8))) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    private static HttpsURLConnection open(String address, int timeoutMs) throws IOException {
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol())) throw new IOException("仅支持 HTTPS 地址。");
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", "Jianyu-Android/" + BuildConfig.VERSION_NAME);
        return conn;
    }

    static PageResult fetchPlazaPage(int page, int pageSize) throws IOException {
        String url = PLAZA_URL + "?page=" + page + "&pageSize=" + pageSize + "&sort=hot";
        HttpsURLConnection conn = open(url, 20000);
        try {
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("创意广场加载失败（HTTP " + status + "）");
            InputStream raw = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) raw = new GZIPInputStream(raw);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = raw) {
                ImageService.copy(in, out, JSON_LIMIT, "广场数据过大。");
            }
            JSONObject root = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            JSONArray array = root.optJSONArray("items");
            List<Item> items = new ArrayList<>();
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.optJSONObject(i);
                    if (obj == null) continue;
                    String prompt = optStr(obj, "prompt");
                    String thumb = firstUrl(obj.optJSONArray("thumbs"));
                    if (thumb.isEmpty()) thumb = optStr(obj, "cover");
                    String image = firstUrl(obj.optJSONArray("images"));
                    if (image.isEmpty()) image = thumb;
                    if (thumb.isEmpty()) thumb = image;
                    if (thumb.isEmpty() || prompt.isEmpty()) continue;

                    String rawModel = optStr(obj, "model").toLowerCase(Locale.ROOT);
                    String provider = optStr(obj, "mediaProvider").toLowerCase(Locale.ROOT);
                    String displayModel = (rawModel.contains("2") || provider.contains("grok")) ? "Grok" : "GPT";
                    String ratio = inferRatioFromSize(optStr(obj, "size"));
                    boolean nsfw = obj.optBoolean("nsfw", false);
                    String author = optStr(obj, "nickname");
                    String id = String.valueOf(obj.opt("id"));
                    items.add(new Item("plaza", id, "", prompt, thumb, image,
                            "", "", displayModel, ratio, nsfw, author));
                }
            }
            boolean hasMore = root.optBoolean("hasMore", items.size() >= pageSize);
            return new PageResult(items, hasMore);
        } catch (org.json.JSONException e) {
            throw new IOException("创意广场数据解析失败。");
        } finally {
            conn.disconnect();
        }
    }

    static GalleryData loadGallery(Context context, boolean forceRefresh) throws IOException {
        if (!forceRefresh && galleryCache != null) return galleryCache;
        File cacheFile = new File(context.getCacheDir(), "prompt-gallery-20260919-r10.json");
        if (forceRefresh || !cacheFile.isFile() || cacheFile.length() < 1024) {
            File tmp = File.createTempFile("catalog-", ".tmp", context.getCacheDir());
            try {
                HttpsURLConnection conn = open(GALLERY_CATALOG_URL, 30000);
                try {
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setRequestProperty("Accept-Encoding", "gzip");
                    int status = conn.getResponseCode();
                    if (status < 200 || status >= 300) throw new IOException("提示词画廊加载失败（HTTP " + status + "）");
                    InputStream raw = conn.getInputStream();
                    if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) raw = new GZIPInputStream(raw);
                    try (InputStream in = raw; OutputStream out = new FileOutputStream(tmp)) {
                        ImageService.copy(in, out, CATALOG_LIMIT, "提示词画廊数据过大。");
                    }
                } finally { conn.disconnect(); }
                if (cacheFile.exists()) cacheFile.delete();
                if (!tmp.renameTo(cacheFile)) throw new IOException("提示词画廊缓存失败。");
            } finally {
                if (tmp.exists()) tmp.delete();
            }
        }
        GalleryData parsed = parseGalleryStream(cacheFile);
        galleryCache = parsed;
        return parsed;
    }

    private static GalleryData parseGalleryStream(File file) throws IOException {
        List<Item> items = new ArrayList<>(5600);
        Map<String, Integer> majorCounts = new LinkedHashMap<>();
        try (JsonReader reader = new JsonReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            reader.beginArray();
            while (reader.hasNext()) {
                String id = "", title = "", major = "", category = "", prompt = "", image = "", thumb = "";
                int width = 0, height = 0;
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull();
                        continue;
                    }
                    switch (name) {
                        case "id":
                            id = reader.peek() == JsonToken.NUMBER ? String.valueOf(reader.nextLong()) : reader.nextString().trim();
                            break;
                        case "title": title = reader.nextString().trim(); break;
                        case "major": major = reader.nextString().trim(); break;
                        case "category": category = reader.nextString().trim(); break;
                        case "prompt": prompt = reader.nextString().trim(); break;
                        case "image": image = reader.nextString().trim(); break;
                        case "thumb": thumb = reader.nextString().trim(); break;
                        case "width":
                            if (reader.peek() == JsonToken.NUMBER) width = (int) Math.round(reader.nextDouble());
                            else reader.skipValue();
                            break;
                        case "height":
                            if (reader.peek() == JsonToken.NUMBER) height = (int) Math.round(reader.nextDouble());
                            else reader.skipValue();
                            break;
                        default: reader.skipValue(); break;
                    }
                }
                reader.endObject();
                if (prompt.isEmpty() || (thumb.isEmpty() && image.isEmpty())) continue;
                String fullThumb = thumb.startsWith("https://") ? thumb : GALLERY_THUMB_BASE + (thumb.isEmpty() ? image : thumb);
                String fullImage = image.startsWith("https://") ? image : GALLERY_IMAGE_BASE + (image.isEmpty() ? thumb : image);
                String ratio = inferRatio(width, height);
                items.add(new Item("gallery", id, title, prompt, fullThumb, fullImage,
                        major, category, "", ratio, false, ""));
                if (!major.isEmpty()) {
                    Integer count = majorCounts.get(major);
                    majorCounts.put(major, count == null ? 1 : count + 1);
                }
            }
            reader.endArray();
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(majorCounts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> majors = new ArrayList<>();
        majors.add("全部");
        for (Map.Entry<String, Integer> entry : sorted) majors.add(entry.getKey());
        return new GalleryData(items, majors);
    }

    static List<Item> filterGallery(GalleryData data, String major, String query) {
        if (data == null) return Collections.emptyList();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        boolean allMajor = major == null || major.isEmpty() || "全部".equals(major);
        if (allMajor && q.isEmpty()) return data.items;
        List<Item> out = new ArrayList<>();
        for (Item item : data.items) {
            if (!allMajor && !major.equals(item.major)) continue;
            if (!q.isEmpty()) {
                boolean hit = item.title.toLowerCase(Locale.ROOT).contains(q)
                        || item.category.toLowerCase(Locale.ROOT).contains(q)
                        || item.major.toLowerCase(Locale.ROOT).contains(q)
                        || item.prompt.toLowerCase(Locale.ROOT).contains(q);
                if (!hit) continue;
            }
            out.add(item);
        }
        return out;
    }

    static void loadImage(Context context, ImageView target, String url, int maxDim) {
        if (target == null || url == null || url.isEmpty()) return;
        String cacheKey = maxDim + ":" + url;
        target.setTag(cacheKey);
        Bitmap cached = MEMORY_CACHE.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            target.setImageBitmap(cached);
            return;
        }
        target.setImageDrawable(null);
        Context appContext = context.getApplicationContext();
        IMAGE_POOL.execute(() -> {
            Bitmap bitmap = fetchBitmap(appContext, url, maxDim);
            if (bitmap != null) {
                MEMORY_CACHE.put(cacheKey, bitmap);
                MAIN.post(() -> {
                    if (cacheKey.equals(target.getTag())) {
                        target.setImageBitmap(bitmap);
                    }
                });
            }
        });
    }

    static void loadLocalThumb(ImageView target, File file, int maxDim) {
        if (target == null || file == null || !file.isFile()) return;
        String cacheKey = "local:" + maxDim + ":" + file.getPath();
        target.setTag(cacheKey);
        Bitmap cached = MEMORY_CACHE.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            target.setImageBitmap(cached);
            return;
        }
        target.setImageDrawable(null);
        IMAGE_POOL.execute(() -> {
            Bitmap bitmap = decodeSampled(file, maxDim);
            if (bitmap != null) {
                MEMORY_CACHE.put(cacheKey, bitmap);
                MAIN.post(() -> {
                    if (cacheKey.equals(target.getTag())) {
                        target.setImageBitmap(bitmap);
                    }
                });
            }
        });
    }

    static File ensureCachedImage(Context context, String url) throws IOException {
        File dir = new File(context.getCacheDir(), "img-cache");
        if (!dir.isDirectory()) dir.mkdirs();
        File file = new File(dir, sha1(url) + ".img");
        if (file.isFile() && file.length() > 128) return file;
        File tmp = File.createTempFile("img-", ".tmp", dir);
        try {
            URL target = new URL(url);
            for (int i = 0; i < 5; i++) {
                HttpsURLConnection conn = open(target.toString(), 25000);
                try {
                    int status = conn.getResponseCode();
                    if (status >= 300 && status < 400 && conn.getHeaderField("Location") != null) {
                        target = new URL(target, conn.getHeaderField("Location"));
                        continue;
                    }
                    if (status != 200) throw new IOException("图片加载失败（HTTP " + status + "）");
                    try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                        ImageService.copy(in, out, IMAGE_LIMIT, "图片体积过大。");
                    }
                    break;
                } finally { conn.disconnect(); }
            }
            if (!tmp.renameTo(file)) {
                if (file.isFile() && file.length() > 128) return file;
                throw new IOException("图片缓存失败。");
            }
            return file;
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    private static Bitmap fetchBitmap(Context context, String url, int maxDim) {
        try {
            File file = ensureCachedImage(context, url);
            return decodeSampled(file, maxDim);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Bitmap decodeSampled(File file, int maxDim) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inSampleSize = 1;
        while ((long) Math.max(o.outWidth, o.outHeight) / o.inSampleSize > maxDim) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(file.getPath(), o);
    }

    private static String sha1(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    private static String optStr(JSONObject obj, String key) {
        if (obj == null || obj.isNull(key)) return "";
        Object value = obj.opt(key);
        return value instanceof String ? ((String) value).trim() : "";
    }

    private static String firstUrl(JSONArray array) {
        if (array == null) return "";
        for (int i = 0; i < array.length(); i++) {
            String v = array.optString(i, "").trim();
            if (v.startsWith("https://")) return v;
        }
        return "";
    }

    private static String inferRatioFromSize(String size) {
        if (size == null || !size.contains("x")) return "1:1";
        String[] parts = size.toLowerCase(Locale.ROOT).split("x");
        if (parts.length != 2) return "1:1";
        try {
            return inferRatio(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
        } catch (NumberFormatException e) {
            return "1:1";
        }
    }

    private static String inferRatio(int w, int h) {
        if (w <= 0 || h <= 0) return "1:1";
        double r = (double) w / h;
        double[] targets = {1.0, 3.0 / 4.0, 4.0 / 3.0, 9.0 / 16.0, 16.0 / 9.0};
        String[] names = {"1:1", "3:4", "4:3", "9:16", "16:9"};
        int best = 0;
        double diff = Double.MAX_VALUE;
        for (int i = 0; i < targets.length; i++) {
            double d = Math.abs(r - targets[i]);
            if (d < diff) { diff = d; best = i; }
        }
        return names[best];
    }
}
