package studio.jianyu.app;

import android.content.Context;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
