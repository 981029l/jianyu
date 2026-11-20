package studio.jianyu.app;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class GenerationDownloadTest {
    @Test public void stalledConnectionIsAbortedEvenWithoutMoreReads() throws Exception {
        CountDownLatch aborted = new CountDownLatch(1);
        try (GenerationDownload progress = new GenerationDownload(() -> 0L, aborted::countDown, 50)) {
            assertTrue(aborted.await(5, TimeUnit.SECONDS));
            assertThrows(IOException.class, progress::check);
        }
    }

    @Test public void closingDownloadCancelsWatchdog() throws Exception {
        CountDownLatch aborted = new CountDownLatch(1);
        GenerationDownload progress = new GenerationDownload(() -> 0L, aborted::countDown, 200);
        progress.close();
        assertFalse(aborted.await(300, TimeUnit.MILLISECONDS));
    }

    @Test public void separateDownloadsHaveIndependentDeadlinesAndProgress() throws Exception {
        AtomicLong clock = new AtomicLong(100);
        try (GenerationDownload first = new GenerationDownload(clock::get, () -> {})) {
            first.setTotal(2048);
            first.addBytes(1024);
            clock.addAndGet(100000);
            try (GenerationDownload second = new GenerationDownload(clock::get, () -> {})) {
                assertEquals(80000, first.timeoutMillis());
                assertEquals(GenerationDownload.READ_TIMEOUT_MS, second.timeoutMillis());
                assertEquals("已下载 1.0 KB / 2.0 KB · 50%", first.progressLabel());
                assertEquals("正在连接图片服务器…", second.progressLabel());
                clock.addAndGet(80000);
                assertThrows(IOException.class, first::check);
                second.check();
            }
        }
    }
}
