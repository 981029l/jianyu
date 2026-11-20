package studio.jianyu.app;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

final class GenerationDownload implements AutoCloseable {
    static final int MAX_DOWNLOAD_MS = 3 * 60 * 1000;
    static final int READ_TIMEOUT_MS = 90000;
    static final int MAX_REDIRECTS = 5;
    private static final ScheduledThreadPoolExecutor WATCHDOG = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "jianyu-download-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    static { WATCHDOG.setRemoveOnCancelPolicy(true); }

    private final LongSupplier clock;
    private final long deadline;
    private final ScheduledFuture<?> timeout;
    private volatile boolean expired;
    private boolean closed;
    private volatile long received;
    private volatile long total = -1;

    GenerationDownload(LongSupplier clock, Runnable abort) {
        this(clock, abort, MAX_DOWNLOAD_MS);
    }

    GenerationDownload(LongSupplier clock, Runnable abort, long timeoutMs) {
        this.clock = clock;
        deadline = clock.getAsLong() + timeoutMs;
        timeout = WATCHDOG.schedule(() -> {
            synchronized (this) {
                if (closed) return;
                expired = true;
                abort.run();
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    int timeoutMillis() throws IOException {
        check();
        return (int) Math.max(1, Math.min(READ_TIMEOUT_MS, deadline - clock.getAsLong()));
    }

    void check() throws IOException {
        if (expired || clock.getAsLong() >= deadline)
            throw new IOException("图片已生成，但下载超过 3 分钟，请检查网络后继续获取。");
    }

    void setTotal(long bytes) { total = bytes > 0 ? bytes : -1; }
    void addBytes(int bytes) { if (bytes > 0) received += bytes; }

    void finish() throws IOException {
        check();
        if (total > 0 && received != total) throw new IOException("图片下载不完整，请继续获取。");
    }

    String progressLabel() {
        long bytes = received;
        long size = total;
        if (bytes == 0 && size <= 0) return "正在连接图片服务器…";
        String text = "已下载 " + formatBytes(bytes);
        return size > 0 ? text + " / " + formatBytes(size) + " · " + Math.min(100, bytes * 100 / size) + "%" : text;
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        if (bytes >= 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }

    @Override public synchronized void close() {
        closed = true;
        timeout.cancel(false);
    }
}
