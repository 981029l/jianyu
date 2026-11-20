package studio.jianyu.app;

import java.io.IOException;
import java.util.function.LongSupplier;

final class GenerationPolling {
    static final long INITIAL_DELAY_MS = 2500;
    static final long MAX_DELAY_MS = 30000;
    static final long MAX_POLLING_MS = 20 * 60 * 1000;
    static final int QUERY_TIMEOUT_MS = 60000;
    private final LongSupplier clock;
    private long startedAt = -1;
    private long delayMs = INITIAL_DELAY_MS;

    GenerationPolling(LongSupplier clock) { this.clock = clock; }

    long nextDelayMillis() throws IOException {
        long delay = Math.min(delayMs, remainingMillis());
        delayMs = Math.min(MAX_DELAY_MS, delayMs * 2);
        return delay;
    }

    int queryTimeoutMillis() throws IOException {
        return (int) Math.min(QUERY_TIMEOUT_MS, remainingMillis());
    }

    private long remainingMillis() throws IOException {
        long now = clock.getAsLong();
        if (startedAt < 0) startedAt = now;
        long remaining = MAX_POLLING_MS - (now - startedAt);
        if (remaining <= 0) throw new IOException("已达到 20 分钟自动查询时限，原任务已保留，可稍后继续获取。");
        return remaining;
    }
}
