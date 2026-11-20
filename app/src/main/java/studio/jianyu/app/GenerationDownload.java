package studio.jianyu.app;

import java.io.IOException;
import java.util.Locale;

final class GenerationDownload {
    static final int READ_TIMEOUT_MS = 90000;
    static final int MAX_REDIRECTS = 5;
    static final int MAX_RETRIES = 3;
    static final int RETRY_DELAY_MS = 2000;
    private volatile long received;
    private volatile long total = -1;
    private volatile String phase = "";

    static final class RetryException extends IOException {
        RetryException(String message) { super(message); }
        RetryException(String message, IOException cause) { super(message, cause); }
    }

    void setTotal(long bytes) { total = bytes > 0 ? bytes : -1; }
    void setReceived(long bytes) { received = bytes; }
    void setPhase(String text) { phase = text; }
    void addBytes(int bytes) { if (bytes > 0) received += bytes; }

    void finish() throws IOException {
        if (total > 0 && received != total) throw new IOException("图片下载不完整，请继续获取。");
    }

    String progressLabel() {
        long bytes = received;
        long size = total;
        if (bytes == 0 && size <= 0) return phase + "正在连接图片服务器…";
        String text = "已下载 " + formatBytes(bytes);
        return phase + (size > 0 ? text + " / " + formatBytes(size) + " · " + Math.min(100, bytes * 100 / size) + "%" : text);
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        if (bytes >= 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }
}
