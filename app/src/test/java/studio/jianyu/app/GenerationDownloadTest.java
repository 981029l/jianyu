package studio.jianyu.app;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class GenerationDownloadTest {
    @Test public void separateDownloadsKeepIndependentProgress() throws Exception {
        GenerationDownload first = new GenerationDownload();
        GenerationDownload second = new GenerationDownload();
        first.setTotal(2048);
        first.addBytes(1024);
        assertEquals("已下载 1.0 KB / 2.0 KB · 50%", first.progressLabel());
        assertEquals("正在连接图片服务器…", second.progressLabel());
        first.addBytes(1024);
        first.finish();
        assertEquals("已下载 2.0 KB / 2.0 KB · 100%", first.progressLabel());
    }

    @Test public void incompleteDownloadStillFailsIntegrityCheck() {
        GenerationDownload progress = new GenerationDownload();
        progress.setTotal(2048);
        progress.addBytes(1024);
        assertThrows(IOException.class, progress::finish);
    }

    @Test public void unknownLengthReportsOnlyReceivedBytes() throws Exception {
        GenerationDownload progress = new GenerationDownload();
        progress.addBytes(4096);
        progress.finish();
        assertEquals("已下载 4.0 KB", progress.progressLabel());
    }
}
