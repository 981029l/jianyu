package studio.jianyu.app;

import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DownloadCheckpoint {
    private static final String URL_KEY = "download-url";
    private static final String ETAG_KEY = "download-etag";
    private static final String MODIFIED_KEY = "download-modified";
    private static final String TOTAL_KEY = "download-total";
    private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final Pattern STRONG_ETAG = Pattern.compile("\"[!#-~\\x80-\\xFF]*\"");
    private static final long MIN_MODIFIED_AGE_MS = 60000;
    final File file;
    private final SharedPreferences prefs;
    private String address;

    DownloadCheckpoint(File file, SharedPreferences prefs) { this.file = file; this.prefs = prefs; }

    void prepare(String url) throws IOException {
        address = url;
        if (!url.equals(prefs.getString(URL_KEY, "")) || file.length() > ImageService.IMAGE_LIMIT) reset();
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建下载缓存目录。");
    }

    long total() { return prefs.getLong(TOTAL_KEY, -1); }
    boolean complete() { return total() > 0 && file.length() == total(); }
    boolean resumable() { return file.length() > 0 && !validator().isEmpty(); }

    private String validator() {
        String etag = prefs.getString(ETAG_KEY, "");
        return !etag.isEmpty() ? etag : prefs.getString(MODIFIED_KEY, "");
    }

    void requestRange(HttpURLConnection conn, long offset) {
        if (offset > 0) {
            conn.setRequestProperty("Range", "bytes=" + offset + "-");
            conn.setRequestProperty("If-Range", validator());
        }
    }

    long accept(HttpURLConnection conn, int status, long offset) throws IOException {
        String etag = conn.getHeaderField("ETag");
        boolean hasEtag = etag != null && !etag.isEmpty();
        etag = etag != null && STRONG_ETAG.matcher(etag).matches() ? etag : "";
        String modified = conn.getHeaderField("Last-Modified");
        long modifiedAt = conn.getHeaderFieldDate("Last-Modified", -1), datedAt = conn.getHeaderFieldDate("Date", -1);
        modified = !hasEtag && modified != null && modifiedAt >= 0 && datedAt - modifiedAt >= MIN_MODIFIED_AGE_MS ? modified : "";
        String encoding = conn.getContentEncoding();
        boolean identity = encoding == null || "identity".equalsIgnoreCase(encoding);
        long size = conn.getContentLengthLong(), end = size > 0 && identity ? size : -1;
        if (status == 206) {
            String header = conn.getHeaderField("Content-Range");
            Matcher range = RANGE.matcher(header == null ? "" : header.trim());
            String current = !prefs.getString(ETAG_KEY, "").isEmpty() ? etag : modified;
            if (offset <= 0 || !identity || !range.matches() || !validator().equals(current)) return invalidRange();
            try {
                long start = Long.parseLong(range.group(1)), last = Long.parseLong(range.group(2));
                long whole = Long.parseLong(range.group(3));
                if (start != offset || last < start || whole <= last || whole > ImageService.IMAGE_LIMIT
                        || (total() > 0 && total() != whole) || (size >= 0 && size != last - start + 1)) return invalidRange();
                end = last + 1;
                size = whole;
            } catch (NumberFormatException error) { return invalidRange(); }
        } else {
            reset();
            if (!identity) { size = -1; etag = modified = ""; }
        }
        if (size > ImageService.IMAGE_LIMIT) { reset(); throw new IOException("图片超过 24 MiB。"); }
        prefs.edit().putString(URL_KEY, address).putString(ETAG_KEY, etag).putString(MODIFIED_KEY, modified)
                .putLong(TOTAL_KEY, size > 0 ? size : -1).commit();
        return end;
    }

    private long invalidRange() throws IOException {
        reset();
        throw new GenerationDownload.RetryException("续传响应不匹配，正在重新完整下载。");
    }

    void reset() throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("无法清理原下载缓存，请稍后继续获取。");
        prefs.edit().remove(URL_KEY).remove(ETAG_KEY).remove(MODIFIED_KEY).remove(TOTAL_KEY).commit();
    }
}
