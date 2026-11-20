package studio.jianyu.app;

import android.content.Context;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.HttpsURLConnection;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ImageServicePollingTest {
    private Context context;
    private String id;
    private ImageService service;
    private AtomicLong clock;
    private List<Long> waits;
    private List<Integer> stages;

    @Before public void prepare() throws Exception {
        context = RuntimeEnvironment.getApplication();
        id = UUID.randomUUID().toString();
        clock = new AtomicLong(100);
        waits = new ArrayList<>();
        stages = new ArrayList<>();
        service = spy(new ImageService(context, id, new GenerationPolling(clock::get)));
        service.setProgressListener(stages::add);
        doReturn(Arrays.asList("test-only-key")).when(service).activeKeysLine1();
        doReturn(Arrays.asList("test-only-key")).when(service).activeKeysLine2();
        doReturn(true).when(service).isDirectMode();
        doAnswer(call -> {
            long wait = call.getArgument(0);
            waits.add(wait);
            clock.addAndGet(wait);
            return null;
        }).when(service).waitForPoll(anyLong());
        doAnswer(call -> { throw new AssertionError("Unexpected network request: " + call.getArgument(0)); })
                .when(service).connect(any(URL.class), anyInt());
    }

    @Test public void gatewayKeepsPolling202WithoutSubmittingAgain() throws Exception {
        doReturn(false).when(service).isDirectMode();
        doReturn("https://example.test/generate").when(service).gatewayEndpoint();
        HttpsURLConnection submit = response(202, "{\"task_id\":\"task-a\"}");
        HttpsURLConnection pending = response(202, "{\"task_id\":\"task-a\"}");
        HttpsURLConnection failed = response(410, "{\"task_id\":\"task-a\",\"error\":\"task failed\"}");
        doReturn(submit, pending, failed).when(service).connect(any(URL.class), anyInt());
        assertThrows(IOException.class, () -> service.run("海边灯塔", "image-1", "1:1", null, false));
        verify(submit).setRequestMethod("POST");
        verify(pending, never()).setRequestMethod("POST");
        verify(failed, never()).setRequestMethod("POST");
        assertEquals(Arrays.asList(2500L, 5000L), waits);
        assertFalse(service.hasPending());
        assertFalse(service.isSubmissionUnconfirmed());
    }

    @Test public void grokNsfwUsesFixedPortraitSizeAndForwardsLongPromptIntact() throws Exception {
        String prompt = "海边灯塔🌊".repeat(1000);
        HttpsURLConnection submit = response(422, "{\"detail\":\"upstream prompt too long\"}");
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        when(submit.getOutputStream()).thenReturn(sent);
        doReturn(submit).when(service).connect(any(URL.class), anyInt());
        IOException error = assertThrows(IOException.class, () -> service.run(prompt, "image-2", "16:9", null, true));
        org.json.JSONObject body = new org.json.JSONObject(sent.toString("UTF-8"));
        assertEquals(prompt, body.getString("prompt"));
        assertEquals("1024x1536", body.getString("size"));
        assertTrue(body.getBoolean("nsfw"));
        assertTrue(error.getMessage().contains("upstream prompt too long"));
        verify(service, times(1)).connect(any(URL.class), anyInt());
    }

    @Test public void gatewayNsfwUsesFixedRatioWithoutChangingOtherModels() throws Exception {
        assertEquals("16:9", ImageService.generationRatio("image-2", "16:9", false));
        assertEquals("16:9", ImageService.generationRatio("image-1", "16:9", true));
        assertEquals("16:9", ImageService.generationRatio("gpt-image-2.5", "16:9", true));
        doReturn(false).when(service).isDirectMode();
        doReturn("https://example.test/generate").when(service).gatewayEndpoint();
        HttpsURLConnection submit = response(400, "{\"error\":\"test rejection\"}");
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        when(submit.getOutputStream()).thenReturn(sent);
        doReturn(submit).when(service).connect(any(URL.class), anyInt());
        assertThrows(IOException.class, () -> service.run("成年角色艺术摄影", "image-2", "1:1", null, true));
        org.json.JSONObject body = new org.json.JSONObject(sent.toString("UTF-8"));
        assertEquals("2:3", body.getString("ratio"));
        assertTrue(body.getBoolean("nsfw"));
    }

    @Test public void lineOneReportsQueueGenerationAndDownloadAndRetainsUrl() throws Exception {
        HttpsURLConnection submit = response(202, "{\"task_id\":\"task-a\",\"status\":\"queued\"}");
        HttpsURLConnection running = response(200, "{\"status\":\"running\"}");
        HttpsURLConnection complete = response(200, "{\"data\":[{\"url\":\"https://example.test/image.png\"}]}");
        HttpsURLConnection download = mock(HttpsURLConnection.class);
        when(download.getResponseCode()).thenThrow(new SocketTimeoutException());
        doReturn(submit, running, complete, download).when(service).connect(any(URL.class), anyInt());
        assertThrows(SocketTimeoutException.class, () -> service.run("海边灯塔", "image-1", "1:1", null, false));
        assertEquals(Arrays.asList(GenerationRecord.STATUS_SUBMITTING, GenerationRecord.STATUS_QUEUED,
                GenerationRecord.STATUS_RUNNING, GenerationRecord.STATUS_DOWNLOADING), stages);
        assertTrue(service.hasPending());
        assertEquals("https://example.test/image.png", context.getSharedPreferences(ImageService.PENDING_PREFS + "-" + id, 0).getString("url", ""));
        verify(service).connect(argThat(url -> url.toString().equals("https://example.test/image.png")), anyInt());
    }

    @Test public void lineTwoRetainsOriginalIdAcrossRateLimitAndMissingId() throws Exception {
        HttpsURLConnection submit = response(202, "{\"task_id\":\"task-b\"}");
        HttpsURLConnection limited = response(429, "{\"error\":\"rate limited\"}");
        HttpsURLConnection queued = response(200, "{\"items\":[{\"status\":\"queued\"}]}");
        HttpsURLConnection failed = response(200, "{\"items\":[{\"status\":\"failed\",\"public_error\":\"rejected\"}]}");
        doReturn(submit, limited, queued, failed).when(service).connect(any(URL.class), anyInt());
        IOException error = assertThrows(IOException.class, () -> service.run("雪山小屋", "gpt-image-2.5", "1:1", null, false));
        assertEquals("rejected", error.getMessage());
        assertEquals(Arrays.asList(2500L, 5000L, 10000L), waits);
        verify(service, times(3)).connect(argThat(url -> url.toString().endsWith("?ids=task-b")), anyInt());
        verify(limited, never()).setRequestMethod("POST");
        verify(queued, never()).setRequestMethod("POST");
        assertFalse(service.hasPending());
    }

    @Test public void deadlineKeepsTaskAndDoesNotMakeAnotherRequest() throws Exception {
        context.getSharedPreferences(ImageService.PENDING_PREFS + "-" + id, 0).edit().putString("task", "old-task").commit();
        doReturn(response(200, "{\"status\":\"running\"}")).when(service).connect(any(URL.class), anyInt());
        doAnswer(call -> { clock.addAndGet(GenerationPolling.MAX_POLLING_MS); return null; })
                .when(service).waitForPoll(anyLong());
        IOException error = assertThrows(IOException.class, () -> service.run("继续", "image-1", "1:1", null, false));
        assertTrue(error.getMessage().contains("20 分钟"));
        assertTrue(service.hasPending());
        verify(service, times(1)).connect(any(URL.class), anyInt());
    }

    @Test public void unknownSubmissionCannotAutomaticallyResubmit() throws Exception {
        HttpsURLConnection submit = response(200, "{}");
        when(submit.getResponseCode()).thenThrow(new SocketTimeoutException());
        doReturn(submit).when(service).connect(any(URL.class), anyInt());
        assertThrows(SocketTimeoutException.class, () -> service.run("断网", "image-1", "1:1", null, false));
        assertTrue(service.isSubmissionUnconfirmed());
        assertFalse(service.hasPending());
        ImageService resumed = spy(new ImageService(context, id));
        assertThrows(IOException.class, () -> resumed.run("断网", "image-1", "1:1", null, false));
        verify(resumed, never()).connect(any(URL.class), anyInt());
    }

    @Test public void resumingDownloadUsesSavedUrlWithoutQueryOrSubmission() throws Exception {
        context.getSharedPreferences(ImageService.PENDING_PREFS + "-" + id, 0).edit()
                .putString("task", "task-c").putString("url", "https://example.test/saved.png").commit();
        HttpsURLConnection download = mock(HttpsURLConnection.class);
        when(download.getResponseCode()).thenThrow(new SocketTimeoutException());
        doReturn(download).when(service).connect(any(URL.class), anyInt());
        assertThrows(SocketTimeoutException.class, () -> service.run("继续下载", "image-1", "1:1", null, false));
        verify(service).connect(argThat(url -> url.toString().equals("https://example.test/saved.png")), anyInt());
        verify(download, never()).setRequestMethod("POST");
        assertTrue(service.hasPending());
    }

    @Test public void stoppingWaitWakesSleepingPollImmediately() throws Exception {
        ImageService waiting = new ImageService(context, UUID.randomUUID().toString());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> {
            entered.countDown();
            try { waiting.waitForPoll(GenerationPolling.MAX_DELAY_MS); }
            catch (Throwable e) { error.set(e); }
            finally { finished.countDown(); }
        });
        worker.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            waiting.cancelActive();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertTrue(error.get() instanceof IOException);
        } finally {
            waiting.cancelActive();
            worker.interrupt();
            worker.join(2000);
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void slowLineTwoDownloadContinuesPastThreeMinutesAndSavesCompleteArtwork() throws Exception {
        retainDownload();
        byte[] png = pngBytes();
        List<String> labels = new ArrayList<>();
        long started = android.os.SystemClock.elapsedRealtime();
        HttpsURLConnection download = imageResponse(slowChunks(png, labels), png.length);
        doReturn(download).when(service).connect(any(URL.class), anyInt());
        String imageId = service.run("慢下载", "gpt-image-2.5", "1:1", null, false);
        assertTrue(android.os.SystemClock.elapsedRealtime() - started > 180000);
        assertTrue(labels.stream().anyMatch(label -> label.matches(".* · 9\\d%")));
        assertTrue(service.downloadProgress().contains("100%"));
        assertEquals(png.length, new File(new File(context.getFilesDir(), "images"), imageId).length());
        assertFalse(service.hasPending());
        assertNoTemporaryImages();
        verify(service, times(1)).connect(any(URL.class), anyInt());
        verify(download, never()).setRequestMethod("POST");
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void redirectChainHasNoCombinedThreeMinuteDeadline() throws Exception {
        retainDownload();
        HttpsURLConnection first = mock(HttpsURLConnection.class);
        when(first.getResponseCode()).thenAnswer(call -> { ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(70)); return 302; });
        when(first.getHeaderField("Location")).thenReturn("/second.png");
        HttpsURLConnection second = mock(HttpsURLConnection.class);
        when(second.getResponseCode()).thenAnswer(call -> { ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(70)); return 302; });
        when(second.getHeaderField("Location")).thenReturn("/third.png");
        HttpsURLConnection third = mock(HttpsURLConnection.class);
        when(third.getResponseCode()).thenAnswer(call -> { ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(70)); return 302; });
        when(third.getHeaderField("Location")).thenReturn("/fourth.png");
        byte[] png = pngBytes();
        doReturn(first, second, third, imageResponse(new ByteArrayInputStream(png), png.length)).when(service).connect(any(URL.class), anyInt());
        assertNotNull(service.run("重定向", "image-1", "1:1", null, false));
        verify(service).connect(argThat(url -> url.getPath().equals("/second.png")), eq(GenerationDownload.READ_TIMEOUT_MS));
        verify(service, times(4)).connect(any(URL.class), eq(GenerationDownload.READ_TIMEOUT_MS));
        assertFalse(service.hasPending());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void successfulDownloadReportsMeasuredProgressAndSavesArtwork() throws Exception {
        retainDownload();
        byte[] png = pngBytes();
        List<String> labels = new ArrayList<>();
        InputStream chunks = new ByteArrayInputStream(png) {
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                labels.add(service.downloadProgress());
                return super.read(buffer, offset, Math.min(length, png.length / 2));
            }
        };
        HttpsURLConnection download = imageResponse(chunks, png.length);
        doReturn(download).when(service).connect(any(URL.class), anyInt());
        String imageId = service.run("海边灯塔", "image-1", "1:1", null, false);
        assertTrue(labels.stream().anyMatch(label -> label.contains((png.length / 2 * 100 / png.length) + "%")));
        assertTrue(service.downloadProgress().contains("100%"));
        assertTrue(service.downloadProgress().contains(png.length + " B"));
        assertTrue(new File(new File(context.getFilesDir(), "images"), imageId).isFile());
        assertEquals("海边灯塔", context.getSharedPreferences("art", 0).getString(imageId, ""));
        assertFalse(service.hasPending());
        assertFalse(service.isSubmissionUnconfirmed());
        verify(download, never()).setRequestMethod("POST");
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void missingContentLengthShowsBytesWithoutInventedPercentage() throws Exception {
        retainDownload();
        byte[] png = pngBytes();
        doReturn(imageResponse(new ByteArrayInputStream(png), -1)).when(service).connect(any(URL.class), anyInt());
        String imageId = service.run("无大小", "image-1", "1:1", null, false);
        assertNotNull(imageId);
        assertEquals("已下载 " + png.length + " B", service.downloadProgress());
        assertFalse(service.hasPending());
    }

    @Test public void truncatedDownloadRetainsUrlInsteadOfSavingIncompleteArtwork() throws Exception {
        retainDownload();
        byte[] png = pngBytes();
        doReturn(imageResponse(new ByteArrayInputStream(png), png.length * 2)).when(service).connect(any(URL.class), anyInt());
        IOException error = assertThrows(IOException.class, () -> service.run("未下完", "image-1", "1:1", null, false));
        assertTrue(error.getMessage().contains("下载不完整"));
        assertRetainedDownload();
        assertNoTemporaryImages();
    }

    @Test public void stoppingDownloadAfterAChunkKeepsUrlAndDeletesPartialFile() throws Exception {
        retainDownload();
        InputStream interrupted = new InputStream() {
            @Override public int read() { throw new AssertionError("Use buffered reads"); }
            @Override public int read(byte[] buffer, int offset, int length) {
                buffer[offset] = 1;
                service.cancelActive();
                return 1;
            }
        };
        doReturn(imageResponse(interrupted, -1)).when(service).connect(any(URL.class), anyInt());
        IOException error = assertThrows(IOException.class, () -> service.run("停止下载", "image-1", "1:1", null, false));
        assertTrue(error.getMessage().contains("已取消"));
        assertRetainedDownload();
        assertNoTemporaryImages();
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void gatewayImageBodyContinuesPastThreeMinutes() throws Exception {
        context.getSharedPreferences(ImageService.PENDING_PREFS + "-" + id, 0).edit().putString("task", "gateway-task").commit();
        doReturn(false).when(service).isDirectMode();
        doReturn("https://example.test/generate").when(service).gatewayEndpoint();
        byte[] png = pngBytes();
        long started = android.os.SystemClock.elapsedRealtime();
        doReturn(imageResponse(slowChunks(png, new ArrayList<>()), -1)).when(service).connect(any(URL.class), anyInt());
        assertNotNull(service.run("网关续取", "image-1", "1:1", null, false));
        assertTrue(android.os.SystemClock.elapsedRealtime() - started > 180000);
        assertFalse(service.hasPending());
        assertNoTemporaryImages();
    }

    @Test public void readStallStillTimesOutAndKeepsOriginalTask() throws Exception {
        retainDownload();
        InputStream timeout = new InputStream() {
            private boolean received;
            @Override public int read() { throw new AssertionError("Use buffered reads"); }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                if (received) throw new SocketTimeoutException("Read timed out");
                received = true;
                buffer[offset] = 1;
                return 1;
            }
        };
        HttpsURLConnection download = imageResponse(timeout, 2048);
        doReturn(download).when(service).connect(any(URL.class), anyInt());
        assertThrows(SocketTimeoutException.class, () -> service.run("停滞下载", "gpt-image-2.5", "1:1", null, false));
        verify(download, atLeastOnce()).setReadTimeout(90000);
        assertRetainedDownload();
        assertNoTemporaryImages();
    }

    private InputStream slowChunks(byte[] png, List<String> labels) {
        return new ByteArrayInputStream(png) {
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(30));
                labels.add(service.downloadProgress());
                return super.read(buffer, offset, Math.min(length, Math.max(1, png.length / 10)));
            }
        };
    }

    private void retainDownload() {
        context.getSharedPreferences(ImageService.PENDING_PREFS + "-" + id, 0).edit()
                .putString("task", "download-task").putString("url", "https://example.test/saved.png").commit();
    }

    private void assertRetainedDownload() {
        assertTrue(service.hasPending());
        assertEquals("https://example.test/saved.png", context.getSharedPreferences(ImageService.PENDING_PREFS + "-" + id, 0).getString("url", ""));
        assertEquals("download-task", context.getSharedPreferences(ImageService.PENDING_PREFS + "-" + id, 0).getString("task", ""));
    }

    private void assertNoTemporaryImages() {
        File[] partials = new File(context.getFilesDir(), "images").listFiles((dir, name) -> name.startsWith("pending-"));
        assertTrue(partials == null || partials.length == 0);
    }

    private byte[] pngBytes() throws IOException {
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
            return output.toByteArray();
        } finally { bitmap.recycle(); }
    }

    private HttpsURLConnection imageResponse(InputStream input, long length) throws Exception {
        HttpsURLConnection conn = mock(HttpsURLConnection.class);
        when(conn.getResponseCode()).thenReturn(200);
        when(conn.getContentType()).thenReturn("image/png");
        when(conn.getContentLengthLong()).thenReturn(length);
        when(conn.getInputStream()).thenReturn(input);
        return conn;
    }

    private HttpsURLConnection response(int status, String json) throws Exception {
        HttpsURLConnection conn = mock(HttpsURLConnection.class);
        when(conn.getResponseCode()).thenReturn(status);
        when(conn.getContentType()).thenReturn("application/json");
        when(conn.getInputStream()).thenAnswer(call -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        when(conn.getErrorStream()).thenAnswer(call -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        when(conn.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        return conn;
    }
}
