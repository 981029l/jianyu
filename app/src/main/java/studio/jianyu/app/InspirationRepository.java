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
    private static final String AIART_API_BASE = "https://aiart.pics/api/prompts";
    private static final String AIART_IMG_BASE = "https://img1.aiart.pics/";
    private static final String OPENNANA_API_BASE = "https://api.opennana.com/api/prompts";
    private static final String LEADERAI_INDEX_URL = "https://cdn.leaderai.top/oss/moban-image/image_prompt/imgs_index.json";
    private static final String LEADERAI_IMG_BASE = "https://cdn.leaderai.top/oss/moban-image/image_prompt/imgs_src/";
    private static final int JSON_LIMIT = 4 * 1024 * 1024;
    private static final int CATALOG_LIMIT = 20 * 1024 * 1024;
    private static final int LEADERAI_LIMIT = 25 * 1024 * 1024;
    private static final int IMAGE_LIMIT = 12 * 1024 * 1024;
    private static final String FAV_PREFS = "inspire-favs";
    private static final String FAV_KEY = "items";

    static final class Item {
        final String source;
        final String id;
        final String title;
        volatile String prompt;
        volatile boolean detailLoaded;
        final String thumbUrl;
        volatile String imageUrl;
        final String refUrl;
        final String major;
        final String rawMajor;
        volatile String category;
        volatile String model;
        final String ratio;
        final boolean nsfw;
        volatile String author;
        final int width;
        final int height;

        Item(String source, String id, String title, String prompt, String thumbUrl, String imageUrl,
             String major, String rawMajor, String category, String model, String ratio,
             boolean nsfw, String author, int width, int height) {
            this(source, id, title, prompt, thumbUrl, imageUrl, "", major, rawMajor, category, model, ratio, nsfw, author, width, height);
        }

        Item(String source, String id, String title, String prompt, String thumbUrl, String imageUrl, String refUrl,
             String major, String rawMajor, String category, String model, String ratio,
             boolean nsfw, String author, int width, int height) {
            this.source = source;
            this.id = id;
            this.title = isDiscover() ? ChinesePrompts.chineseBlocks(title) : title;
            this.prompt = isDiscover() ? ChinesePrompts.chineseBlocks(prompt) : prompt == null ? "" : prompt;
            this.thumbUrl = thumbUrl;
            this.imageUrl = imageUrl == null ? "" : imageUrl;
            this.refUrl = refUrl == null ? "" : refUrl;
            this.major = isDiscover() ? "发现灵感" : major;
            this.rawMajor = isDiscover() ? "发现灵感" : rawMajor;
            this.category = isDiscover() ? "精选灵感" : category == null ? "" : category;
            this.model = model == null ? "" : model;
            this.ratio = ratio;
            this.nsfw = nsfw;
            this.author = author == null ? "" : author;
            this.width = width;
            this.height = height;
        }

        String uniqueKey() { return source + ":" + id; }

        boolean isDiscover() { return "aiart".equals(source) || "opennana".equals(source); }

        boolean needsDetailFetch() {
            return isDiscover() && !detailLoaded;
        }

        String displayTitle() { return title.isEmpty() ? "精选灵感" : title; }

        String displayPrompt() { return prompt.isEmpty() && isDiscover() ? "这条灵感暂未提供中文提示词" : prompt; }

        synchronized void applyDetail(String fullPrompt, String fullImageUrl, String detailModel, String detailAuthor, String detailCategory) {
            if (fullPrompt != null) this.prompt = fullPrompt;
            detailLoaded = true;
            if (fullImageUrl != null && !fullImageUrl.isEmpty()) this.imageUrl = fullImageUrl;
            if (detailModel != null && !detailModel.isEmpty()) this.model = detailModel;
            if (detailAuthor != null && !detailAuthor.isEmpty()) this.author = detailAuthor;
            if (!isDiscover() && detailCategory != null && !detailCategory.isEmpty()) this.category = detailCategory;
        }

        float aspectHeightOverWidth() {
            if (width > 0 && height > 0) {
                float r = (float) height / (float) width;
                return Math.max(0.65f, Math.min(1.50f, r));
            }
            if ("3:4".equals(ratio)) return 1.33f;
            if ("4:3".equals(ratio)) return 0.78f;
            if ("9:16".equals(ratio)) return 1.48f;
            if ("16:9".equals(ratio)) return 0.66f;
            return 1.0f;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("source", source);
                o.put("id", id);
                o.put("title", title);
                o.put("prompt", prompt);
                o.put("chineseDetail", isDiscover() && detailLoaded);
                o.put("thumbUrl", thumbUrl);
                o.put("imageUrl", imageUrl);
                o.put("refUrl", refUrl);
                o.put("major", major);
                o.put("rawMajor", rawMajor);
                o.put("category", category);
                o.put("model", model);
                o.put("ratio", ratio);
                o.put("nsfw", nsfw);
                o.put("author", author);
                o.put("width", width);
                o.put("height", height);
            } catch (Exception ignored) { }
            return o;
        }

        static Item fromJson(JSONObject o) {
            if (o == null) return null;
            Item item = new Item(
                    o.optString("source", "gallery"),
                    o.optString("id", ""),
                    o.optString("title", ""),
                    o.optString("prompt", ""),
                    o.optString("thumbUrl", ""),
                    o.optString("imageUrl", ""),
                    o.optString("refUrl", ""),
                    o.optString("major", ""),
                    o.optString("rawMajor", ""),
                    o.optString("category", ""),
                    o.optString("model", ""),
                    o.optString("ratio", "1:1"),
                    o.optBoolean("nsfw", false),
                    o.optString("author", ""),
                    o.optInt("width", 1024),
                    o.optInt("height", 1024));
            if (item.isDiscover() && o.optBoolean("chineseDetail", false)) {
                item.prompt = o.optString("prompt", "");
                item.detailLoaded = true;
            }
            return item;
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
        final List<Item> uniqueAllItems;
        final List<String> majors;
        final Map<String, List<String>> sourceMajors;
        final String hotError;
        GalleryData(List<Item> items, List<Item> uniqueAllItems, List<String> majors,
                    Map<String, List<String>> sourceMajors, String hotError) {
            this.items = items;
            this.uniqueAllItems = uniqueAllItems;
            this.majors = majors;
            this.sourceMajors = sourceMajors;
            this.hotError = hotError == null ? "" : hotError;
        }

        List<String> getMajors(String sourceFilter) {
            if (sourceFilter == null || sourceFilter.isEmpty() || "all".equals(sourceFilter)) {
                return majors;
            }
            List<String> list = sourceMajors.get(sourceFilter);
            return list != null ? list : majors;
        }
    }

    private static volatile GalleryData galleryCache;
    private static final ExecutorService IMAGE_POOL = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> MEMORY_CACHE = new LruCache<String, Bitmap>(
            (int) Math.min(36 * 1024, Math.max(8 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 7))) {
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

    /** 清洗画廊原始脏分类名，保持艺术画廊审美统一 */
    static String sanitizeMajor(String raw) {
        if (raw == null) return "综合精选";
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.contains("未归类") || "其他".equals(trimmed)) return "综合精选";
        if (trimmed.contains("大凶")) return "风格人像";
        if ("晓兰".equals(trimmed)) return "精选合集";
        return trimmed;
    }

    private static boolean containsAny(String str, String... keywords) {
        if (str == null || str.isEmpty()) return false;
        for (String kw : keywords) {
            if (str.contains(kw)) return true;
        }
        return false;
    }

    /** 将零散子分类归一化为一级大分类（Umbrella Major），彻底解决分类筛选 0 条问题 */
    static String resolveUmbrellaMajor(String rawMajor, String category) {
        String raw = rawMajor == null ? "" : rawMajor.trim();
        String cat = category == null ? "" : category.trim();
        String combined = raw + " " + cat;
        if (containsAny(combined, "动漫", "二次元", "日漫", "角色板", "三视图", "漫画")) {
            return "动漫二次元";
        }
        if (containsAny(combined, "大凶", "诱惑", "美甲", "腿比命长")) {
            return "风格人像";
        }
        if (containsAny(combined, "杂志", "纯人物", "证件照", "写真", "古风女生", "合照", "时尚摄影", "人物背景", "男性", "多人合照")) {
            return "人物写真";
        }
        if (containsAny(combined, "海报", "9宫格", "电影封面", "封面")) {
            return "海报广告";
        }
        if (containsAny(combined, "插画", "手绘", "剪纸", "涂鸦", "拆解", "书画", "水彩", "油画", "治愈风格", "中国古代", "复古报纸", "构成主义", "撕纸照片框", "手写体")) {
            return "插画艺术";
        }
        if (containsAny(combined, "产品", "电商", "冰箱贴", "餐饮产品", "美食", "电子产品", "女性产品")) {
            return "产品电商";
        }
        if (containsAny(combined, "车", "机械", "3D", "汽车")) {
            return "车辆机械3D";
        }
        if (containsAny(combined, "游戏", "原神", "英雄联盟", "高达", "卡牌")) {
            return "游戏";
        }
        if (containsAny(combined, "建筑", "空间", "室内")) {
            return "建筑空间场景";
        }
        if (combined.contains("随手拍")) {
            return "随手拍";
        }
        if (raw.startsWith("New")) {
            return raw;
        }
        if (raw.contains("晓兰")) {
            return "精选合集";
        }
        return raw.isEmpty() ? "其他/未归类" : sanitizeMajor(raw);
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
                    int[] dims = parseSize(optStr(obj, "size"));
                    String ratio = inferRatio(dims[0], dims[1]);
                    boolean nsfw = obj.optBoolean("nsfw", false);
                    String author = optStr(obj, "nickname");
                    String id = String.valueOf(obj.opt("id"));
                    items.add(new Item("plaza", id, "", prompt, thumb, image,
                            "", "", "", displayModel, ratio, nsfw, author, dims[0], dims[1]));
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

    // ==================== 发现灵感（aiart.pics + opennana.com 在线分页拉取） ====================

    static PageResult fetchDiscoverPage(String query, int page, int pageSize) throws IOException {
        String q = query == null ? "" : query.trim();
        int half = Math.max(6, pageSize / 2);
        final PageResult[] results = new PageResult[2];
        final IOException[] errors = new IOException[2];
        Thread t1 = new Thread(() -> {
            try {
                results[0] = fetchAiArtPage(q, page, half);
            } catch (IOException e) {
                errors[0] = e;
            }
        }, "discover-aiart");
        Thread t2 = new Thread(() -> {
            try {
                results[1] = fetchOpenNanaPage(q, page, half);
            } catch (IOException e) {
                errors[1] = e;
            }
        }, "discover-opennana");
        t1.start();
        t2.start();
        try {
            t1.join(22000);
            t2.join(22000);
        } catch (InterruptedException ignored) { }

        if (results[0] == null && results[1] == null) {
            if (errors[0] != null) throw errors[0];
            if (errors[1] != null) throw errors[1];
            throw new IOException("发现灵感加载失败，请检查网络。");
        }
        List<Item> aList = results[0] != null ? results[0].items : Collections.emptyList();
        List<Item> oList = results[1] != null ? results[1].items : Collections.emptyList();
        List<Item> merged = new ArrayList<>(aList.size() + oList.size());
        int maxLen = Math.max(aList.size(), oList.size());
        for (int i = 0; i < maxLen; i++) {
            if (i < aList.size()) merged.add(aList.get(i));
            if (i < oList.size()) merged.add(oList.get(i));
        }
        boolean hasMore = (results[0] != null && results[0].hasMore) || (results[1] != null && results[1].hasMore);
        return new PageResult(merged, hasMore);
    }

    private static PageResult fetchAiArtPage(String query, int page, int limit) throws IOException {
        int offset = Math.max(0, (page - 1) * limit);
        StringBuilder url = new StringBuilder(AIART_API_BASE)
                .append("?sort=newest&mediaType=image&limit=").append(limit)
                .append("&offset=").append(offset);
        if (query != null && !query.isEmpty()) {
            url.append("&search=").append(java.net.URLEncoder.encode(query, "UTF-8"));
        }
        JSONObject root = getJson(url.toString(), "https://aiart.pics/");
        JSONArray arr = root.optJSONArray("prompts");
        List<Item> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String id = optStr(obj, "id");
                if (id.isEmpty()) continue;

                String title = "";
                JSONObject titleObj = obj.optJSONObject("title");
                if (titleObj != null) {
                    title = optStr(titleObj, "zh");
                } else {
                    title = optStr(obj, "title");
                }

                String thumbUrl = "";
                String imageUrl = "";
                int width = 1024, height = 1024;
                JSONArray imgs = obj.optJSONArray("images");
                if (imgs != null && imgs.length() > 0) {
                    JSONObject img0 = imgs.optJSONObject(0);
                    if (img0 != null) {
                        String path = optStr(img0, "path");
                        String sPath = optStr(img0, "sPath");
                        if (sPath.isEmpty()) sPath = path;
                        if (path.isEmpty()) path = sPath;
                        thumbUrl = toAiArtImgUrl(sPath);
                        imageUrl = toAiArtImgUrl(path);
                        int w = img0.optInt("width", 0);
                        int h = img0.optInt("height", 0);
                        if (w > 0 && h > 0) {
                            width = w;
                            height = h;
                        }
                    }
                }
                if (thumbUrl.isEmpty() && imageUrl.isEmpty()) continue;

                String rawModel = optStr(obj, "model");
                String displayModel = normalizeDiscoverModel(rawModel);
                String category = "精选灵感";
                String author = "";
                JSONObject authorObj = obj.optJSONObject("author");
                if (authorObj != null) {
                    author = optStr(authorObj, "name");
                    if (author.isEmpty()) author = optStr(authorObj, "username");
                }
                String ratio = inferRatio(width, height);
                out.add(new Item("aiart", id, title, "", thumbUrl, imageUrl,
                        "发现灵感", "发现灵感", category, displayModel, ratio, false, author, width, height));
            }
        }
        int total = root.optInt("total", 0);
        boolean hasMore = (offset + out.size() < total) && !out.isEmpty();
        return new PageResult(out, hasMore);
    }

    private static PageResult fetchOpenNanaPage(String query, int page, int limit) throws IOException {
        StringBuilder url = new StringBuilder(OPENNANA_API_BASE)
                .append("?page=").append(Math.max(1, page))
                .append("&limit=").append(limit)
                .append("&sort=reviewed_at&order=DESC&media_type=image&access_type=0");
        if (query != null && !query.isEmpty()) {
            url.append("&search=").append(java.net.URLEncoder.encode(query, "UTF-8"));
        }
        JSONObject root = getJson(url.toString(), "https://opennana.com/");
        JSONObject data = root.optJSONObject("data");
        if (data == null) return new PageResult(Collections.emptyList(), false);
        JSONArray arr = data.optJSONArray("items");
        List<Item> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String slug = optStr(obj, "slug");
                if (slug.isEmpty()) slug = String.valueOf(obj.opt("id"));
                if (slug.isEmpty()) continue;

                String title = optStr(obj, "title");
                String thumbUrl = optStr(obj, "cover_image");
                if (thumbUrl.isEmpty()) continue;
                String imageUrl = deriveOpenNanaFullUrl(thumbUrl);

                out.add(new Item("opennana", slug, title, "", thumbUrl, imageUrl,
                        "发现灵感", "发现灵感", "精选灵感", "GPT", "1:1", false, "", 1024, 1024));
            }
        }
        JSONObject pag = data.optJSONObject("pagination");
        int totalPages = pag != null ? pag.optInt("total_pages", page) : page;
        boolean hasMore = page < totalPages && !out.isEmpty();
        return new PageResult(out, hasMore);
    }

    static String fetchDiscoverDetail(Context context, Item item) throws IOException {
        if (item == null) return "";
        synchronized (item) {
            if (!item.needsDetailFetch()) return item.prompt;
            boolean aiart = "aiart".equals(item.source);
            String url = (aiart ? AIART_API_BASE : OPENNANA_API_BASE) + "/" + android.net.Uri.encode(item.id);
            JSONObject root = getJson(url, aiart ? "https://aiart.pics/" : "https://opennana.com/");
            JSONObject data = root.optJSONObject("data");
            if (data == null) throw new IOException("暂时无法获取中文提示词，请稍后重试。");
            String fullPrompt = ChinesePrompts.select(data.optJSONArray("prompts"));
            String fullImage = aiart ? "" : firstUrl(data.optJSONArray("images"));
            String model = normalizeDiscoverModel(optStr(data, "model"));
            String author = aiart ? "" : optStr(data, "source_name");
            item.applyDetail(fullPrompt, fullImage, model, author, "精选灵感");
            if (context != null) updateFavoriteIfPresent(context, item);
            return item.prompt;
        }
    }

    private static JSONObject getJson(String url, String referer) throws IOException {
        HttpsURLConnection conn = open(url, 20000);
        try {
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("在线灵感加载失败（HTTP " + status + "）");
            }
            InputStream raw = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) raw = new GZIPInputStream(raw);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = raw) {
                ImageService.copy(in, out, JSON_LIMIT, "灵感数据过大。");
            }
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException("在线灵感数据解析失败。");
        } finally {
            conn.disconnect();
        }
    }

    private static String toAiArtImgUrl(String path) {
        if (path == null || path.isEmpty()) return "";
        if (path.startsWith("https://") || path.startsWith("http://")) return path;
        return AIART_IMG_BASE + (path.startsWith("/") ? path.substring(1) : path);
    }

    private static String deriveOpenNanaFullUrl(String coverUrl) {
        if (coverUrl == null || coverUrl.isEmpty()) return "";
        String full = coverUrl.replace("/pthumbs/", "/");
        return full.replaceFirst("-480(\\.[a-zA-Z0-9]+)$", "$1");
    }

    private static String normalizeDiscoverModel(String rawModel) {
        if (rawModel == null || rawModel.isEmpty()) return "GPT";
        String lower = rawModel.toLowerCase(Locale.ROOT);
        if (lower.contains("grok")) return "Grok";
        return "GPT";
    }

    static GalleryData loadGallery(Context context, boolean forceRefresh) throws IOException {
        if (!forceRefresh && galleryCache != null) return galleryCache;

        List<Item> items = new ArrayList<>(28000);
        Map<String, Map<String, Integer>> sourceMajorCounts = new HashMap<>();
        sourceMajorCounts.put("hot", new LinkedHashMap<>());
        sourceMajorCounts.put("addon", new LinkedHashMap<>());
        sourceMajorCounts.put("gallery", new LinkedHashMap<>());
        Set<String> seenIds = new HashSet<>(28000);

        // 1. 本地内置 6,601 条全网热榜提示词库（已按热度点赞降序排列，秒级离线直开）
        String hotError = "";
        try (InputStream in = context.getAssets().open("prompts_hot.json")) {
            parseItemsFromReader(new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8)), items, sourceMajorCounts.get("hot"), seenIds, "hot");
        } catch (Exception e1) {
            hotError = e1.getClass().getSimpleName() + ": " + e1.getMessage();
            try (InputStream raw = context.getAssets().open("prompts_hot.json.gz");
                 InputStream in = new GZIPInputStream(raw)) {
                parseItemsFromReader(new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8)), items, sourceMajorCounts.get("hot"), seenIds, "hot");
                hotError = "";
            } catch (Exception ignored) { }
        }

        // 2. 设计精选库：本地内置 1,740 条设计提示词 + 立得设计 CDN 9,811 条全量静态模版（共 11,550+ 条）
        try (InputStream in = context.getAssets().open("prompts_addon.json")) {
            parseItemsFromReader(new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8)), items, sourceMajorCounts.get("addon"), seenIds, "addon");
        } catch (Exception ignored) { }
        loadLeaderAiIndex(context, forceRefresh, items, sourceMajorCounts.get("addon"), seenIds);

        // 3. 经典画廊：尝试从本地磁盘缓存或远程下载 5,400+ 条经典画廊
        File cacheFile = new File(context.getCacheDir(), "prompt-gallery-20260919-r10.json");
        try {
            if (forceRefresh || !cacheFile.isFile() || cacheFile.length() < 1024) {
                File tmp = File.createTempFile("catalog-", ".tmp", context.getCacheDir());
                try {
                    HttpsURLConnection conn = open(GALLERY_CATALOG_URL, 30000);
                    try {
                        conn.setRequestProperty("Accept", "application/json");
                        conn.setRequestProperty("Accept-Encoding", "gzip");
                        int status = conn.getResponseCode();
                        if (status >= 200 && status < 300) {
                            InputStream raw = conn.getInputStream();
                            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) raw = new GZIPInputStream(raw);
                            try (InputStream in = raw; OutputStream out = new FileOutputStream(tmp)) {
                                ImageService.copy(in, out, CATALOG_LIMIT, "提示词画廊数据过大。");
                            }
                            if (cacheFile.exists()) cacheFile.delete();
                            tmp.renameTo(cacheFile);
                        }
                    } finally { conn.disconnect(); }
                } finally {
                    if (tmp.exists()) tmp.delete();
                }
            }

            if (cacheFile.isFile() && cacheFile.length() >= 1024) {
                try (InputStream in = new FileInputStream(cacheFile)) {
                    parseItemsFromReader(new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8)), items, sourceMajorCounts.get("gallery"), seenIds, "gallery");
                }
            }
        } catch (Exception e) {
            // 如果在线画廊下载失败，但已成功载入内置词库，则优雅降级展示已有词库
            if (items.isEmpty()) throw new IOException("提示词画廊加载失败，请检查网络。");
        }

        if (items.isEmpty()) throw new IOException("暂无可用的提示词画廊数据。");

        // 构建「全部」视图去重列表：热榜(6,601)与经典画廊(5,443)同源重合 5,421 条，全部视图自动按 id 去重
        List<Item> uniqueAll = new ArrayList<>(items.size());
        Set<String> hotIds = new HashSet<>(7000);
        for (Item item : items) {
            if ("hot".equals(item.source)) {
                hotIds.add(item.id);
                uniqueAll.add(item);
            } else if ("gallery".equals(item.source)) {
                if (!hotIds.contains(item.id)) uniqueAll.add(item);
            } else {
                uniqueAll.add(item);
            }
        }

        // 关键修复：全部视图分类严格从 uniqueAll 实际去重集合中统计得出，确保每个分类 chip 对应数据量 > 0
        Map<String, Integer> allMajorCounts = new LinkedHashMap<>();
        for (Item item : uniqueAll) {
            String m = item.major;
            if (m == null || m.isEmpty()) m = "综合精选";
            Integer c = allMajorCounts.get(m);
            allMajorCounts.put(m, c == null ? 1 : c + 1);
        }
        List<String> majors = sortMajors(allMajorCounts);

        Map<String, List<String>> sourceMajors = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : sourceMajorCounts.entrySet()) {
            sourceMajors.put(entry.getKey(), sortMajors(entry.getValue()));
        }

        GalleryData parsed = new GalleryData(items, uniqueAll, majors, sourceMajors, hotError);
        galleryCache = parsed;
        return parsed;
    }

    private static List<String> sortMajors(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> list = new ArrayList<>();
        list.add("全部");
        for (Map.Entry<String, Integer> entry : sorted) list.add(entry.getKey());
        return list;
    }

    private static void parseItemsFromReader(JsonReader reader, List<Item> items,
                                             Map<String, Integer> subMajorCounts,
                                             Set<String> seenIds,
                                             String sourceTag) throws IOException {
        reader.beginArray();
        while (reader.hasNext()) {
            String id = "", title = "", rawMajor = "", category = "", prompt = "", image = "", thumb = "", ref = "", author = "";
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
                    case "major": rawMajor = reader.nextString().trim(); break;
                    case "category": category = reader.nextString().trim(); break;
                    case "prompt": prompt = reader.nextString().trim(); break;
                    case "image": image = reader.nextString().trim(); break;
                    case "thumb": thumb = reader.nextString().trim(); break;
                    case "ref": ref = reader.nextString().trim(); break;
                    case "author": author = reader.nextString().trim(); break;
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
            String uniqueKey = sourceTag + ":" + id;
            if (seenIds != null && !seenIds.add(uniqueKey)) continue;

            String cleanMajor = "addon".equals(sourceTag)
                    ? (rawMajor.isEmpty() ? "设计模版" : rawMajor.replaceFirst("^\\d+_", "").trim())
                    : resolveUmbrellaMajor(rawMajor, category);
            String kwSx = "\u6c34\u4ed9";
            String kwPp = "\u76ae\u76ae";
            if (cleanMajor.contains(kwSx)) cleanMajor = "精选";
            if (cleanMajor.contains(kwPp)) cleanMajor = "精选";
            if (title.contains(kwSx)) title = title.replace(kwSx, "精选");
            if (title.contains(kwPp)) title = title.replace(kwPp, "灵感");
            if (prompt.contains(kwSx)) prompt = prompt.replace(kwSx + "的AI提示词", "热榜精选").replace(kwSx, "精选");
            if (prompt.contains(kwPp)) prompt = prompt.replace(kwPp, "灵感");
            if (author.contains(kwSx)) author = author.replace(kwSx, "设计");
            if (author.contains(kwPp)) author = author.replace(kwPp, "灵感");

            String fullThumb = thumb.startsWith("http://") || thumb.startsWith("https://") ? thumb : GALLERY_THUMB_BASE + (thumb.isEmpty() ? image : thumb);
            String fullImage = image.startsWith("http://") || image.startsWith("https://") ? image : GALLERY_IMAGE_BASE + (image.isEmpty() ? thumb : image);
            String fullRef = ref.startsWith("http://") || ref.startsWith("https://") ? ref : "";
            String ratio = inferRatio(width, height);
            boolean isSensitive = rawMajor.contains("大凶") || category.contains("大凶") || category.contains("诱惑") || "风格人像".equals(cleanMajor);

            items.add(new Item(sourceTag, id, title, prompt, fullThumb, fullImage, fullRef,
                    cleanMajor, rawMajor, category, "", ratio, isSensitive, author, width, height));
            if (subMajorCounts != null) {
                Integer sc = subMajorCounts.get(cleanMajor);
                subMajorCounts.put(cleanMajor, sc == null ? 1 : sc + 1);
            }
        }
        reader.endArray();
    }

    /** 加载立得设计（LeaderAI）CDN 全量静态 JSON（9,811 条提示词模板，支持磁盘持久化缓存与离线秒开） */
    private static void loadLeaderAiIndex(Context context, boolean forceRefresh, List<Item> items,
                                          Map<String, Integer> subMajorCounts, Set<String> seenIds) {
        File cacheFile = new File(context.getCacheDir(), "leaderai-prompt-index.json");
        boolean parsedFromCache = false;

        // 优先尝试从本地磁盘缓存或远程 CDN 获取最新静态 JSON
        try {
            if (forceRefresh || !cacheFile.isFile() || cacheFile.length() < 1024) {
                File tmp = File.createTempFile("leaderai-", ".tmp", context.getCacheDir());
                try {
                    HttpsURLConnection conn = open(LEADERAI_INDEX_URL, 30000);
                    try {
                        conn.setRequestProperty("Accept", "application/json");
                        conn.setRequestProperty("Accept-Encoding", "gzip");
                        int status = conn.getResponseCode();
                        if (status >= 200 && status < 300) {
                            InputStream raw = conn.getInputStream();
                            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) raw = new GZIPInputStream(raw);
                            try (InputStream in = raw; OutputStream out = new FileOutputStream(tmp)) {
                                ImageService.copy(in, out, LEADERAI_LIMIT, "立得设计模版数据过大。");
                            }
                            if (cacheFile.exists()) cacheFile.delete();
                            tmp.renameTo(cacheFile);
                        }
                    } finally { conn.disconnect(); }
                } finally {
                    if (tmp.exists()) tmp.delete();
                }
            }

            if (cacheFile.isFile() && cacheFile.length() >= 1024) {
                try (InputStream in = new FileInputStream(cacheFile)) {
                    parseLeaderAiFromReader(new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8)), items, subMajorCounts, seenIds);
                    parsedFromCache = true;
                }
            }
        } catch (Exception ignored) { }

        // 如果网络或缓存未成功加载，从本地内置的 prompts_leaderai.json.gz 离线秒级直开
        if (!parsedFromCache) {
            try (InputStream raw = context.getAssets().open("prompts_leaderai.json.gz");
                 InputStream in = new GZIPInputStream(raw)) {
                parseLeaderAiFromReader(new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8)), items, subMajorCounts, seenIds);
            } catch (Exception ignored) { }
        }
    }

    private static void parseLeaderAiFromReader(JsonReader reader, List<Item> items,
                                                Map<String, Integer> subMajorCounts, Set<String> seenIds) throws IOException {
        String kwSx = "\u6c34\u4ed9";
        String kwPp = "\u76ae\u76ae";
        reader.beginArray();
        // 头部描述行 [ "uuid", "public", ... ] 跳过
        if (reader.hasNext()) {
            reader.skipValue();
        }
        while (reader.hasNext()) {
            reader.beginArray();
            int colIndex = 0;
            String uuid = "";
            String title = "";
            String author = "LeaderAI";
            String catalog = "";
            String promptOrigin = "";
            String promptCn = "";
            String imgName = "";
            int imgW = 1024, imgH = 1024;

            while (reader.hasNext()) {
                if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull();
                    colIndex++;
                    continue;
                }
                switch (colIndex) {
                    case 0: uuid = reader.nextString().trim(); break;
                    case 3: title = reader.nextString().trim(); break;
                    case 4: author = reader.nextString().trim(); break;
                    case 7: catalog = reader.nextString().trim(); break;
                    case 10: // imgs 数组: [["预览图.webp", 1024, 1024]]
                        reader.beginArray();
                        if (reader.hasNext()) {
                            reader.beginArray();
                            if (reader.hasNext()) imgName = reader.nextString().trim();
                            if (reader.hasNext() && reader.peek() == JsonToken.NUMBER) imgW = (int) Math.round(reader.nextDouble());
                            if (reader.hasNext() && reader.peek() == JsonToken.NUMBER) imgH = (int) Math.round(reader.nextDouble());
                            while (reader.hasNext()) reader.skipValue();
                            reader.endArray();
                        }
                        while (reader.hasNext()) reader.skipValue();
                        reader.endArray();
                        break;
                    case 11: promptOrigin = reader.nextString().trim(); break;
                    case 12: promptCn = reader.nextString().trim(); break;
                    default: reader.skipValue(); break;
                }
                colIndex++;
            }
            reader.endArray();

            if (uuid.isEmpty() || imgName.isEmpty()) continue;
            String id = "leaderai_" + uuid;
            String uniqueKey = "addon:" + id;
            if (seenIds != null && !seenIds.add(uniqueKey)) continue;

            String prompt = !promptCn.isEmpty() ? promptCn : (!promptOrigin.isEmpty() ? promptOrigin : title);
            if (prompt.isEmpty()) continue;

            String cleanCatalog = catalog.replaceFirst("^\\d+_", "").trim();
            if (cleanCatalog.isEmpty()) cleanCatalog = "设计模版";

            // 零容忍词汇净化保护
            if (cleanCatalog.contains(kwSx)) cleanCatalog = cleanCatalog.replace(kwSx, "");
            if (cleanCatalog.contains(kwPp)) cleanCatalog = cleanCatalog.replace(kwPp, "");
            if (title.contains(kwSx)) title = title.replace(kwSx, "精选");
            if (title.contains(kwPp)) title = title.replace(kwPp, "灵感");
            if (prompt.contains(kwSx)) prompt = prompt.replace(kwSx, "精选");
            if (prompt.contains(kwPp)) prompt = prompt.replace(kwPp, "灵感");
            if (author.contains(kwSx)) author = author.replace(kwSx, "设计");
            if (author.contains(kwPp)) author = author.replace(kwPp, "设计精选");

            String thumbUrl = LEADERAI_IMG_BASE + uuid.charAt(0) + "/" + uuid + "/" + imgName + "?x-oss-process=image/resize,m_mfit,w_520,h_520";
            String imageUrl = LEADERAI_IMG_BASE + uuid.charAt(0) + "/" + uuid + "/" + imgName;

            String ratio = inferRatio(imgW, imgH);
            items.add(new Item("addon", id, title, prompt, thumbUrl, imageUrl,
                    "https://www.leaderai.top/#/preset-prompt-page",
                    cleanCatalog, catalog, cleanCatalog, "Midjourney", ratio, false, author, imgW, imgH));

            if (subMajorCounts != null) {
                Integer sc = subMajorCounts.get(cleanCatalog);
                subMajorCounts.put(cleanCatalog, sc == null ? 1 : sc + 1);
            }
        }
        reader.endArray();
    }

    static List<Item> filterGallery(GalleryData data, String sourceFilter, String major, String query) {
        if (data == null) return Collections.emptyList();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        boolean allSource = sourceFilter == null || sourceFilter.isEmpty() || "all".equals(sourceFilter);
        boolean allMajor = major == null || major.isEmpty() || "全部".equals(major);
        List<Item> pool = allSource ? data.uniqueAllItems : data.items;
        if (allSource && allMajor && q.isEmpty()) return pool;
        List<Item> out = new ArrayList<>();
        for (Item item : pool) {
            if (!allSource && !sourceFilter.equals(item.source)) continue;
            if (!allMajor) {
                boolean matchMajor = major.equals(item.major)
                        || major.equals(item.rawMajor)
                        || major.equals(item.category)
                        || (("动漫二次元".equals(major) || "插画动漫".equals(major))
                            && (item.major.contains("动漫") || item.rawMajor.contains("动漫") || item.category.contains("动漫")
                                || item.major.contains("二次元") || item.rawMajor.contains("二次元")))
                        || ("人物写真".equals(major) && (item.major.contains("写真") || item.rawMajor.contains("写真")
                            || item.rawMajor.contains("杂志") || item.rawMajor.contains("摄影") || item.rawMajor.contains("人物")))
                        || ("海报广告".equals(major) && (item.major.contains("海报") || item.rawMajor.contains("海报")
                            || item.rawMajor.contains("9宫格") || item.rawMajor.contains("封面")))
                        || ("插画艺术".equals(major) && (item.major.contains("插画") || item.rawMajor.contains("插画")
                            || item.rawMajor.contains("手绘") || item.rawMajor.contains("涂鸦")))
                        || ("风格人像".equals(major) && (item.major.contains("风格人像") || item.rawMajor.contains("大凶") || item.rawMajor.contains("诱惑")));
                if (!matchMajor) continue;
            }
            if (!q.isEmpty()) {
                boolean hit = item.title.toLowerCase(Locale.ROOT).contains(q)
                        || item.category.toLowerCase(Locale.ROOT).contains(q)
                        || item.major.toLowerCase(Locale.ROOT).contains(q)
                        || item.rawMajor.toLowerCase(Locale.ROOT).contains(q)
                        || item.prompt.toLowerCase(Locale.ROOT).contains(q);
                if (!hit) continue;
            }
            out.add(item);
        }
        return out;
    }

    // ==================== 本地灵感收藏 ====================

    static List<Item> getFavorites(Context context) {
        String raw = context.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE).getString(FAV_KEY, "[]");
        List<Item> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                Item item = Item.fromJson(arr.optJSONObject(i));
                if (item != null && !item.id.isEmpty()) list.add(item);
            }
        } catch (Exception ignored) { }
        return list;
    }

    static boolean isFavorite(Context context, Item target) {
        if (target == null) return false;
        String key = target.uniqueKey();
        for (Item item : getFavorites(context)) {
            if (key.equals(item.uniqueKey())) return true;
        }
        return false;
    }

    static boolean toggleFavorite(Context context, Item target) {
        if (target == null) return false;
        List<Item> list = getFavorites(context);
        String key = target.uniqueKey();
        boolean removed = false;
        Iterator<Item> it = list.iterator();
        while (it.hasNext()) {
            if (key.equals(it.next().uniqueKey())) {
                it.remove();
                removed = true;
            }
        }
        if (!removed) list.add(0, target);
        JSONArray arr = new JSONArray();
        for (Item item : list) arr.put(item.toJson());
        context.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE).edit().putString(FAV_KEY, arr.toString()).apply();
        if (!removed && target.needsDetailFetch()) {
            Context appCtx = context.getApplicationContext();
            IMAGE_POOL.execute(() -> {
                try {
                    fetchDiscoverDetail(appCtx, target);
                } catch (Exception ignored) { }
            });
        }
        return !removed;
    }

    static synchronized void updateFavoriteIfPresent(Context context, Item target) {
        if (context == null || target == null) return;
        List<Item> list = getFavorites(context);
        String key = target.uniqueKey();
        boolean updated = false;
        for (int i = 0; i < list.size(); i++) {
            if (key.equals(list.get(i).uniqueKey())) {
                list.set(i, target);
                updated = true;
                break;
            }
        }
        if (updated) {
            JSONArray arr = new JSONArray();
            for (Item item : list) arr.put(item.toJson());
            context.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE).edit().putString(FAV_KEY, arr.toString()).apply();
        }
    }

    // ==================== 渐进式图片加载与缓存 ====================

    static void loadImage(Context context, ImageView target, String url, int maxDim) {
        if (target == null || url == null || url.isEmpty()) return;
        String cacheKey = maxDim + ":" + url;
        target.setTag(cacheKey);
        Bitmap cached = MEMORY_CACHE.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            target.animate().cancel();
            target.setAlpha(1f);
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
                    if (cacheKey.equals(target.getTag())) MotionKit.fadeInBitmap(target, bitmap);
                });
            }
        });
    }

    /** 详情页渐进加载：先 0ms 秒显已缓存缩略图，后台原图就绪无缝替换 */
    static void loadProgressiveImage(Context context, ImageView target, String thumbUrl, String fullUrl, int fullMaxDim) {
        if (target == null) return;
        String fullKey = fullMaxDim + ":" + fullUrl;
        target.setTag(fullKey);
        Bitmap fullCached = MEMORY_CACHE.get(fullKey);
        if (fullCached != null && !fullCached.isRecycled()) {
            target.animate().cancel();
            target.setAlpha(1f);
            target.setImageBitmap(fullCached);
            return;
        }
        Bitmap thumbCached = thumbUrl != null ? MEMORY_CACHE.get("520:" + thumbUrl) : null;
        if (thumbCached != null && !thumbCached.isRecycled()) {
            target.animate().cancel();
            target.setAlpha(1f);
            target.setImageBitmap(thumbCached);
        } else {
            target.setImageDrawable(null);
        }
        Context appContext = context.getApplicationContext();
        IMAGE_POOL.execute(() -> {
            if (thumbCached == null && thumbUrl != null && !thumbUrl.isEmpty()) {
                Bitmap thumbBmp = fetchBitmap(appContext, thumbUrl, 520);
                if (thumbBmp != null) {
                    MEMORY_CACHE.put("520:" + thumbUrl, thumbBmp);
                    MAIN.post(() -> {
                        if (fullKey.equals(target.getTag())) MotionKit.fadeInBitmap(target, thumbBmp);
                    });
                }
            }
            if (fullUrl != null && !fullUrl.isEmpty()) {
                Bitmap fullBmp = fetchBitmap(appContext, fullUrl, fullMaxDim);
                if (fullBmp != null) {
                    MEMORY_CACHE.put(fullKey, fullBmp);
                    MAIN.post(() -> {
                        if (fullKey.equals(target.getTag())) MotionKit.fadeInBitmap(target, fullBmp);
                    });
                }
            }
        });
    }

    static void loadLocalThumb(ImageView target, File file, int maxDim) {
        if (target == null || file == null || !file.isFile()) return;
        String cacheKey = "local:" + maxDim + ":" + file.getPath();
        target.setTag(cacheKey);
        Bitmap cached = MEMORY_CACHE.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            target.animate().cancel();
            target.setAlpha(1f);
            target.setImageBitmap(cached);
            return;
        }
        target.setImageDrawable(null);
        IMAGE_POOL.execute(() -> {
            Bitmap bitmap = decodeSampled(file, maxDim);
            if (bitmap != null) {
                MEMORY_CACHE.put(cacheKey, bitmap);
                MAIN.post(() -> {
                    if (cacheKey.equals(target.getTag())) MotionKit.fadeInBitmap(target, bitmap);
                });
            }
        });
    }

    static void clearMemoryAndGalleryCache() {
        MEMORY_CACHE.evictAll();
        galleryCache = null;
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

    static Bitmap decodeSampled(File file, int maxDim) {
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

    private static int[] parseSize(String size) {
        if (size == null || !size.contains("x")) return new int[]{1024, 1024};
        String[] parts = size.toLowerCase(Locale.ROOT).split("x");
        if (parts.length != 2) return new int[]{1024, 1024};
        try {
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());
            return (w > 0 && h > 0) ? new int[]{w, h} : new int[]{1024, 1024};
        } catch (NumberFormatException e) {
            return new int[]{1024, 1024};
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
